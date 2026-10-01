"""Geteiltes Verzeichnis (``CAPTAIN_SHARED_DIR``): nur lesend für alle Chats.

Der Admin nennt in der ``.env`` ein Host-Verzeichnis, z. B.
``CAPTAIN_SHARED_DIR=/srv/captain-shared``. ``compose.deploy.yml`` bindet es
schreibgeschützt (``:ro``) unter dem festen Pfad :data:`MOUNT` (``/shared``)
in den opencode-Container ein. Leer = Feature aus (dann hängt dort nur das
leere Volume ``shared-leer``, und nichts gibt es frei).

Wie opencode 2.0.20 Pfade außerhalb des Session-Verzeichnisses prüft (aus dem
Binary, ``FileAccess.resolve``; belegt durch
``tests/test_shared_integration.py``):

- Der Pfad des Modells wird **lexikalisch** aufgelöst (``path.resolve`` gegen
  das Session-Verzeichnis: ``..`` und ``.`` fallen weg, **kein realpath**).
- Liegt er außerhalb, prüft opencode zuerst ``external_directory`` mit
  Ressource ``<verzeichnis>/*`` (bei einer Datei ihr Elternverzeichnis), dann
  ``read`` mit dem absoluten Pfad; ``glob``/``grep`` prüfen das Suchmuster,
  der Suchpfad läuft ebenfalls über ``external_directory``.
- Muster: ``*`` = beliebig viel (auch ``/``), ``?`` = ein Zeichen,
  Groß-/Kleinschreibung zählt. ``/shared/*`` passt also auf ``/shared/*``
  (das Verzeichnis selbst) und alles darunter, nicht auf ``/sharedX/*``.
- **Symlinks** löst opencode nicht auf – ``/shared/x`` → ``/etc/passwd``
  würde als ``/shared/x`` geprüft und dann gelesen. Deshalb prüft
  ``infra/opencode/shared.mjs`` beim Start und danach alle 10 s, dass unter
  ``/shared`` keine Symlinks (und keine Dateien mit mehreren harten Links,
  keine Captain-eigenen Verzeichnisse) liegen; sonst startet bzw. läuft
  opencode nicht weiter (fail closed).
- ``.git`` (in jeder Tiefe) ist gesperrt: ``.git/config`` kann Zugangsdaten
  in Remote-URLs enthalten.

Dieselbe Pfadprüfung steckt in ``infra/opencode/shared.mjs`` (gemeinsame
Testfälle: ``tests/data/shared_dir.json``); dort entstehen auch die Policies
(``external_directory:/shared/*`` erlaubt, ``.git`` und ``edit`` unter
``/shared`` verboten).
"""

from __future__ import annotations

import re

ENV = "CAPTAIN_SHARED_DIR"
# Fester Pfad im opencode-Container (compose.deploy.yml)
MOUNT = "/shared"

# Wurzel und Systemverzeichnisse: nie als Ganzes freigeben.
SYSTEM_DIRS = frozenset({
    "/", "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib32", "/lib64", "/media", "/mnt",
    "/opt", "/proc", "/root", "/run", "/sbin", "/srv", "/sys", "/tmp", "/usr", "/var",
})
# Git-Innenleben (``.git`` als Verzeichnis oder Datei, in jeder Tiefe): nie
# lesbar – ``.git/config`` kann Zugangsdaten in Remote-URLs enthalten. ``*``
# passt auch auf ``/``, ``*/.git`` deckt also jede Tiefe ab, ``a.git`` oder
# ``.github`` aber nicht.
GIT_PATTERNS = (f"{MOUNT}/.git", f"{MOUNT}/.git/*", f"{MOUNT}/*/.git", f"{MOUNT}/*/.git/*")

# Doppelpunkt trennt in Compose Quelle/Ziel/Optionen, "$" wäre eine
# Compose-Variable; Steuerzeichen und "\\" haben in einem Pfad nichts verloren.
_FORBIDDEN = re.compile(r"[:$\\\x00-\x1f\x7f]")


def normalize(value: str | None) -> str | None:
    """Host-Pfad prüfen → kanonischer Pfad oder ``None`` (Feature aus).

    Erlaubt: absoluter POSIX-Pfad ohne ``.``/``..``-Segmente, nicht ``/`` und
    kein Systemverzeichnis wie ``/etc``; ein ``/`` am Ende fällt weg. Wirft
    ``ValueError`` – lieber nicht starten als etwas anderes freigeben.
    """
    raw = (value or "").strip()
    if not raw:
        return None
    if not raw.isascii():
        raise ValueError(f"{ENV}: nur ASCII-Pfade: {raw!r}")
    bad = _FORBIDDEN.search(raw)
    if bad:
        raise ValueError(f"{ENV}: Zeichen {bad.group()!r} nicht erlaubt: {raw!r}")
    if not raw.startswith("/"):
        raise ValueError(f"{ENV}: muss ein absoluter Pfad sein (/…): {raw!r}")
    path = raw.rstrip("/") or "/"
    segments = path.split("/")[1:]
    if path != "/" and any(s in ("", ".", "..") for s in segments):
        raise ValueError(f"{ENV}: Pfad ohne '.', '..' und leere Segmente: {raw!r}")
    if path in SYSTEM_DIRS:
        raise ValueError(f"{ENV}: {path} ist die Wurzel oder ein Systemverzeichnis – eigenes Unterverzeichnis nehmen: {raw!r}")
    return path


def session_rules(enabled: bool) -> list[dict]:
    """Session-Regeln, die *nach* den Verboten kommen (letzte gewinnt).

    Gibt ``external_directory`` und ``read`` für :data:`MOUNT` und alles
    darunter frei (``glob``/``grep`` prüfen das Muster, sind schon erlaubt;
    ihren Suchpfad deckt ``external_directory`` ab). Danach verboten:
    ``.git`` in jeder Tiefe (:data:`GIT_PATTERNS`, für ``external_directory``
    und ``read``; ripgrep lässt ``.git`` bei glob/grep ohnehin aus) und
    ``edit`` unter :data:`MOUNT`.
    """
    if not enabled:
        return []
    inside = f"{MOUNT}/*"
    return [
        {"action": "external_directory", "resource": inside, "effect": "allow"},
        {"action": "read", "resource": MOUNT, "effect": "allow"},
        {"action": "read", "resource": inside, "effect": "allow"},
        *({"action": a, "resource": p, "effect": "deny"}
          for a in ("external_directory", "read") for p in GIT_PATTERNS),
        {"action": "edit", "resource": MOUNT, "effect": "deny"},
        {"action": "edit", "resource": inside, "effect": "deny"},
    ]
