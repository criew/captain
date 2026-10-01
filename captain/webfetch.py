"""webfetch-Allowlist (``CAPTAIN_WEBFETCH_ALLOW``): Normalisierung und Muster.

Der Admin gibt kommagetrennte URL-Präfixe an, z. B. ``http://text-example.org``
oder ``https://docs.example.org/handbuch``. Leer = webfetch komplett aus.

Wie opencode 2.0.20 webfetch prüft (aus dem Binary ermittelt, per
``tests/test_webfetch_integration.py`` belegt):

- Ressource ist die **rohe URL**, wie das Modell sie übergibt (keine
  Normalisierung; vorher nur ``new URL()`` + Schema http/https).
- Muster: ``*`` = beliebig viele Zeichen (auch ``/``), ``?`` = **genau ein
  beliebiges Zeichen**, sonst wörtlich und mit Groß-/Kleinschreibung; ``\\`` im
  Wert zählt als ``/``. Regeln: letzte passende gewinnt.
- Weiterleitungen folgt opencode selbst, **ohne** erneute Prüfung. Das fängt
  der Egress-Filter in ``infra/opencode/egress.mjs`` auf Host-Ebene ab.

Daraus folgt die Normalisierung: Ein Eintrag wird zu genau zwei Mustern,
``<präfix>`` und ``<präfix>/*``. Das ``/`` direkt hinter Host bzw. Pfad
verhindert Präfix-Tricks (``http://text-example.org.evil.com``,
``http://text-example.org@evil.com``, ``…:8080``); ``?`` und ``*`` kommen in
Einträgen nicht vor (``?`` wäre ein Platzhalter für ein beliebiges Zeichen).
Großgeschriebene Hosts, abweichende Ports oder ein anderes Schema passen nicht
und werden abgelehnt (sicherer Fehler). Pfadsegmente ``..`` (auch
prozentkodiert) und Tab/Zeilenumbruch (entfernt der URL-Parser) werden
zusätzlich verboten, sonst käme man aus einem Pfad-Präfix heraus.

Dieselbe Logik steckt in ``infra/opencode/start.mjs`` (Policies als harte
Obergrenze); gemeinsame Testfälle: ``tests/data/webfetch_allow.json``.
"""

from __future__ import annotations

import re

ENV = "CAPTAIN_WEBFETCH_ALLOW"

_ENTRY = re.compile(
    r"(?P<scheme>[A-Za-z]+)://(?P<host>\[[0-9A-Fa-f:.]+\]|[^/:\[\]]+)(?::(?P<port>[0-9]{1,5}))?(?P<path>/.*)?",
    re.DOTALL,
)
_LABEL = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
_DEFAULT_PORT = {"http": 80, "https": 443}
# Zeichen, die in einem Eintrag nie vorkommen dürfen: Platzhalter der Muster
# (``*``, ``?``), Query/Fragment, Backslash (opencode liest ihn als ``/``),
# Userinfo, Leer- und Steuerzeichen.
_FORBIDDEN = re.compile(r"[*?#\\@\s\x00-\x1f\x7f]")

# Nach den Freigaben: kein ``..``-Segment (auch kodiert), keine Zeichen, die
# der URL-Parser still entfernt (Tab, LF, CR – daraus würde z. B. ``/.\t./``
# ein ``/../``). ``?`` ist hier gewollt der Ein-Zeichen-Platzhalter.
DENY_PATTERNS = (
    "*/..", "*/..?*",
    "*/%2e*", "*/%2E*", "*/.%2e*", "*/.%2E*",
    "*\t*", "*\n*", "*\r*",
)


def _host(raw: str) -> str:
    host = raw.lower()
    if host.startswith("["):
        return host  # IPv6-Literal, Zeichen schon durch _ENTRY begrenzt
    labels = host.split(".")
    if len(host) > 253 or not all(_LABEL.fullmatch(label) for label in labels):
        raise ValueError("ungültiger Hostname (nur a-z, 0-9, '-', Punkte; IDN als Punycode)")
    return host


def normalize_entry(entry: str) -> str:
    """Ein Eintrag → kanonisches Präfix ``schema://host[:port][/pfad]``."""
    if not entry.isascii():
        raise ValueError("nur ASCII (IDN als Punycode xn--…)")
    bad = _FORBIDDEN.search(entry)
    if bad:
        raise ValueError(f"Zeichen {bad.group()!r} nicht erlaubt (keine Platzhalter, Query, Userinfo)")
    m = _ENTRY.fullmatch(entry)
    if not m:
        raise ValueError("Format: http(s)://host[:port][/pfad]")
    scheme = m.group("scheme").lower()
    if scheme not in _DEFAULT_PORT:
        raise ValueError("nur http:// oder https://")
    host = _host(m.group("host"))
    port = ""
    if m.group("port") is not None:
        number = int(m.group("port"))
        if not 0 < number < 65536:
            raise ValueError("ungültiger Port")
        if number != _DEFAULT_PORT[scheme]:
            port = f":{number}"
    path = (m.group("path") or "").rstrip("/")
    segments = path.split("/")[1:]
    if any(s in (".", "..") or "%2e" in s.lower() for s in segments) or "//" in path:
        raise ValueError("Pfad ohne '.', '..', '%2e' und leere Segmente")
    return f"{scheme}://{host}{port}{path}"


def parse(value: str | None) -> tuple[str, ...]:
    """Kommagetrennte Einträge → kanonische Präfixe (dedupliziert, Reihenfolge bleibt).

    Wirft ``ValueError`` mit dem fehlerhaften Eintrag – lieber nicht starten
    als still etwas anderes freigeben.
    """
    out: list[str] = []
    for raw in (value or "").split(","):
        entry = raw.strip()
        if not entry:
            continue
        try:
            prefix = normalize_entry(entry)
        except ValueError as e:
            raise ValueError(f"{ENV}: Eintrag {entry!r} ungültig: {e}") from None
        if prefix not in out:
            out.append(prefix)
    return tuple(out)


def allow_patterns(prefixes) -> list[str]:
    """Muster für Regeln/Policies: je Präfix ``p`` und ``p/*``."""
    return [p for prefix in prefixes for p in (prefix, f"{prefix}/*")]


def session_rules(prefixes) -> list[dict]:
    """Session-Regeln, die *nach* ``webfetch * deny`` kommen (letzte gewinnt)."""
    if not prefixes:
        return []
    rules = [{"action": "webfetch", "resource": p, "effect": "allow"} for p in allow_patterns(prefixes)]
    rules += [{"action": "webfetch", "resource": p, "effect": "deny"} for p in DENY_PATTERNS]
    return rules
