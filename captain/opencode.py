"""opencode-Client für den Server-Modus (``opencode serve``, API v2).

Getestet gegen ``@opencode/cli@2.0.20``. REST über httpx, Antworten kommen
in ``{"data": ...}`` gekapselt. Live-Fortschritt kommt über den Event-Stream
``GET /api/event`` (SSE, nur ``data:``-Zeilen, alle Sessions gemischt).

Architektur für parallele Prompts
---------------------------------
Ein Client hält **genau einen** SSE-Reader-Thread (:class:`_EventHub`), der
jedes Event anhand von ``data.sessionID`` an die Warteschlangen der gerade
laufenden ``prompt``-Aufrufe verteilt. Gründe gegen einen Stream pro Aufruf:

* opencode schickt ohnehin *alle* Events an jeden Abonnenten – n Streams
  hießen n-fach Parsen und n offene Verbindungen für dieselben Daten.
* Die Registrierung ist billig und passiert synchron vor dem Absenden des
  Prompts; der Hub ist dann bereits verbunden, es geht also nichts verloren.
* Reconnect-Logik existiert nur einmal. Nach einem Abriss bekommt jeder
  Abonnent ein Markierungs-Event und gleicht seinen Stand per REST ab
  (``GET /api/session/{id}`` + ``/message``), weil opencode keine Events
  nachliefert.

Hinweis: Das Arbeitsverzeichnis einer Session ist ein Pfad *im
opencode-Container* (z. B. ``/tmp/captain/ses_…``). Es muss existieren, bevor
ein Prompt geschickt wird, sonst antwortet opencode mit HTTP 500. Der Client
legt es nicht an – das ist Sache des Aufrufers (der Bot teilt das Volume).
"""

from __future__ import annotations

import json
import logging
import posixpath
import queue
import threading
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field

import httpx

from .webfetch import session_rules as webfetch_rules

log = logging.getLogger(__name__)

USER = "opencode"  # Basic-Auth-Benutzer von opencode serve
MCP_WAIT = 10.0  # so lange höchstens auf MCP-Server einer Location warten (Sekunden)
MCP_GRACE = 3.0  # … und so lange, bis eine frische Location ihre Server überhaupt meldet
MCP_SETTLE = 0.5  # nach dem Verbinden, bis opencode die Tools registriert hat

_RECONNECTED = "_captain.reconnected"  # internes Markierungs-Event
_TERMINAL = {
    "session.execution.succeeded",
    "session.execution.failed",
    "session.execution.interrupted",
}

_TOOL_STATUS = {
    "task": "🤖 Ich starte Recherche-Agenten…",
    "agent": "🤖 Ich starte Recherche-Agenten…",
    "websearch": "🔍 Ich recherchiere im Web…",
    "web_search": "🔍 Ich recherchiere im Web…",
    "webfetch": "🌐 Ich lese eine Webseite…",
    "web_fetch": "🌐 Ich lese eine Webseite…",
    "bash": "⚙️ Ich führe Befehle aus…",
    "shell": "⚙️ Ich führe Befehle aus…",
    "read": "📄 Ich sehe mir Dateien an…",
    "write": "✏️ Ich erstelle Dateien…",
    "edit": "✏️ Ich bearbeite Dateien…",
    "patch": "✏️ Ich bearbeite Dateien…",
    "multiedit": "✏️ Ich bearbeite Dateien…",
    "notebookedit": "✏️ Ich bearbeite ein Notebook…",
    "glob": "📂 Ich durchsuche Dateien…",
    "list": "📂 Ich durchsuche Dateien…",
    "ls": "📂 Ich durchsuche Dateien…",
    "grep": "🔎 Ich durchsuche Inhalte…",
    "codesearch": "🔎 Ich durchsuche Inhalte…",
    "toolsearch": "🧰 Ich suche passende Werkzeuge…",
    "skill": "🧰 Ich lade eine Fähigkeit…",
    "todowrite": "📝 Ich plane die nächsten Schritte…",
    "todoread": "📝 Ich plane die nächsten Schritte…",
}


def tool_status(name: str) -> str:
    """Kurzer Statustext für einen Tool-Aufruf (Fallback: generischer Text)."""
    key = (name or "").lower()
    return _TOOL_STATUS.get(key, "🔧 Ich arbeite…")


class OpencodeError(Exception):
    """Aufruf an opencode fehlgeschlagen oder Ausführung mit Fehler beendet."""


class SessionGone(OpencodeError):
    """Session existiert nicht (mehr)."""


class Interrupted(OpencodeError):
    """Ausführung wurde abgebrochen (``abort`` oder serverseitig)."""


def split_model(model: str, variant: str | None = None) -> dict:
    """``"provider/model"`` → ``Model.Ref`` von v2 (Modell-ID darf „/“ enthalten)."""
    provider, sep, model_id = model.partition("/")
    if not sep or not provider or not model_id:
        raise ValueError(f"Modell muss 'provider/modell' sein: {model!r}")
    ref = {"providerID": provider, "id": model_id}
    if variant:
        ref["variant"] = variant
    return ref


def parse_sse_line(line: str) -> dict | None:
    """Eine SSE-Zeile → Event-Dict; ``None`` für Kommentare/Leerzeilen/Müll."""
    if not line.startswith("data:"):
        return None
    payload = line[5:].strip()
    if not payload:
        return None
    try:
        event = json.loads(payload)
    except json.JSONDecodeError:
        log.warning("SSE-Zeile ist kein JSON: %.200s", payload)
        return None
    return event if isinstance(event, dict) else None


def _session_of(event: dict) -> str | None:
    data = event.get("data")
    return data.get("sessionID") if isinstance(data, dict) else None


# ---------------------------------------------------------------------------
# Event-Hub: ein SSE-Reader, Verteiler pro Session-ID
# ---------------------------------------------------------------------------


class _EventHub:
    """Liest ``/api/event`` in einem Daemon-Thread und verteilt nach Session."""

    def __init__(
        self,
        url: str,
        auth: tuple[str, str] | None,
        *,
        read_timeout: float = 60.0,
        backoff: tuple[float, float] = (0.5, 10.0),
        connect_wait: float = 15.0,
        transport: httpx.BaseTransport | None = None,
    ):
        self._url = url
        self._auth = auth
        self._read_timeout = read_timeout  # Heartbeats kommen alle ~10 s
        self._backoff = backoff
        self._connect_wait = connect_wait
        self._transport = transport
        self._lock = threading.Lock()
        self._subs: dict[str, list[queue.Queue]] = {}
        self._connected = threading.Event()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._response: httpx.Response | None = None
        self.connects = 0  # Anzahl erfolgreicher Verbindungen (für Tests/Logs)

    def subscribe(self, session_id: str) -> queue.Queue:
        """Registriert eine Warteschlange und wartet, bis der Stream steht."""
        q: queue.Queue = queue.Queue()
        with self._lock:
            self._subs.setdefault(session_id, []).append(q)
            if self._thread is None or not self._thread.is_alive():
                self._stop.clear()
                self._thread = threading.Thread(
                    target=self._run, name="opencode-events", daemon=True
                )
                self._thread.start()
        if not self._connected.wait(self._connect_wait):
            # Kein Abbruch: prompt() gleicht notfalls per REST ab.
            log.warning("opencode-Event-Stream nicht verbunden – fahre ohne fort")
        return q

    def unsubscribe(self, session_id: str, q: queue.Queue) -> None:
        with self._lock:
            subs = self._subs.get(session_id)
            if subs and q in subs:
                subs.remove(q)
                if not subs:
                    del self._subs[session_id]

    def close(self) -> None:
        self._stop.set()
        resp = self._response
        if resp is not None:
            try:
                resp.close()
            except Exception:  # noqa: BLE001 – nur Aufräumen
                pass
        if self._thread is not None:
            self._thread.join(timeout=5)

    def _dispatch(self, event: dict) -> None:
        sid = _session_of(event)
        if not sid:
            return
        with self._lock:
            targets = list(self._subs.get(sid, ()))
        for q in targets:
            q.put(event)

    def _broadcast(self, event: dict) -> None:
        with self._lock:
            targets = [q for subs in self._subs.values() for q in subs]
        for q in targets:
            q.put(event)

    def _run(self) -> None:
        delay = self._backoff[0]
        timeout = httpx.Timeout(10.0, read=self._read_timeout)
        with httpx.Client(auth=self._auth, timeout=timeout, transport=self._transport) as http:
            while not self._stop.is_set():
                try:
                    with http.stream(
                        "GET", self._url, headers={"Accept": "text/event-stream"}
                    ) as resp:
                        if resp.status_code != 200:
                            raise OpencodeError(f"Event-Stream: HTTP {resp.status_code}")
                        self._response = resp
                        first = True
                        for line in resp.iter_lines():
                            if self._stop.is_set():
                                return
                            event = parse_sse_line(line)
                            if event is None:
                                continue
                            if first:
                                first = False
                                self.connects += 1
                                self._connected.set()
                                delay = self._backoff[0]
                                if self.connects > 1:
                                    log.info("opencode-Event-Stream wieder verbunden")
                                    self._broadcast({"type": _RECONNECTED, "data": {}})
                            self._dispatch(event)
                    if not self._stop.is_set():
                        log.info("opencode-Event-Stream beendet – verbinde neu")
                except Exception as e:  # noqa: BLE001 – Reader darf nie sterben
                    if self._stop.is_set():
                        return
                    log.warning("opencode-Event-Stream unterbrochen: %s", e)
                finally:
                    self._response = None
                    self._connected.clear()
                if self._stop.wait(delay):
                    return
                delay = min(delay * 2, self._backoff[1])


# ---------------------------------------------------------------------------
# Antwort-Zusammenbau
# ---------------------------------------------------------------------------


@dataclass
class _Answer:
    """Sammelt Textblöcke einer Ausführung in Reihenfolge ihres Auftretens."""

    blocks: dict[tuple[str, int], str] = field(default_factory=dict)

    def delta(self, key: tuple[str, int], text: str) -> None:
        self.blocks[key] = self.blocks.get(key, "") + text

    def set(self, key: tuple[str, int], text: str) -> None:
        self.blocks[key] = text

    def clear(self) -> None:
        self.blocks.clear()

    @property
    def text(self) -> str:
        return join_blocks(self.blocks.values())


def join_blocks(blocks) -> str:
    """Verbindet Textblöcke (Text → Tool → Text) mit Leerzeile."""
    return "\n\n".join(b.strip() for b in blocks if b and b.strip())


def _block_key(data: dict) -> tuple[str, int]:
    return (str(data.get("assistantMessageID") or ""), int(data.get("ordinal") or 0))


def _error_text(data: dict) -> str:
    err = data.get("error")
    if isinstance(err, dict):
        return str(err.get("message") or err.get("_tag") or err.get("type") or err)
    return str(err or data.get("message") or "unbekannter Fehler")


# ---------------------------------------------------------------------------
# Client
# ---------------------------------------------------------------------------


# Datei-Aktionen, die eine Session in ihrem Verzeichnis darf (v2.0.20):
# ``edit`` deckt die Tools edit, write und apply_patch ab. Einen eigenen
# list-Aktionsnamen gibt es in v2 nicht (Verzeichnisse listet glob/read).
FILE_ACTIONS = ("read", "edit", "glob", "grep")


# Namen, die opencode ab dem Session-Verzeichnis aufwärts als Projekt-Config,
# Plugins (``.opencode/plugins``, läuft als JS im Server!) oder Anweisungen lädt.
PROTECTED = (".opencode", ".claude", ".agents", "opencode.json*", "AGENTS.md", "CLAUDE.md")

# Aktionen, die eine Session nie bekommt – als letzte Regeln der Session, also
# auch dann verborgen, wenn die (editierbare) Admin-Config sie freigibt.
# ``execute`` ist Code Mode: ein Skript-Tool mit eigenem ``fetch`` (Netz!),
# das opencode nicht über Policies prüft. ``opencode_*``: MCP-Ressourcen.
DENIED_ACTIONS = (
    "shell", "webfetch", "websearch", "subagent", "skill", "question", "execute",
    "opencode_*", "external_directory",
)


def session_permissions(directory: str, webfetch: tuple[str, ...] | list[str] = ()) -> list[dict]:
    """Session-Regeln: nur Dateizugriff in ``directory`` (plus webfetch-Allowlist).

    opencode wertet Agent-Defaults, globale Config und dann diese Regeln aus;
    die letzte passende gewinnt. Global ist alles verboten, hier wird nur
    Dateizugriff freigegeben. Innerhalb des Session-Verzeichnisses prüft
    opencode ``read``/``edit`` mit *relativen* Pfaden (daher ``*``), Pfade
    außerhalb laufen zusätzlich über ``external_directory`` (verboten) und
    tauchen dann absolut auf (daher ``/*`` verboten außer ``directory``).
    ``glob``/``grep`` prüfen das Suchmuster, der Suchpfad fällt ebenfalls
    unter ``external_directory``. Zum Schluss werden :data:`DENIED_ACTIONS`
    verboten – das überstimmt auch Freigaben aus der Admin-Config (die vor
    den Session-Regeln ausgewertet wird); MCP-Tools (``<server>_<tool>``)
    bleiben der Admin-Config überlassen.

    ``webfetch``: kanonische Präfixe aus ``CAPTAIN_WEBFETCH_ALLOW``
    (:func:`captain.webfetch.parse`). Dann folgen auf ``webfetch * deny`` die
    Freigaben ``<präfix>`` und ``<präfix>/*`` und danach Verbote für
    ``..``-Segmente und Steuerzeichen – so überstimmt keine Regel der
    Admin-Config die Allowlist, in keine Richtung.
    """
    root = directory.rstrip("/")
    rules = [{"action": a, "resource": "*", "effect": "allow"} for a in FILE_ACTIONS]
    for action in ("read", "edit"):
        rules.append({"action": action, "resource": "/*", "effect": "deny"})
        rules.append({"action": action, "resource": f"{root}/*", "effect": "allow"})
    # Defense in depth: nichts anlegen/lesen, was opencode als Projekt-Config,
    # Plugin oder Anweisung laden könnte (abgeschaltet ist das ohnehin per
    # OPENCODE_DISABLE_PROJECT_CONFIG im Container). ``*/`` deckt den absoluten
    # Pfad und Unterverzeichnisse ab.
    for action in ("read", "edit"):
        for name in PROTECTED:
            for pattern in (name, f"{name}/*", f"*/{name}", f"*/{name}/*"):
                rules.append({"action": action, "resource": pattern, "effect": "deny"})
    rules += [{"action": a, "resource": "*", "effect": "deny"} for a in DENIED_ACTIONS]
    rules += webfetch_rules(webfetch)
    return rules


class OpencodeClient:
    """Synchroner Client für ``opencode serve`` (API v2).

    Thread-sicher: mehrere ``prompt``-Aufrufe für *verschiedene* Sessions
    dürfen parallel laufen. Pro Session serialisiert der Aufrufer.
    """

    def __init__(
        self,
        url: str,
        password: str | None = None,
        *,
        timeout: float = 30.0,
        transport: httpx.BaseTransport | None = None,
        event_transport: httpx.BaseTransport | None = None,
        sse_read_timeout: float = 60.0,
        reconcile_interval: float = 30.0,
    ):
        self.url = url.rstrip("/")
        auth = (USER, password) if password else None
        self._http = httpx.Client(
            base_url=self.url, auth=auth, timeout=timeout, transport=transport
        )
        self._events = _EventHub(
            self.url + "/api/event",
            auth,
            read_timeout=sse_read_timeout,
            transport=event_transport or transport,
        )
        # Solange ein Prompt läuft, spätestens so oft per REST nachsehen,
        # ob die Ausführung schon zu Ende ist (falls Events verloren gingen).
        self._reconcile_interval = reconcile_interval
        self._mcp_seen: bool | None = None  # zuletzt MCP-Server gemeldet? (None: noch nie geprüft)
        self._mcp_epoch = 0  # Verbindungszähler des Event-Streams bei der letzten Prüfung
        self._mcp_dirs: set[str] = set()  # Verzeichnisse, deren MCP-Tools schon bereitstehen

    # -- Lebenszyklus --------------------------------------------------------

    def close(self) -> None:
        self._events.close()
        self._http.close()

    def __enter__(self) -> OpencodeClient:
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    # -- REST-Grundlagen -----------------------------------------------------

    def _request(self, method: str, path: str, session_id: str | None = None, **kw):
        try:
            resp = self._http.request(method, path, **kw)
        except httpx.HTTPError as e:
            raise OpencodeError(f"{method} {path}: {e}") from e
        if resp.status_code == 404 and session_id:
            raise SessionGone(f"Session {session_id} existiert nicht")
        if resp.status_code >= 400:
            raise OpencodeError(f"{method} {path}: HTTP {resp.status_code} {resp.text[:300]}")
        if resp.status_code == 204 or not resp.content:
            return None
        try:
            body = resp.json()
        except ValueError as e:
            raise OpencodeError(f"{method} {path}: keine JSON-Antwort") from e
        return body.get("data") if isinstance(body, dict) and "data" in body else body

    # -- Sessions ------------------------------------------------------------

    def create_session(
        self,
        directory: str,
        title: str | None = None,
        permissions: list[dict] | None = None,
        *,
        session_id: str | None = None,
    ) -> str:
        """Legt eine Session im (Container-)Verzeichnis ``directory`` an.

        ``permissions``: Session-Regeln (``[{action, resource, effect}]``),
        werden hinter die globalen Regeln gehängt – die letzte passende Regel
        gewinnt, siehe :func:`session_permissions`. ``session_id``: eigene ID
        (muss mit ``ses`` beginnen), sonst vergibt opencode eine.
        """
        body: dict = {"location": {"directory": directory}}
        if title:
            body["title"] = title
        if permissions is not None:
            body["permissions"] = permissions
        if session_id:
            body["id"] = session_id
        data = self._request("POST", "/api/session", json=body)
        try:
            return data["id"]
        except (TypeError, KeyError):
            raise OpencodeError(f"Unerwartete Antwort beim Anlegen: {data!r}") from None

    def set_instructions(self, session_id: str, entries: Mapping[str, str]) -> None:
        """Hängt dauerhafte Systemanweisungen an die Session (experimentelle v2-API).

        opencode fügt jeden Eintrag bei jedem Schritt als ``<context key=…>``
        an den Systemprompt an (nach der globalen ``AGENTS.md``); sie überleben
        daher auch eine Kompaktierung.
        """
        for key, value in entries.items():
            self._request(
                "PUT",
                f"/api/experimental/session/{session_id}/instructions/entries/{key}",
                session_id,
                json={"value": value},
            )

    def session_exists(self, session_id: str) -> bool:
        try:
            self._request("GET", f"/api/session/{session_id}", session_id)
        except SessionGone:
            return False
        return True

    def abort(self, session_id: str) -> None:
        """Bricht eine laufende Ausführung ab; unbekannte Sessions werden ignoriert."""
        try:
            self._request("POST", f"/api/session/{session_id}/interrupt", session_id)
        except SessionGone:
            pass

    def list_models(self, directory: str | None = None) -> list[dict]:
        """Verfügbare Modelle als ``{id: "provider/modell", name, variants}``.

        Die Liste hängt in v2 vom Verzeichnis ab (Config-Kaskade); ohne
        ``directory`` nimmt opencode sein Startverzeichnis. Direkt nach dem
        Start kann sie noch leer sein (Provider-Discovery läuft asynchron).
        """
        params = {"location[directory]": directory} if directory else None
        data: list = []
        for attempt in range(3):  # Discovery läuft beim ersten Aufruf an
            data = self._request("GET", "/api/model", params=params) or []
            if data or attempt == 2:
                break
            time.sleep(1 + attempt)
        models = []
        for m in data:
            if m.get("enabled") is False:
                continue
            provider = m.get("providerID") or ""
            model_id = m.get("modelID") or m.get("id") or ""
            models.append(
                {
                    "id": f"{provider}/{model_id}" if provider else model_id,
                    "name": m.get("name") or model_id,
                    "variants": [v["id"] for v in m.get("variants") or [] if v.get("id")],
                }
            )
        return models

    def ensure_mcp(
        self,
        directory: str,
        *,
        timeout: float = MCP_WAIT,
        grace: float = MCP_GRACE,
        settle: float = MCP_SETTLE,
    ) -> list[dict]:
        """Wartet, bis die MCP-Server für ``directory`` verbunden sind.

        opencode 2.0.20 verbindet MCP-Server pro Location (Verzeichnis) erst
        beim ersten Zugriff und stellt die Tool-Liste eines Prompts zusammen,
        bevor die Verbindung steht – ohne diesen Schritt fehlen die MCP-Tools
        im ersten Prompt jeder neuen Session. ``GET /api/mcp`` stößt die
        Verbindung an; gewartet wird, solange ein Server ``pending`` ist
        (höchstens ``timeout``). Eine frische Location meldet anfangs gar
        keine Server – darauf wird höchstens ``grace`` Sekunden gewartet, und
        nur, solange zuletzt überhaupt Server gemeldet wurden (bzw. beim
        ersten Aufruf bzw. nach einem Neustart von opencode, erkannt am
        Wiederverbinden des Event-Streams). Ohne MCP-Server kostet das also
        einmal ``grace`` pro opencode-Start, danach nichts mehr. Musste gewartet werden oder ist
        das Verzeichnis neu, folgen noch ``settle`` Sekunden, bis opencode die
        Tools registriert hat.
        Fehler werden geloggt, nicht geworfen. Rückgabe: Server-Status.
        """
        start = time.monotonic()
        connects = self._events.connects
        if connects > 1 and connects != self._mcp_epoch:
            # Event-Stream neu verbunden → opencode wurde (vermutlich) neu
            # gestartet, evtl. mit geänderter MCP-Config: neu lernen.
            self._mcp_seen = None
            self._mcp_dirs.clear()
        self._mcp_epoch = connects
        wait_empty = grace if self._mcp_seen is not False else 0.0
        servers: list = []
        waited = False
        while True:
            try:
                servers = self._request(
                    "GET", "/api/mcp", params={"location[directory]": directory}
                ) or []
            except OpencodeError as e:
                log.debug("MCP-Status für %s nicht abrufbar: %s", directory, e)
                return []
            pending = [s for s in servers if (s.get("status") or {}).get("status") == "pending"]
            elapsed = time.monotonic() - start
            if servers and not pending:
                break
            if not servers and elapsed >= wait_empty:
                break
            if elapsed >= timeout:
                log.warning("MCP-Server noch nicht verbunden: %s", [s.get("name") for s in pending])
                break
            waited = True
            time.sleep(0.2)
        if servers and (waited or directory not in self._mcp_dirs):
            # opencode registriert die Tools erst kurz nach "connected"
            # (entprellt) – auch wenn schon das Anlegen der Session die
            # Verbindung angestoßen hat und der erste Abruf "connected" meldet.
            time.sleep(settle)
        if servers:
            if len(self._mcp_dirs) > 10000:
                self._mcp_dirs.clear()
            self._mcp_dirs.add(directory)
        self._mcp_seen = bool(servers)
        for server in servers:
            status = (server.get("status") or {})
            if status.get("status") not in ("connected", "pending"):
                log.warning("MCP-Server %s: %s %s", server.get("name"), status.get("status"),
                            status.get("error") or "")
        return servers

    def set_model(self, session_id: str, model: str, variant: str | None = None) -> None:
        """Setzt das Modell der Session (``provider/modell``, optional Variante)."""
        self._request(
            "POST",
            f"/api/session/{session_id}/model",
            session_id,
            json={"model": split_model(model, variant)},
        )

    def _session(self, session_id: str) -> dict:
        return self._request("GET", f"/api/session/{session_id}", session_id) or {}

    def _messages_since(
        self, session_id: str, user_msg_id: str, *, page: int = 50, max_pages: int = 10
    ) -> list[dict] | None:
        """Nachrichten *nach* ``user_msg_id`` in chronologischer Reihenfolge.

        ``None``, wenn die User-Nachricht (noch) nicht in der Historie liegt,
        z. B. weil sie in der Inbox auf Zustellung wartet.
        """
        newer: list[dict] = []
        cursor = None
        for _ in range(max_pages):
            params = {"limit": str(page), "order": "desc"}
            if cursor:
                params["cursor"] = cursor
            try:
                resp = self._http.get(f"/api/session/{session_id}/message", params=params)
            except httpx.HTTPError as e:
                raise OpencodeError(f"Nachrichten von {session_id}: {e}") from e
            if resp.status_code == 404:
                raise SessionGone(f"Session {session_id} existiert nicht")
            if resp.status_code >= 400:
                raise OpencodeError(f"Nachrichten von {session_id}: HTTP {resp.status_code}")
            body = resp.json()
            for msg in body.get("data") or []:
                if msg.get("id") == user_msg_id:
                    newer.reverse()
                    return newer
                newer.append(msg)
            cursor = (body.get("cursor") or {}).get("next")
            if not cursor or not body.get("data"):
                break
        return None

    def _state_after(
        self, session_id: str, user_msg_id: str
    ) -> tuple[bool, str | None, str, str]:
        """Stand der Ausführung nach ``user_msg_id`` laut Historie.

        Liefert ``(gefunden, outcome, text, fehler)``. ``gefunden`` sagt, ob
        die eigene User-Nachricht schon in der Historie steht (also zugestellt
        ist); ``outcome`` ist ``None``, solange danach keine ``idle``-Nachricht
        existiert. Ausgewertet wird nur bis zum *ersten* ``idle`` – spätere
        Nachrichten gehören zu Folge-Ausführungen.
        """
        msgs = self._messages_since(session_id, user_msg_id)
        if msgs is None:
            return False, None, "", ""
        texts: list[str] = []
        error = ""
        outcome = None
        for msg in msgs:
            if msg.get("type") == "assistant":
                texts += [
                    p.get("text", "") for p in msg.get("content") or [] if p.get("type") == "text"
                ]
                if msg.get("error"):
                    error = _error_text(msg)
            elif msg.get("type") == "idle":
                outcome = msg.get("outcome") or "succeeded"
                break
        return True, outcome, join_blocks(texts), error

    # -- Prompt --------------------------------------------------------------

    def prompt(
        self,
        session_id: str,
        text: str,
        *,
        directory: str,
        model: str | None = None,
        variant: str | None = None,
        files: list[str] | None = None,
        on_delta: Callable[[str], None] | None = None,
        on_status: Callable[[str], None] | None = None,
        idle_timeout: float = 600,
    ) -> str:
        """Schickt ``text`` an die Session und wartet auf das Ende der Ausführung.

        ``on_delta`` bekommt jeweils den **gesamten** bisherigen Antworttext
        (mehrere Textblöcke mit Leerzeile verbunden), ``on_status`` einen
        Statustext pro Tool-Aufruf. Rückgabe ist der finale Text.

        ``directory`` bestimmt in v2 nicht mehr den Ort (der ist an die
        Session gebunden), sondern dient zum Auflösen relativer ``files``.
        ``model``/``variant`` werden vorher per ``set_model`` gesetzt, falls
        sie vom aktuellen Modell der Session abweichen. Vorher wartet
        :meth:`ensure_mcp` auf die MCP-Server des Verzeichnisses.

        Wirft :class:`SessionGone`, :class:`Interrupted` oder
        :class:`OpencodeError` (Fehler-Event, HTTP-Fehler, ``idle_timeout``
        Sekunden ohne Event dieser Session).
        """
        self.ensure_mcp(directory)
        if model:
            current = self._session(session_id).get("model") or {}
            wanted = split_model(model, variant)
            if any(current.get(k) != v for k, v in wanted.items()):
                self.set_model(session_id, model, variant)

        body: dict = {"text": text}
        if files:
            body["files"] = [self._file_ref(f, directory) for f in files]

        q = self._events.subscribe(session_id)  # vor dem Absenden!
        try:
            try:
                data = self._request(
                    "POST", f"/api/session/{session_id}/prompt", session_id, json=body
                )
            except SessionGone:
                raise
            except OpencodeError as e:
                if "ENOENT" in str(e):
                    raise OpencodeError(
                        f"Arbeitsverzeichnis der Session fehlt im opencode-Container: {e}"
                    ) from e
                raise
            inbox_id = (data or {}).get("id") or ""
            return self._follow(session_id, q, inbox_id, on_delta, on_status, idle_timeout)
        finally:
            self._events.unsubscribe(session_id, q)

    @staticmethod
    def _file_ref(path: str, directory: str) -> dict:
        """Dateipfad (Container-Sicht) → ``PromptInput.FileAttachment``."""
        if "://" in path:
            return {"uri": path, "name": posixpath.basename(path)}
        full = path if path.startswith("/") else posixpath.join(directory, path)
        return {"uri": "file://" + full, "name": posixpath.basename(full)}

    def _follow(
        self,
        session_id: str,
        q: queue.Queue,
        inbox_id: str,
        on_delta,
        on_status,
        idle_timeout: float,
    ) -> str:
        answer = _Answer()
        delivered = not inbox_id  # ohne ID nicht auf Zustellung warten
        gap = False  # Stream riss während der Ausführung → Text per REST
        last_event = time.monotonic()
        last_check = last_event
        last_error = ""

        sent = [""]  # zuletzt an on_delta gemeldeter Text

        def emit(cb, value):
            if cb is None:
                return
            try:
                cb(value)
            except Exception:  # noqa: BLE001 – Callback-Fehler nicht durchreichen
                log.exception("Callback-Fehler in opencode.prompt")

        def emit_text():
            text = answer.text
            if delivered and text != sent[0]:
                sent[0] = text
                emit(on_delta, text)

        while True:
            now = time.monotonic()
            wait = min(
                idle_timeout - (now - last_event),
                self._reconcile_interval - (now - last_check),
            )
            try:
                event = q.get(timeout=max(wait, 0.01))
            except queue.Empty:
                event = None

            if event is None or event.get("type") == _RECONNECTED:
                # Stille oder Stream-Abriss: Stand per REST abgleichen.
                if event is not None:
                    gap = True
                last_check = time.monotonic()
                found, done = self._reconcile(session_id, inbox_id)
                if found and not delivered:
                    # Zustellungs-Event verpasst: die Nachricht steht schon in
                    # der Historie. Text notfalls per REST (gap).
                    delivered = True
                    gap = True
                    answer.clear()
                if done is not None:
                    outcome, text, error = done
                    return self._result(outcome, text or answer.text, error or last_error)
                if event is None and time.monotonic() - last_event >= idle_timeout:
                    self.abort(session_id)
                    raise OpencodeError(
                        f"Keine Rückmeldung von opencode seit {idle_timeout:.0f} s – abgebrochen"
                    )
                continue

            last_event = time.monotonic()
            etype = event.get("type", "")
            data = event.get("data") or {}

            if etype == "session.inbox.delivered":
                if data.get("inboxID") == inbox_id:
                    delivered = True
                    answer.clear()  # evtl. Text einer vorherigen Ausführung
            elif etype == "session.text.delta":
                answer.delta(_block_key(data), data.get("delta") or "")
                emit_text()
            elif etype == "session.text.ended":
                if isinstance(data.get("text"), str):
                    answer.set(_block_key(data), data["text"])
                    emit_text()
            elif etype == "session.tool.input.started":  # nur hier steht der Name
                if delivered and data.get("name"):
                    emit(on_status, tool_status(data["name"]))
            elif etype in ("session.error", "session.step.failed"):
                last_error = _error_text(data)
                log.warning("opencode meldet Fehler in %s: %s", session_id, last_error)
            elif etype == "session.deleted":
                raise SessionGone(f"Session {session_id} wurde gelöscht")
            elif etype in _TERMINAL:
                if not delivered:
                    # Ende einer älteren Ausführung; unsere steht noch aus.
                    answer.clear()
                    continue
                outcome = etype.rsplit(".", 1)[1]
                if data.get("error"):
                    last_error = _error_text(data)
                text = answer.text
                if (gap or not text or outcome == "failed") and inbox_id:
                    # Deltas verpasst oder Fehlerdetails gesucht: Historie lesen.
                    try:
                        _, _, rest_text, rest_error = self._state_after(session_id, inbox_id)
                        text = rest_text or text
                        last_error = last_error or rest_error
                    except OpencodeError as e:
                        log.warning("Antwort nicht nachladbar: %s", e)
                return self._result(outcome, text, last_error)

    def _reconcile(self, session_id: str, inbox_id: str):
        """``(zugestellt, (outcome, text, fehler) | None)`` laut REST.

        Das zweite Element ist gesetzt, falls die Ausführung vorbei ist.
        """
        if not inbox_id:
            return False, None
        try:
            found, outcome, text, error = self._state_after(session_id, inbox_id)
        except SessionGone:
            raise
        except OpencodeError as e:
            log.warning("Abgleich für %s fehlgeschlagen: %s", session_id, e)
            return False, None
        if outcome is None:
            return found, None
        log.info("Ausführung in %s per REST als beendet erkannt (%s)", session_id, outcome)
        return found, (outcome, text, error)

    @staticmethod
    def _result(outcome: str, text: str, error: str) -> str:
        if outcome == "failed":
            raise OpencodeError(f"opencode-Ausführung fehlgeschlagen: {error or 'ohne Details'}")
        if outcome == "interrupted":
            raise Interrupted("opencode-Ausführung abgebrochen")
        return text
