"""Unit-Tests für captain.opencode mit aufgezeichneten SSE-Beispielen.

Die Dateien unter ``tests/data/`` stammen aus echten Läufen gegen
``@opencode/cli@2.0.20`` mit ``ollama/gemma4:e4b`` (nur Zeilen der
betroffenen Session, Reasoning-Deltas gekürzt). Ein Fake-Server auf Basis von
``httpx.MockTransport`` spielt sie ab, sobald der Prompt eintrifft.
"""

from __future__ import annotations

import json
import queue
import threading
import time
from pathlib import Path

import httpx
import pytest

from captain.opencode import (
    Interrupted,
    OpencodeClient,
    OpencodeError,
    SessionGone,
    join_blocks,
    parse_sse_line,
    session_permissions,
    split_model,
    tool_status,
)

DATA = Path(__file__).parent / "data"
CONNECTED = 'data: {"id":"evt_0","type":"server.connected","data":{}}'


def load_sse(name: str) -> list[str]:
    return (DATA / f"{name}.sse").read_text(encoding="utf-8").splitlines()


def load_messages(name: str) -> dict:
    return json.loads((DATA / f"{name}.messages.json").read_text(encoding="utf-8"))


def events(lines: list[str]) -> list[dict]:
    return [e for e in map(parse_sse_line, lines) if e]


def ids_of(lines: list[str]) -> tuple[str, str]:
    """(sessionID, inboxID) aus einer Aufzeichnung."""
    for e in events(lines):
        if e["type"] == "session.inbox.delivered":
            return e["data"]["sessionID"], e["data"]["inboxID"]
    raise AssertionError("Aufzeichnung ohne inbox.delivered")


def ev(etype: str, sid: str, **data) -> str:
    return "data: " + json.dumps({"type": etype, "data": {"sessionID": sid, **data}})


def final_text(messages: dict, inbox_id: str) -> str:
    newer = []
    for m in messages["data"]:
        if m["id"] == inbox_id:
            break
        newer.append(m)
    return join_blocks(
        p["text"]
        for m in reversed(newer)
        if m["type"] == "assistant"
        for p in m["content"]
        if p["type"] == "text"
    )


# ---------------------------------------------------------------------------
# Fake-Server
# ---------------------------------------------------------------------------


class FakeOpencode:
    """Minimaler opencode-v2-Server: REST-Routen + steuerbarer SSE-Stream."""

    def __init__(self):
        self.requests: list[tuple[str, str, dict | None]] = []
        self.sse: queue.Queue = queue.Queue()  # Zeilen; None beendet die Verbindung
        self.connections = 0
        self.closed = threading.Event()
        self.scripts: dict[str, list[str]] = {}  # sessionID → Zeilen nach Prompt
        self.inbox: dict[str, str] = {}  # sessionID → inboxID der Antwort
        self.messages: dict[str, dict] = {}  # sessionID → /message-Antwort
        self.sessions: dict[str, dict] = {}
        self.prompt_status = 200
        self.prompt_body: dict | None = None
        self.lock = threading.Lock()

    # Steuerung -----------------------------------------------------------

    def add_session(self, sid: str, script=None, inbox="msg_inbox", messages=None, **info):
        self.sessions[sid] = {"id": sid, **info}
        self.scripts[sid] = list(script or [])
        self.inbox[sid] = inbox
        if messages is not None:
            self.messages[sid] = messages

    def calls(self, method: str, path: str) -> list[dict | None]:
        return [b for m, p, b in self.requests if m == method and p == path]

    # Transport -----------------------------------------------------------

    def _stream(self):
        yield (CONNECTED + "\n\n").encode()
        while not self.closed.is_set():
            try:
                line = self.sse.get(timeout=0.05)
            except queue.Empty:
                continue
            if line is None:
                return
            yield (line + "\n").encode()

    def handler(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        body = json.loads(request.content) if request.content else None
        with self.lock:
            self.requests.append((request.method, path, body))
        parts = path.strip("/").split("/")  # api, session, <id>, ...

        if path == "/api/event":
            self.connections += 1
            return httpx.Response(
                200, headers={"content-type": "text/event-stream"}, content=self._stream()
            )
        if path == "/api/session" and request.method == "POST":
            return httpx.Response(200, json={"data": {"id": "ses_new", **(body or {})}})
        if parts[:3] == ["api", "experimental", "session"] and request.method == "PUT":
            if parts[3] not in self.sessions:
                return httpx.Response(404, json={"_tag": "SessionNotFoundError"})
            return httpx.Response(204)
        if path == "/api/model":
            return httpx.Response(200, json={"data": MODELS})
        if parts[:2] == ["api", "session"] and len(parts) >= 3:
            sid = parts[2]
            if sid not in self.sessions:
                return httpx.Response(
                    404, json={"_tag": "SessionNotFoundError", "sessionID": sid, "message": "x"}
                )
            action = parts[3] if len(parts) > 3 else ""
            if action == "":
                return httpx.Response(200, json={"data": self.sessions[sid]})
            if action == "prompt":
                self.prompt_body = body
                if self.prompt_status != 200:
                    return httpx.Response(self.prompt_status, text="ENOENT: realPath /x")
                for line in self.scripts.get(sid, []):
                    self.sse.put(line)
                return httpx.Response(
                    200, json={"data": {"id": self.inbox[sid], "type": "user"}}
                )
            if action == "model":
                self.sessions[sid]["model"] = body["model"]
                return httpx.Response(204)
            if action == "interrupt":
                return httpx.Response(200, json={"data": {"interrupted": True}})
            if action == "message":
                return httpx.Response(200, json=self.messages.get(sid, {"data": []}))
        return httpx.Response(500, text=f"unbekannt: {request.method} {path}")


MODELS = [
    {
        "id": "gemma4:e4b",
        "modelID": "gemma4:e4b",
        "providerID": "ollama",
        "name": "gemma4:e4b",
        "variants": [],
        "enabled": True,
    },
    {
        "id": "space-bunny-free",
        "modelID": "space-bunny-free",
        "providerID": "opencode",
        "name": "Space Bunny Free",
        "variants": [{"id": "low"}, {"id": "high"}],
        "enabled": True,
    },
    {"id": "off", "modelID": "off", "providerID": "x", "name": "Off", "enabled": False},
]


@pytest.fixture
def server():
    fake = FakeOpencode()
    yield fake
    fake.closed.set()


@pytest.fixture
def client(server):
    c = OpencodeClient(
        "http://oc:4096",
        "pw",
        transport=httpx.MockTransport(server.handler),
        reconcile_interval=0.3,
    )
    c._events._backoff = (0.01, 0.05)
    yield c
    server.closed.set()
    c.close()


# ---------------------------------------------------------------------------
# Hilfsfunktionen
# ---------------------------------------------------------------------------


def test_parse_sse_line():
    assert parse_sse_line(": heartbeat") is None
    assert parse_sse_line("") is None
    assert parse_sse_line("data: kein json") is None
    assert parse_sse_line('data: {"type":"x","data":{}}') == {"type": "x", "data": {}}


def test_split_model():
    assert split_model("ollama/gemma4:e4b") == {"providerID": "ollama", "id": "gemma4:e4b"}
    assert split_model("openrouter/anthropic/claude", "high") == {
        "providerID": "openrouter",
        "id": "anthropic/claude",
        "variant": "high",
    }
    with pytest.raises(ValueError):
        split_model("gemma4")


def test_tool_status():
    assert tool_status("shell").startswith("⚙️")
    assert tool_status("Bash").startswith("⚙️")
    assert tool_status("write").startswith("✏️")
    assert tool_status("websearch").startswith("🔍")
    assert tool_status("irgendwas") == "🔧 Ich arbeite…"


def test_join_blocks():
    assert join_blocks(["Ich sehe nach.", "", " Ergebnis: hallo "]) == (
        "Ich sehe nach.\n\nErgebnis: hallo"
    )


# ---------------------------------------------------------------------------
# REST
# ---------------------------------------------------------------------------


def test_create_session(client, server):
    assert client.create_session("/tmp/captain/x", title="DM x") == "ses_new"
    assert server.calls("POST", "/api/session") == [
        {"location": {"directory": "/tmp/captain/x"}, "title": "DM x"}
    ]


def test_create_session_with_id_and_permissions(client, server):
    rules = session_permissions("/tmp/captain/ses_abc/")
    assert client.create_session(
        "/tmp/captain/ses_abc", permissions=rules, session_id="ses_abc"
    ) == "ses_abc"
    (body,) = server.calls("POST", "/api/session")
    assert body == {
        "location": {"directory": "/tmp/captain/ses_abc"}, "permissions": rules, "id": "ses_abc",
    }


def test_session_permissions_only_files_in_directory():
    rules = session_permissions("/tmp/captain/ses_abc")
    allowed = {r["action"] for r in rules if r["effect"] == "allow"}
    assert allowed == {"read", "edit", "glob", "grep"}
    # letzte passende Regel gewinnt: absolute Pfade nur im eigenen Verzeichnis
    def effect(action, resource):
        import fnmatch
        hit = [r for r in rules if fnmatch.fnmatchcase(action, r["action"])
               and fnmatch.fnmatchcase(resource, r["resource"])]
        return hit[-1]["effect"] if hit else None
    assert effect("read", "notiz.txt") == "allow"
    assert effect("edit", "sub/notiz.txt") == "allow"
    assert effect("read", "/etc/passwd") == "deny"
    assert effect("read", "/tmp/captain/ses_other/x") == "deny"
    assert effect("edit", "/tmp/captain/ses_abc/x") == "allow"
    assert effect("external_directory", "/tmp/*") == "deny"
    # zum Schluss verboten – überstimmt Freigaben aus der Admin-Config
    for action in ("shell", "webfetch", "websearch", "subagent", "skill", "question",
                   "execute", "opencode_list_mcp_resources", "opencode_read_mcp_resource"):
        assert effect(action, "*") == "deny", action
    assert effect("testmcp_wuerfeln", "*") is None  # MCP-Freigaben bleiben der Admin-Config

    # Projekt-Config/Plugins/Anweisungen weder schreiben noch lesen
    for path in (
        ".opencode/opencode.jsonc", ".opencode/plugins/x.ts", ".opencode", "opencode.json",
        "opencode.jsonc", "AGENTS.md", "CLAUDE.md", ".claude/settings.json", ".agents/a.md",
        "sub/AGENTS.md", "/tmp/captain/ses_abc/.opencode/opencode.json",
        "/tmp/captain/ses_abc/AGENTS.md",
    ):
        assert effect("edit", path) == "deny", path
        assert effect("read", path) == "deny", path
    assert effect("edit", "agents.txt") == "allow"
    assert effect("edit", "notizen/opencode-idee.md") == "allow"


def test_set_instructions(client, server):
    server.add_session("ses_a")
    client.set_instructions("ses_a", {"captain-persona": "P", "captain-umgebung": "U"})
    path = "/api/experimental/session/ses_a/instructions/entries/"
    assert server.calls("PUT", path + "captain-persona") == [{"value": "P"}]
    assert server.calls("PUT", path + "captain-umgebung") == [{"value": "U"}]


def test_session_exists_and_abort(client, server):
    server.add_session("ses_a")
    assert client.session_exists("ses_a")
    assert not client.session_exists("ses_weg")
    client.abort("ses_weg")  # unbekannt → kein Fehler
    client.abort("ses_a")
    assert server.calls("POST", "/api/session/ses_a/interrupt") == [None]


def test_list_models(client):
    assert client.list_models() == [
        {"id": "ollama/gemma4:e4b", "name": "gemma4:e4b", "variants": []},
        {"id": "opencode/space-bunny-free", "name": "Space Bunny Free", "variants": ["low", "high"]},
    ]


def test_basic_auth(server):
    seen = []

    def handler(request):
        seen.append(request.headers.get("authorization"))
        return server.handler(request)

    with OpencodeClient("http://oc", "geheim", transport=httpx.MockTransport(handler)) as c:
        c.session_exists("ses_x")
    assert seen == ["Basic " + __import__("base64").b64encode(b"opencode:geheim").decode()]


# ---------------------------------------------------------------------------
# Prompt mit aufgezeichneten Streams
# ---------------------------------------------------------------------------


def test_prompt_text_tool_text(client, server):
    lines = load_sse("text_tool_text")
    sid, inbox = ids_of(lines)
    server.add_session(sid, lines, inbox)
    deltas, status = [], []

    result = client.prompt(
        sid, "egal", directory="/tmp", on_delta=deltas.append, on_status=status.append
    )

    expected = final_text(load_messages("text_tool_text"), inbox)
    assert result == expected
    assert len(deltas) > 2
    assert deltas[-1] == expected
    for a, b in zip(deltas, deltas[1:]):
        assert b.startswith(a) or len(b) >= len(a)  # wächst nur
    assert status and status[0].startswith("⚙️")
    # Denkspur landet nicht im Text
    reasoning = [e for e in events(lines) if e["type"] == "session.reasoning.ended"]
    for r in reasoning:
        if r["data"].get("text"):
            assert r["data"]["text"] not in result


def test_prompt_simple(client, server):
    lines = load_sse("simple")
    sid, inbox = ids_of(lines)
    server.add_session(sid, lines, inbox)
    assert client.prompt(sid, "x", directory="/tmp") == final_text(
        load_messages("simple"), inbox
    )


def test_prompt_interrupted(client, server):
    lines = load_sse("interrupted")
    sid, inbox = ids_of(lines)
    server.add_session(sid, lines, inbox)
    with pytest.raises(Interrupted):
        client.prompt(sid, "x", directory="/tmp")


def test_multiple_blocks_are_joined(client, server):
    sid = "ses_multi"
    m1, m2 = "msg_a1", "msg_a2"
    script = [
        ev("session.inbox.delivered", sid, inboxID="msg_in"),
        ev("session.execution.started", sid),
        ev("session.text.delta", sid, assistantMessageID=m1, ordinal=0, delta="Ich sehe "),
        ev("session.text.delta", sid, assistantMessageID=m1, ordinal=0, delta="nach."),
        ev("session.text.ended", sid, assistantMessageID=m1, ordinal=0, text="Ich sehe nach."),
        ev("session.tool.input.started", sid, assistantMessageID=m1, id="c1", name="shell"),
        ev("session.tool.success", sid, assistantMessageID=m1, id="c1"),
        ev("session.text.delta", sid, assistantMessageID=m2, ordinal=0, delta="Ausgabe: "),
        ev("session.text.delta", sid, assistantMessageID=m2, ordinal=0, delta="hallo"),
        ev("session.execution.succeeded", sid),
    ]
    server.add_session(sid, script, "msg_in")
    deltas, status = [], []
    result = client.prompt(
        sid, "x", directory="/tmp", on_delta=deltas.append, on_status=status.append
    )
    assert result == "Ich sehe nach.\n\nAusgabe: hallo"
    assert deltas == [
        "Ich sehe",
        "Ich sehe nach.",
        "Ich sehe nach.\n\nAusgabe:",
        "Ich sehe nach.\n\nAusgabe: hallo",
    ]
    assert status == ["⚙️ Ich führe Befehle aus…"]


def test_foreign_sessions_and_old_execution_ignored(client, server):
    sid = "ses_mine"
    script = [
        # Ende einer älteren Ausführung derselben Session vor unserer Zustellung
        ev("session.text.delta", sid, assistantMessageID="msg_old", ordinal=0, delta="alt"),
        ev("session.execution.succeeded", sid),
        ev("session.text.delta", "ses_other", assistantMessageID="m", ordinal=0, delta="fremd"),
        ev("session.inbox.delivered", sid, inboxID="msg_in"),
        ev("session.execution.succeeded", "ses_other"),
        ev("session.text.delta", sid, assistantMessageID="msg_new", ordinal=0, delta="neu"),
        ev("session.execution.succeeded", sid),
    ]
    server.add_session(sid, script, "msg_in")
    assert client.prompt(sid, "x", directory="/tmp") == "neu"


def test_failed_execution_raises(client, server):
    sid = "ses_fail"
    script = [
        ev("session.inbox.delivered", sid, inboxID="msg_in"),
        ev("session.error", sid, error={"message": "Modell nicht erreichbar"}),
        ev("session.execution.failed", sid),
    ]
    server.add_session(sid, script, "msg_in")
    with pytest.raises(OpencodeError, match="Modell nicht erreichbar"):
        client.prompt(sid, "x", directory="/tmp")


def test_prompt_unknown_session(client):
    with pytest.raises(SessionGone):
        client.prompt("ses_weg", "x", directory="/tmp")


def test_prompt_missing_directory(client, server):
    server.add_session("ses_a")
    server.prompt_status = 500
    with pytest.raises(OpencodeError, match="Arbeitsverzeichnis"):
        client.prompt("ses_a", "x", directory="/tmp/captain/fehlt")


def test_prompt_sets_model_only_when_changed(client, server):
    sid = "ses_m"
    done = [
        ev("session.inbox.delivered", sid, inboxID="msg_in"),
        ev("session.execution.succeeded", sid),
    ]
    server.add_session(sid, done, "msg_in")
    client.prompt(sid, "x", directory="/tmp", model="opencode/space-bunny-free", variant="high")
    assert server.calls("POST", f"/api/session/{sid}/model") == [
        {"model": {"providerID": "opencode", "id": "space-bunny-free", "variant": "high"}}
    ]
    server.scripts[sid] = done
    client.prompt(sid, "x", directory="/tmp", model="opencode/space-bunny-free", variant="high")
    assert len(server.calls("POST", f"/api/session/{sid}/model")) == 1


def test_prompt_files(client, server):
    sid = "ses_f"
    server.add_session(
        sid,
        [ev("session.inbox.delivered", sid, inboxID="i"), ev("session.execution.succeeded", sid)],
        "i",
    )
    client.prompt(sid, "x", directory="/tmp/captain/ses_a", files=["bild.png", "/tmp/a.txt"])
    assert server.prompt_body["files"] == [
        {"uri": "file:///tmp/captain/ses_a/bild.png", "name": "bild.png"},
        {"uri": "file:///tmp/a.txt", "name": "a.txt"},
    ]


# ---------------------------------------------------------------------------
# Robustheit
# ---------------------------------------------------------------------------


def test_stream_break_falls_back_to_messages(client, server):
    """Stream reißt mitten in der Antwort → Reconnect → Text aus /message."""
    lines = load_sse("text_tool_text")
    sid, inbox = ids_of(lines)
    first_delta = next(i for i, l in enumerate(lines) if "session.text.delta" in l)
    # nur bis zum ersten Text-Delta, dann Verbindungsabbruch
    server.add_session(
        sid, lines[: first_delta + 1] + [None], inbox, messages=load_messages("text_tool_text")
    )
    result = client.prompt(sid, "x", directory="/tmp", idle_timeout=10)
    assert result == final_text(load_messages("text_tool_text"), inbox)
    assert server.connections >= 2


def test_silent_stream_reconciles_via_rest(client, server):
    """Keine Events (z. B. Stream hängt) → periodischer REST-Abgleich."""
    msgs = load_messages("simple")
    sid, inbox = ids_of(load_sse("simple"))
    server.add_session(sid, [], inbox, messages=msgs)
    t0 = time.monotonic()
    assert client.prompt(sid, "x", directory="/tmp", idle_timeout=10) == final_text(msgs, inbox)
    assert time.monotonic() - t0 < 5


def test_idle_timeout_aborts(client, server):
    sid = "ses_idle"
    server.add_session(sid, [ev("session.inbox.delivered", sid, inboxID="msg_in")], "msg_in")
    server.messages[sid] = {"data": [{"id": "msg_in", "type": "user", "text": "x"}]}
    with pytest.raises(OpencodeError, match="Keine Rückmeldung"):
        client.prompt(sid, "x", directory="/tmp", idle_timeout=0.5)
    assert server.calls("POST", f"/api/session/{sid}/interrupt") == [None]


def test_parallel_prompts(client, server):
    """Zwei Sessions gleichzeitig über einen Stream; Events verschränkt."""
    a, b = "ses_a", "ses_b"
    gate = threading.Event()
    server.add_session(a, [], "in_a")
    server.add_session(b, [], "in_b")
    results: dict[str, str] = {}
    deltas: dict[str, list[str]] = {a: [], b: []}

    def run(sid):
        results[sid] = client.prompt(
            sid, "x", directory="/tmp", on_delta=deltas[sid].append, idle_timeout=10
        )

    threads = [threading.Thread(target=run, args=(s,)) for s in (a, b)]
    for t in threads:
        t.start()
    # warten, bis beide Prompts abgeschickt sind
    deadline = time.monotonic() + 5
    while len([r for r in server.requests if r[1].endswith("/prompt")]) < 2:
        assert time.monotonic() < deadline
        time.sleep(0.01)
    for line in [
        ev("session.inbox.delivered", a, inboxID="in_a"),
        ev("session.inbox.delivered", b, inboxID="in_b"),
        ev("session.text.delta", a, assistantMessageID="ma", ordinal=0, delta="A1"),
        ev("session.text.delta", b, assistantMessageID="mb", ordinal=0, delta="B1"),
        ev("session.text.delta", a, assistantMessageID="ma", ordinal=0, delta="A2"),
        ev("session.execution.succeeded", b),
        ev("session.text.delta", a, assistantMessageID="ma", ordinal=0, delta="A3"),
        ev("session.execution.succeeded", a),
    ]:
        server.sse.put(line)
    gate.set()
    for t in threads:
        t.join(10)
    assert results == {a: "A1A2A3", b: "B1"}
    assert deltas[a] == ["A1", "A1A2", "A1A2A3"]
    assert server.connections == 1  # ein gemeinsamer Stream


def test_callback_errors_do_not_break_prompt(client, server):
    sid = "ses_cb"
    server.add_session(
        sid,
        [
            ev("session.inbox.delivered", sid, inboxID="i"),
            ev("session.text.delta", sid, assistantMessageID="m", ordinal=0, delta="ok"),
            ev("session.execution.succeeded", sid),
        ],
        "i",
    )

    def boom(_):
        raise RuntimeError("kaputt")

    assert client.prompt(sid, "x", directory="/tmp", on_delta=boom) == "ok"


def test_rest_marks_delivered_when_event_missed(client, server):
    """inbox.delivered verpasst → Abgleich findet die User-Nachricht → Ende-Event zählt."""
    sid = "ses_missed"
    server.add_session(sid, [], "msg_in")
    # Nachricht zugestellt, Ausführung läuft noch (kein idle in der Historie)
    server.messages[sid] = {"data": [{"id": "msg_in", "type": "user", "text": "x"}]}
    result: dict = {}
    deltas: list[str] = []

    def run():
        result["text"] = client.prompt(
            sid, "x", directory="/tmp", on_delta=deltas.append, idle_timeout=5
        )

    t = threading.Thread(target=run)
    t.start()
    deadline = time.monotonic() + 5
    while not server.calls("GET", f"/api/session/{sid}/message"):
        assert time.monotonic() < deadline
        time.sleep(0.01)
    # Historie hat nie ein idle – ohne Fix liefe der Prompt in den Timeout.
    server.messages[sid] = {
        "data": [
            {"id": "msg_a", "type": "assistant", "content": [{"type": "text", "text": "hi"}]},
            {"id": "msg_in", "type": "user", "text": "x"},
        ]
    }
    server.sse.put(ev("session.text.delta", sid, assistantMessageID="msg_a", ordinal=0, delta="hi"))
    server.sse.put(ev("session.execution.succeeded", sid))
    t.join(5)
    assert not t.is_alive()
    assert result["text"] == "hi"
    assert deltas == ["hi"]


def test_state_after_stops_at_first_idle(client, server):
    """Folge-Ausführungen nach dem ersten idle gehören nicht zur Antwort."""
    sid = "ses_two"
    msgs = {
        "data": [
            {"id": "i2", "type": "idle", "outcome": "succeeded"},
            {"id": "a2", "type": "assistant", "content": [{"type": "text", "text": "zweite"}]},
            {"id": "u2", "type": "user", "text": "y"},
            {"id": "i1", "type": "idle", "outcome": "succeeded"},
            {"id": "a1", "type": "assistant", "content": [{"type": "text", "text": "erste"}]},
            {"id": "msg_in", "type": "user", "text": "x"},
        ]
    }
    server.add_session(sid, [], "msg_in", messages=msgs)
    assert client._state_after(sid, "msg_in") == (True, "succeeded", "erste", "")
    assert client._state_after(sid, "u2") == (True, "succeeded", "zweite", "")
    assert client._state_after(sid, "fehlt") == (False, None, "", "")
    assert client.prompt(sid, "x", directory="/tmp", idle_timeout=5) == "erste"


# ---------------------------------------------------------------------------
# MCP-Verbindung pro Location (ensure_mcp)
# ---------------------------------------------------------------------------


def mcp_client(answers: list):
    """Client, dessen ``GET /api/mcp`` nacheinander ``answers`` liefert (letzte bleibt)."""
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/mcp"
        calls.append(request.url.params.get("location[directory]"))
        data = answers[min(len(calls), len(answers)) - 1]
        if isinstance(data, int):
            return httpx.Response(data, text="kaputt")
        return httpx.Response(200, json={"location": {}, "data": data})

    return OpencodeClient("http://oc", "pw", transport=httpx.MockTransport(handler)), calls


def srv(status: str, name: str = "testmcp") -> dict:
    return {"name": name, "status": {"status": status}}


def test_ensure_mcp_waits_for_pending_servers(monkeypatch):
    monkeypatch.setattr(time, "sleep", lambda _s: None)
    c, calls = mcp_client([[], [srv("pending")], [srv("connected")]])
    with c:
        assert c.ensure_mcp("/tmp/captain/ses_a") == [srv("connected")]
    assert calls == ["/tmp/captain/ses_a"] * 3


def test_ensure_mcp_without_servers_waits_only_once():
    c, calls = mcp_client([[]])
    with c:
        t0 = time.monotonic()
        assert c.ensure_mcp("/tmp/captain/ses_a", grace=0.3) == []
        assert time.monotonic() - t0 >= 0.3  # frische Location: kurz auf Server warten
        n = len(calls)
        t0 = time.monotonic()
        assert c.ensure_mcp("/tmp/captain/ses_b", grace=0.3) == []
        assert time.monotonic() - t0 < 0.2  # keine Server konfiguriert → nicht mehr warten
        assert len(calls) == n + 1


def test_ensure_mcp_connected_needs_no_wait():
    c, calls = mcp_client([[srv("connected"), srv("failed", "kaputt")]])
    with c:
        t0 = time.monotonic()
        assert len(c.ensure_mcp("/tmp/captain/ses_a", settle=0.3)) == 2
        # neues Verzeichnis: kurz warten, bis opencode die Tools registriert hat
        assert 0.3 <= time.monotonic() - t0 < 0.6 and len(calls) == 1
        t0 = time.monotonic()
        assert len(c.ensure_mcp("/tmp/captain/ses_a", settle=0.3)) == 2
        assert time.monotonic() - t0 < 0.2 and len(calls) == 2  # bekannt: sofort


def test_ensure_mcp_timeout_and_errors(monkeypatch):
    c, _ = mcp_client([[srv("pending")]])
    with c:
        t0 = time.monotonic()
        assert c.ensure_mcp("/tmp/captain/ses_a", timeout=0.3, settle=0) == [srv("pending")]
        assert time.monotonic() - t0 < 1.5
    c, _ = mcp_client([500])
    with c:
        assert c.ensure_mcp("/tmp/captain/ses_a") == []  # Fehler → kein Abbruch des Prompts


def test_ensure_mcp_relearns_after_opencode_restart():
    c, calls = mcp_client([[]])
    with c:
        c.ensure_mcp("/tmp/captain/ses_a", grace=0.2)  # lernt: keine Server
        c._events.connects = 1
        t0 = time.monotonic()
        c.ensure_mcp("/tmp/captain/ses_b", grace=0.2)
        assert time.monotonic() - t0 < 0.15  # erste Verbindung: kein Neustart
        c._events.connects = 2  # Event-Stream neu verbunden → opencode neu gestartet
        t0 = time.monotonic()
        c.ensure_mcp("/tmp/captain/ses_c", grace=0.2)
        assert time.monotonic() - t0 >= 0.2  # neu lernen
        t0 = time.monotonic()
        c.ensure_mcp("/tmp/captain/ses_d", grace=0.2)
        assert time.monotonic() - t0 < 0.15
