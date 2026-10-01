"""Unit-Tests für captain.bot mit Fake-Clients (ohne Netz)."""

from __future__ import annotations

import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import pytest

from captain.bot import CURSOR, QUEUED_EMOJI, Bot, display_name, parse_command
from captain.config import Config
from captain.mattermost import AttachmentTooLarge, Post
from captain.opencode import Interrupted, OpencodeError, SessionGone
from captain.sessions import SessionStore

USERS = {
    "u_alice": {"id": "u_alice", "username": "alice", "first_name": "Alice", "last_name": "Test"},
    "u_bob": {"id": "u_bob", "username": "bob", "nickname": "Bobby"},
    "u_robot": {"id": "u_robot", "username": "robot", "is_bot": True},
    "bot1": {"id": "bot1", "username": "captain", "is_bot": True},
}


class FakeMM:
    def __init__(self):
        self.me = {"id": "bot1", "username": "captain"}
        self.posts: dict[str, dict] = {}  # id → {channel_id, message, root_id}
        self.updates: list[tuple[str, str]] = []
        self.reactions: list[tuple[str, str, str]] = []  # (op, post, emoji)
        self.typings = 0
        self.lock = threading.Lock()
        self.roots: dict[str, list] = {}
        self.channel: list[dict] = []  # für get_channel_posts
        self.thread_calls = 0
        self.channels: list[dict] = []  # für my_channels (Nachholen)
        self.history: dict[str, list[dict]] = {}  # Kanal → Posts (für get_posts_after)
        self.after_calls: list[tuple[str, int]] = []
        self.props: dict[str, dict] = {}  # Post-ID → props eigener Posts

    def create_post(self, channel_id, message, root_id=None, file_ids=None, props=None):
        with self.lock:
            pid = f"b{len(self.posts) + 1}"
            self.posts[pid] = {"channel_id": channel_id, "message": message, "root_id": root_id}
            self.props[pid] = dict(props or {})
            return pid

    def update_post(self, post_id, message, props=None):
        with self.lock:
            if props is not None:  # nur Props setzen (Abschluss einer Antwort)
                self.props[post_id] = dict(props)
                if self.posts[post_id]["message"] == message:
                    return
            self.posts[post_id]["message"] = message
            self.updates.append((post_id, message))

    def typing(self, channel_id, parent_id=None):
        self.typings += 1

    def get_user(self, user_id):
        return USERS[user_id]

    def get_thread(self, root_id):
        self.thread_calls += 1
        return self.roots.get(root_id, [])

    def get_channel_posts(self, channel_id, per_page=200):
        return list(self.channel)

    def my_channels(self):
        return list(self.channels)

    def get_posts_after(self, channel_id, after, per_page=200):
        self.after_calls.append((channel_id, after))
        posts = [p for p in self.history.get(channel_id, []) if p["create_at"] > after]
        return sorted(posts, key=lambda p: p["create_at"])

    def download_file(self, file_id, dest_dir, max_bytes=None):
        self.max_bytes = max_bytes
        if file_id.startswith("big"):
            raise AttachmentTooLarge(f"{file_id}.pdf", 30 * 1024 * 1024, max_bytes)
        path = os.path.join(dest_dir, f"{file_id}.txt")
        with open(path, "w") as f:
            f.write("x")
        return path

    def add_reaction(self, post_id, emoji):
        self.reactions.append(("add", post_id, emoji))

    def remove_reaction(self, post_id, emoji):
        self.reactions.append(("remove", post_id, emoji))

    def close(self):
        pass

    def final_messages(self):
        with self.lock:
            return [p["message"] for p in self.posts.values()]


class FakeOC:
    """Prompt-Verhalten per ``behaviour``-Liste (Callables je Aufruf) steuerbar."""

    def __init__(self):
        self.created: list[tuple[str, str]] = []
        self.create_calls: list[dict] = []
        self.instructions: dict[str, dict] = {}
        self.instructions_error: Exception | None = None
        self.prompts: list[dict] = []
        self.behaviour: list = []
        self.aborted: list[str] = []
        self.models = [
            {"id": "ollama/gemma4:e4b", "name": "g", "variants": []},
            {"id": "opencode/x", "name": "x", "variants": ["low", "high"]},
        ]

    def create_session(self, directory, title=None, permissions=None, *, session_id=None):
        assert os.path.isdir(directory), "Verzeichnis muss vor der Session existieren"
        sid = session_id or f"ses_{len(self.created) + 1}"
        self.created.append((sid, directory))
        self.create_calls.append(
            {"directory": directory, "title": title, "permissions": permissions, "session_id": session_id}
        )
        return sid

    def set_instructions(self, session_id, entries):
        if self.instructions_error:
            raise self.instructions_error
        self.instructions[session_id] = dict(entries)

    def prompt(self, session_id, text, **kw):
        call = {"sid": session_id, "text": text, **kw}
        self.prompts.append(call)
        assert os.path.isdir(kw["directory"]), "Verzeichnis muss vor dem Prompt existieren"
        if self.behaviour:
            return self.behaviour.pop(0)(call)
        kw["on_delta"]("Antwort")
        return f"Antwort auf: {text}"

    def list_models(self, directory=None):
        return self.models

    def abort(self, session_id):
        self.aborted.append(session_id)


@pytest.fixture
def env(tmp_path, monkeypatch):
    ids = iter(range(1, 1000))
    monkeypatch.setattr("captain.bot.new_session_id", lambda: f"ses_{next(ids)}")
    cfg = Config(
        mm_url="http://mm", mm_bot_token="t", opencode_url="http://oc",
        opencode_model="ollama/gemma4:e4b",
        sessions_dir=str(tmp_path / "ws").replace("\\", "/"),
        data_dir=str(tmp_path / "data"),
        system_prompt="Du bist Testkapitän.",
    )
    mm, oc = FakeMM(), FakeOC()
    store = SessionStore(os.path.join(cfg.data_dir, "sessions.json"))
    b = Bot(cfg, mm, oc, store)
    b._side = ThreadPoolExecutor(1)  # FIFO → wait_idle kann Nebenaufgaben abwarten
    yield b, mm, oc, store
    b.stop()


_n = [0]


def post(text, *, ch="c1", ctype="D", root="", user="u_alice", files=(), from_bot=False, at=0):
    _n[0] += 1
    name = USERS[user]["username"]
    return Post(f"p{_n[0]}", ch, ctype, root, user, name, text, list(files), from_bot, at)


def wait_idle(b, timeout=5.0):
    deadline = time.monotonic() + timeout
    b._lookup.submit(lambda: None).result(timeout)  # Nachschlagen abwarten
    while True:
        with b._mutex:
            idle = not b._active
        if idle:
            b._side.submit(lambda: None).result(timeout)  # Nebenaufgaben abwarten
            return
        assert time.monotonic() < deadline, "Bot wird nicht fertig"
        time.sleep(0.01)


# ---------------------------------------------------------------- Hilfen


def test_parse_command():
    assert parse_command("!neu") == ("neu", "")
    assert parse_command("!Modell opencode/x high") == ("modell", "opencode/x high")
    assert parse_command("!!!") is None
    assert parse_command("hallo !neu") is None


def test_display_name():
    assert display_name(USERS["u_alice"]) == "Alice Test"
    assert display_name(USERS["u_bob"]) == "Bobby"
    assert display_name({"username": "x"}) == "x"


# ---------------------------------------------------------------- Routing


def test_dm_answer_flat_and_final_from_return_value(env):
    b, mm, oc, store = env
    b.on_post(post("Hallo"))
    wait_idle(b)
    assert oc.prompts[0]["text"] == "Hallo"  # DM: Text direkt
    assert oc.prompts[0]["model"] == "ollama/gemma4:e4b"
    # Endstand kommt aus dem Rückgabewert, nicht aus dem letzten Delta
    assert mm.final_messages() == ["Antwort auf: Hallo"]
    (reply,) = mm.posts.values()
    assert reply["root_id"] is None and reply["channel_id"] == "c1"
    entry = store.get("dm:c1")
    assert entry["opencode_session_id"] == "ses_1"
    assert entry["directory"] == b.cfg.sessions_dir + "/ses_1"
    assert os.path.isdir(entry["directory"])
    assert oc.prompts[0]["directory"] == entry["directory"]


def test_group_mention_opens_thread_with_name_prefix(env):
    b, mm, oc, store = env
    p = post("@captain Hi", ctype="G", ch="g1", user="u_bob")
    b.on_post(p)
    wait_idle(b)
    assert oc.prompts[0]["text"] == "Bobby: Hi"  # Erwähnung entfernt
    assert store.get(f"th:{p.id}")
    assert oc.created[0][1].endswith("/ws/ses_1")
    (reply,) = mm.posts.values()
    assert reply["root_id"] == p.id and reply["channel_id"] == "g1"


@pytest.mark.parametrize("ctype", ["O", "P", "G"])
def test_channel_without_mention_ignored(env, ctype):
    b, mm, oc, _ = env
    b.on_post(post("Hallo zusammen", ctype=ctype, ch="c5"))
    b.on_post(post("!help", ctype=ctype, ch="c5"))
    b.on_post(post("Antwort im fremden Thread", ctype=ctype, ch="c5", root="fremd"))
    wait_idle(b)
    assert oc.prompts == [] and mm.posts == {}


def test_channel_mention_gets_history_and_followups_need_no_mention(env):
    b, mm, oc, store = env
    mm.channel = [
        {"id": "o1", "user_id": "u_alice", "message": "alt, schon bekannt", "create_at": 1},
        {"id": "t0", "user_id": "u_bob", "message": "@captain frühere Frage", "create_at": 2},
        {"id": "b0", "user_id": "bot1", "root_id": "t0", "message": "frühere Antwort", "create_at": 3},
        {"id": "n1", "user_id": "u_alice", "message": "Ich mag Mangos.", "create_at": 4},
        {"id": "n2", "user_id": "u_robot", "message": "Bot-Rauschen", "create_at": 5},
        {"id": "n3", "user_id": "u_bob", "message": "Ich Birnen.", "create_at": 6},
        {"id": "n4", "user_id": "u_bob", "root_id": "t0", "message": "Thread-Kram", "create_at": 7},
    ]
    p = post("@captain Wer mag was?", ctype="O", ch="c5", user="u_bob", at=8)
    mm.channel.append({"id": p.id, "user_id": "u_bob", "message": p.message, "create_at": 8})
    b.on_post(p)
    wait_idle(b)
    assert oc.prompts[0]["text"] == (
        "[Bisherige Unterhaltung im Kanal seit deiner letzten Antwort hier – nur Vorgeschichte]\n"
        "Alice Test: Ich mag Mangos.\nBobby: Ich Birnen.\n[Ende der Vorgeschichte]\n\n"
        "Bobby: Wer mag was?"
    )
    # Folgeantwort im Thread ohne Erwähnung: gleiche Session, keine Vorgeschichte
    calls = mm.thread_calls
    b.on_post(post("Und du?", ctype="O", ch="c5", root=p.id))
    wait_idle(b)
    assert mm.thread_calls == calls  # Beteiligung bekannt, kein REST
    assert oc.prompts[1]["sid"] == oc.prompts[0]["sid"]
    assert oc.prompts[1]["text"] == "Alice Test: Und du?"
    assert [m["root_id"] for m in mm.posts.values()] == [p.id, p.id]


@pytest.mark.parametrize("posts,chars,expected", [
    (1, 8000, "Bobby: Ich Birnen."),
    (50, len("Bobby: Ich Birnen.") + 1, "Bobby: Ich Birnen."),
    (0, 8000, None),
])
def test_channel_history_limits_from_config(env, posts, chars, expected):
    b, mm, oc, _ = env
    b.cfg = Config(**{**b.cfg.__dict__, "history_max_posts": posts, "history_max_chars": chars})
    mm.channel = [
        {"id": "n1", "user_id": "u_alice", "message": "Ich mag Mangos.", "create_at": 4},
        {"id": "n3", "user_id": "u_bob", "message": "Ich Birnen.", "create_at": 6},
    ]
    b.on_post(post("@captain Wer mag was?", ctype="O", ch="c5", user="u_bob", at=8))
    wait_idle(b)
    text = oc.prompts[0]["text"]
    if expected is None:
        assert text == "Bobby: Wer mag was?"
    else:
        assert f"nur Vorgeschichte]\n{expected}\n[Ende der Vorgeschichte]" in text, text


def test_thread_participation_looked_up_after_restart(env):
    b, mm, oc, _ = env
    mm.roots["r7"] = [
        {"id": "r7", "user_id": "u_bob", "message": "Frage", "create_at": 1},
        {"id": "x7", "user_id": "bot1", "message": "Antwort", "create_at": 2},
    ]
    b.on_post(post("weiter", ctype="P", ch="c5", root="r7"))
    b.on_post(post("noch was", ctype="P", ch="c5", root="r8"))  # ohne Captain
    wait_idle(b)
    assert len(oc.prompts) == 1 and oc.prompts[0]["text"].endswith("Alice Test: weiter")


def test_help_in_channel_with_mention_replies_in_thread(env):
    b, mm, oc, _ = env
    p = post("@captain !help", ctype="O", ch="c5")
    b.on_post(p)
    wait_idle(b)
    ((pid, reply),) = mm.posts.items()
    assert reply["root_id"] == p.id
    assert "!neu" in reply["message"] and "@captain" in reply["message"]
    # im Thread geht !hilfe danach ohne Erwähnung
    b.on_post(post("!hilfe", ctype="O", ch="c5", root=p.id))
    wait_idle(b)
    assert len(mm.posts) == 2 and oc.prompts == []


def test_neu_in_thread_resets_thread_session(env):
    b, mm, oc, store = env
    p = post("@captain los", ctype="O", ch="c5")
    b.on_post(p)
    wait_idle(b)
    assert store.get(f"th:{p.id}")["opencode_session_id"] == "ses_1"
    b.on_post(post("!neu", ctype="O", ch="c5", root=p.id))
    wait_idle(b)
    assert store.get(f"th:{p.id}") is None
    b.on_post(post("weiter", ctype="O", ch="c5", root=p.id))  # ohne Erwähnung
    wait_idle(b)
    assert oc.prompts[-1]["sid"] == "ses_2"


def test_channel_model_applies_to_new_threads(env):
    b, mm, oc, store = env
    b.on_post(post("@captain !modell opencode/x high", ctype="O", ch="c5"))
    wait_idle(b)
    assert store.get("ch:c5") == {"model": "opencode/x", "variant": "high"}
    p = post("@captain Hallo", ctype="O", ch="c5")
    b.on_post(p)
    wait_idle(b)
    assert (oc.prompts[-1]["model"], oc.prompts[-1]["variant"]) == ("opencode/x", "high")
    b.on_post(post("!modell ollama/gemma4:e4b", ctype="O", ch="c5", root=p.id))
    wait_idle(b)
    b.on_post(post("weiter", ctype="O", ch="c5", root=p.id))
    wait_idle(b)
    assert (oc.prompts[-1]["model"], oc.prompts[-1]["variant"]) == ("ollama/gemma4:e4b", None)


def test_thread_reply_uses_root_and_context(env):
    b, mm, oc, store = env
    p = post("Und morgen?", ctype="P", ch="c2", root="r1")
    mm.roots["r1"] = [
        {"id": "r1", "user_id": "u_bob", "message": "Wie wird das Wetter?", "create_at": 1},
        {"id": "x1", "user_id": "bot1", "message": "Sonnig.", "create_at": 2},
        {"id": "x2", "user_id": "u_bob", "message": "joined", "type": "system_x", "create_at": 3},
        {"id": p.id, "user_id": "u_alice", "message": p.message, "create_at": 4},
    ]
    b.on_post(p)
    wait_idle(b)
    assert oc.prompts[0]["text"] == (
        "[Bisheriger Verlauf dieses Threads]\n"
        "Bobby: Wie wird das Wetter?\nCaptain (du): Sonnig.\n"
        "[Ende des Verlaufs]\n\nAlice Test: Und morgen?"
    )
    assert store.get("th:r1")
    (reply,) = mm.posts.values()
    assert reply["root_id"] == "r1"
    # zweite Nachricht im Thread: gleiche Session, kein erneuter Kontext
    b.on_post(post("Danke", ctype="P", ch="c2", root="r1"))
    wait_idle(b)
    assert oc.prompts[1]["sid"] == oc.prompts[0]["sid"]
    assert oc.prompts[1]["text"] == "Alice Test: Danke"


def test_filters(env, tmp_path):
    b, mm, oc, _ = env
    b.on_post(post("von Bot-Prop", from_bot=True))
    b.on_post(post("von Bot-User", user="u_robot"))
    b.on_post(post("   "))
    p = post("einmal")
    b.on_post(p)
    b.on_post(p)  # doppelt zugestellt
    wait_idle(b)
    assert [c["text"] for c in oc.prompts] == ["einmal"]


def test_allowlist(env):
    b, mm, oc, store = env
    b.cfg = Config(**{**b.cfg.__dict__, "allowed_users": frozenset({"bob"})})
    b.on_post(post("alice darf nicht"))
    b.on_post(post("bob darf", user="u_bob"))
    wait_idle(b)
    assert [c["text"] for c in oc.prompts] == ["bob darf"]


def test_attachments_downloaded_into_session_dir(env):
    b, mm, oc, _ = env
    b.on_post(post("Schau mal", files=["f1"]))
    wait_idle(b)
    call = oc.prompts[0]
    assert call["files"] == [call["directory"] + "/f1.txt"]
    assert "f1.txt" in call["text"]
    assert mm.max_bytes == 20 * 1024 * 1024  # Default MAX_ATTACHMENT_MB


def test_attachment_too_large_skipped_and_reported(env):
    b, mm, oc, _ = env
    b.cfg = Config(**{**b.cfg.__dict__, "max_attachment_mb": 2.5})
    b.on_post(post("Lies das", files=["big1", "f2"]))
    wait_idle(b)
    assert mm.max_bytes == int(2.5 * 1024 * 1024)
    call = oc.prompts[0]
    assert call["files"] == [call["directory"] + "/f2.txt"]  # nur der kleine Anhang
    assert "big1.pdf" in call["text"] and "nicht geladen" in call["text"]
    (answer,) = mm.final_messages()
    assert answer.endswith("_(Anhang „big1.pdf“ nicht geladen: größer als 2,5 MB)_")
    assert not os.path.exists(os.path.join(call["directory"], "big1.pdf"))


# ---------------------------------------------------------------- Warteschlange


def test_serialized_and_bundled(env):
    b, mm, oc, _ = env
    gate, running = threading.Event(), threading.Event()

    def slow(call):
        running.set()
        assert gate.wait(5)
        return "erste"

    oc.behaviour = [slow]
    first = post("@captain eins", ctype="G", ch="g1")
    b.on_post(first)
    assert running.wait(5)
    p2 = post("zwei", ctype="G", ch="g1", user="u_bob", root=first.id)
    p3 = post("drei", ctype="G", ch="g1", root=first.id)
    b.on_post(p2)
    b.on_post(p3)
    b.on_post(post("anderswo", ch="c9"))  # andere Session läuft parallel
    deadline = time.monotonic() + 5
    while len(oc.prompts) < 2:
        assert time.monotonic() < deadline
        time.sleep(0.01)
    assert oc.prompts[1]["text"] == "anderswo"
    assert len(oc.prompts) == 2  # g1 wartet
    gate.set()
    wait_idle(b)
    texts = [c["text"] for c in oc.prompts]
    assert texts[2] == "Bobby: zwei\nAlice Test: drei"  # gebündelt, ein Prompt
    assert len(texts) == 3
    assert ("add", p2.id, QUEUED_EMOJI) in mm.reactions
    assert ("remove", p3.id, QUEUED_EMOJI) in mm.reactions


def test_neu_keeps_order(env):
    b, mm, oc, store = env
    gate, running = threading.Event(), threading.Event()

    def slow(call):
        running.set()
        gate.wait(5)
        return "ok"

    oc.behaviour = [slow]
    b.on_post(post("a"))
    assert running.wait(5)
    b.on_post(post("b"))
    b.on_post(post("!neu"))
    b.on_post(post("c"))
    gate.set()
    wait_idle(b)
    assert [(c["sid"], c["text"]) for c in oc.prompts] == [
        ("ses_1", "a"), ("ses_1", "b"), ("ses_2", "c")
    ]
    assert any("Neue Unterhaltung" in m for m in mm.final_messages())


# ---------------------------------------------------------------- Fehler


def test_session_gone_retries_once_with_new_session(env):
    b, mm, oc, store = env
    old = b._session_dir("ses_alt")
    os.makedirs(old)
    store.set("dm:c1", "ses_alt", old)

    def gone(call):
        call["on_delta"]("halb")
        raise SessionGone("weg")

    oc.behaviour = [gone]
    b.on_post(post("Hallo"))
    wait_idle(b)
    assert [c["sid"] for c in oc.prompts] == ["ses_alt", "ses_1"]
    assert store.get("dm:c1")["opencode_session_id"] == "ses_1"
    assert mm.final_messages()[-1] == "Antwort auf: Hallo"
    assert not os.path.exists(old)  # Selbstheilung räumt das alte Verzeichnis ab
    assert oc.prompts[1]["directory"] == b._session_dir("ses_1")


def test_session_gone_twice_gives_up(env):
    b, mm, oc, _ = env

    def gone(call):
        raise SessionGone("weg")

    oc.behaviour = [gone, gone]
    b.on_post(post("Hallo"))
    wait_idle(b)
    assert len(oc.prompts) == 2
    assert "opencode-Fehler" in mm.final_messages()[-1]


def test_timeout_not_retried_and_partial_kept(env):
    b, mm, oc, _ = env

    def timeout(call):
        call["on_delta"]("Teilantwort")
        raise OpencodeError("Keine Rückmeldung von opencode seit 600 s – abgebrochen")

    oc.behaviour = [timeout]
    b.on_post(post("x"))
    wait_idle(b)
    assert len(oc.prompts) == 1
    (msg,) = mm.final_messages()
    assert msg.startswith("Teilantwort\n\n⚠️ opencode-Fehler: Keine Rückmeldung")
    assert CURSOR not in msg


def test_interrupted_is_not_an_error(env):
    b, mm, oc, _ = env

    def interrupted(call):
        call["on_delta"]("Angefangen")
        raise Interrupted("abgebrochen")

    oc.behaviour = [interrupted]
    b.on_post(post("x"))
    wait_idle(b)
    (msg,) = mm.final_messages()
    assert msg == "Angefangen\n\n_(abgebrochen)_"


def test_status_and_cursor_while_running(env):
    b, mm, oc, _ = env
    seen = []

    def tool(call):
        call["on_status"]("⚙️ Ich führe Befehle aus…")
        seen.append([p["message"] for p in mm.posts.values()])
        call["on_delta"]("Ergebnis")
        call["on_status"]("📄 Ich sehe mir Dateien an…")
        return "Ergebnis fertig"

    oc.behaviour = [tool]
    b.on_post(post("x"))
    wait_idle(b)
    (first,) = seen[0]
    assert first == "_⚙️ Ich führe Befehle aus…_" + CURSOR
    assert mm.final_messages() == ["Ergebnis fertig"]


# ---------------------------------------------------------------- Befehle


def test_hilfe(env):
    b, mm, oc, _ = env
    b.on_post(post("!hilfe"))
    wait_idle(b)
    assert "!neu" in mm.final_messages()[0]
    assert oc.prompts == []


def test_modell(env):
    b, mm, oc, store = env
    b.on_post(post("!modell"))
    wait_idle(b)
    assert "`opencode/x` – Varianten: low, high" in mm.final_messages()[-1]
    b.on_post(post("!modell opencode/x high"))
    wait_idle(b)
    assert store.get("dm:c1") == {"model": "opencode/x", "variant": "high"}
    b.on_post(post("!modell gibtsnicht/y"))
    wait_idle(b)
    assert "Unbekanntes Modell" in mm.final_messages()[-1]
    b.on_post(post("Hallo"))
    wait_idle(b)
    assert (oc.prompts[-1]["model"], oc.prompts[-1]["variant"]) == ("opencode/x", "high")
    b.on_post(post("!modell standard"))
    wait_idle(b)
    assert store.get("dm:c1")["opencode_session_id"]
    assert "model" not in store.get("dm:c1")


def test_stopp_aborts_and_drops_queue(env):
    b, mm, oc, _ = env
    gate, running = threading.Event(), threading.Event()

    def slow(call):
        running.set()
        gate.wait(5)
        raise Interrupted("abgebrochen")

    oc.behaviour = [slow]
    b.on_post(post("lang"))
    assert running.wait(5)
    b.on_post(post("wartet"))
    b.on_post(post("!stopp"))
    deadline = time.monotonic() + 5
    while not oc.aborted:
        assert time.monotonic() < deadline
        time.sleep(0.01)
    gate.set()
    wait_idle(b)
    assert oc.aborted == ["ses_1"]
    assert len(oc.prompts) == 1  # „wartet“ verworfen
    msgs = mm.final_messages()
    assert any(m.startswith("⏹️ Abgebrochen. 1 wartende") for m in msgs)
    assert "_(abgebrochen)_" in msgs


# ---------------------------------------------------------------- Review #18


CODE = "def f():\n    return  1\n\n| a  |  b |\n|----|----|"


def test_indented_code_unchanged_in_dm_and_channel(env):
    b, mm, oc, _ = env
    b.on_post(post(CODE))
    wait_idle(b)
    assert oc.prompts[0]["text"] == CODE
    b.on_post(post("@captain\n" + CODE, ctype="O", ch="c5"))
    wait_idle(b)
    assert oc.prompts[1]["text"].endswith("Alice Test: " + CODE)


def test_mention_in_code_is_ignored(env):
    b, mm, oc, _ = env
    b.on_post(post("Schreib `@captain !help`", ctype="O", ch="c5"))
    wait_idle(b)
    assert oc.prompts == [] and mm.posts == {}


def test_negative_thread_cache_and_invalidation(env):
    b, mm, oc, _ = env
    mm.roots["r9"] = [{"id": "r9", "user_id": "u_bob", "message": "x", "create_at": 1}]
    for text in ("eins", "zwei", "drei"):
        b.on_post(post(text, ctype="O", ch="c5", root="r9"))
    wait_idle(b)
    assert mm.thread_calls == 1 and oc.prompts == []  # nur einmal per REST
    b.on_post(post("@captain jetzt du", ctype="O", ch="c5", root="r9"))
    wait_idle(b)
    b.on_post(post("weiter ohne", ctype="O", ch="c5", root="r9"))
    wait_idle(b)
    assert [c["text"].rsplit("\n", 1)[-1] for c in oc.prompts] == [
        "Alice Test: jetzt du", "Alice Test: weiter ohne"]
    assert mm.thread_calls == 2  # Kontext der neuen Session; kein weiteres Nachschlagen


def test_negative_cache_expires(env, monkeypatch):
    b, mm, oc, _ = env
    import captain.bot as botmod
    monkeypatch.setattr(botmod, "NO_THREAD_TTL", 0.0)
    b.on_post(post("a", ctype="O", ch="c5", root="r9"))
    wait_idle(b)
    b.on_post(post("b", ctype="O", ch="c5", root="r9"))
    wait_idle(b)
    assert mm.thread_calls == 2


def test_lookup_keeps_order_within_thread(env):
    b, mm, oc, _ = env
    gate = threading.Event()
    mm.roots["r9"] = [{"id": "x", "user_id": "bot1", "message": "hi", "create_at": 1}]
    orig = mm.get_thread

    def slow(root_id):
        gate.wait(5)
        return orig(root_id)

    mm.get_thread = slow
    b.on_post(post("erst", ctype="O", ch="c5", root="r9"))
    b.on_post(post("@captain dann", ctype="O", ch="c5", root="r9"))  # wartet hinter „erst“
    assert oc.prompts == []
    gate.set()
    wait_idle(b)
    texts = [c["text"] for c in oc.prompts]
    assert "Alice Test: erst" in texts[0] and texts[-1].endswith("Alice Test: dann")


def test_threads_capped(env, monkeypatch):
    b, *_ = env
    import captain.bot as botmod
    monkeypatch.setattr(botmod, "THREADS_MAX", 3)
    for i in range(5):
        b._mark_thread(f"r{i}")
    assert list(b._threads) == ["r2", "r3", "r4"]


def test_top_level_neu_explains(env):
    b, mm, oc, store = env
    p = post("@captain !neu", ctype="O", ch="c5")
    b.on_post(p)
    wait_idle(b)
    ((_, reply),) = mm.posts.items()
    assert reply["root_id"] == p.id and "jede Erwähnung" in reply["message"]
    assert oc.prompts == []


def test_top_level_stopp_stops_channel(env):
    b, mm, oc, _ = env
    gate, running = threading.Event(), threading.Event()

    def slow(call):
        running.set()
        gate.wait(5)
        raise Interrupted("abgebrochen")

    oc.behaviour = [slow]
    first = post("@captain lang", ctype="O", ch="c5")
    b.on_post(first)
    assert running.wait(5)
    b.on_post(post("wartet", ctype="O", ch="c5", root=first.id))
    b.on_post(post("anderswo", ctype="O", ch="c6", root="zz"))  # anderer Kanal, ignoriert
    stop = post("@captain !stopp", ctype="O", ch="c5")
    b.on_post(stop)
    deadline = time.monotonic() + 5
    while not oc.aborted:
        assert time.monotonic() < deadline
        time.sleep(0.01)
    gate.set()
    wait_idle(b)
    assert oc.aborted == ["ses_1"] and len(oc.prompts) == 1
    replies = [m for m in mm.posts.values() if m["root_id"] == stop.id]
    assert replies[0]["message"] == (
        "⏹️ 1 laufende Antwort(en) im Kanal abgebrochen. 1 wartende Nachricht(en) verworfen.")


def test_modell_standard_in_thread_names_channel_model(env):
    b, mm, oc, store = env
    store.set_setting("ch:c5", model="opencode/x", variant="low")
    p = post("@captain !modell ollama/gemma4:e4b", ctype="O", ch="c5", root="r1")
    b.on_post(p)
    wait_idle(b)
    b.on_post(post("!modell standard", ctype="O", ch="c5", root="r1"))
    wait_idle(b)
    assert mm.final_messages()[-1] == (
        "Modell zurückgesetzt – jetzt gilt `opencode/x`, Variante `low` (Kanal).")
    b.on_post(post("!modell", ctype="O", ch="c5", root="r1"))
    wait_idle(b)
    assert mm.final_messages()[-1].startswith("**Modell hier:** `opencode/x`, Variante `low` (Kanal)")


# ---------------------------------------------------------------- Session-Verzeichnis


def test_new_session_gets_dir_permissions_and_instructions(env):
    b, mm, oc, store = env
    b.on_post(post("Hallo"))
    wait_idle(b)
    (call,) = oc.create_calls
    directory = b.cfg.sessions_dir + "/ses_1"
    assert call["session_id"] == "ses_1" and call["directory"] == directory
    rules = call["permissions"]
    assert {"action": "read", "resource": directory + "/*", "effect": "allow"} in rules
    assert {"action": "external_directory", "resource": "*", "effect": "deny"} in rules
    # Shell & Co. nie freigegeben, sondern zum Schluss ausdrücklich verboten
    assert not any(r["effect"] == "allow" and r["action"] in ("shell", "webfetch", "execute", "*")
                   for r in rules)
    assert rules[-1]["effect"] == "deny"
    entries = oc.instructions["ses_1"]
    assert list(entries) == ["captain-persona", "captain-umgebung"]  # Reihenfolge
    assert entries["captain-persona"] == "Du bist Testkapitän."
    assert directory in entries["captain-umgebung"]


def test_instructions_failure_does_not_block(env):
    b, mm, oc, store = env
    oc.instructions_error = OpencodeError("HTTP 404")
    b.on_post(post("Hallo"))
    wait_idle(b)
    assert store.get("dm:c1")["opencode_session_id"] == "ses_1"
    first = oc.prompts[0]["text"]
    # Ersatz: markierter Vorspann mit Persona + Arbeitsumgebung im ersten Prompt
    assert first.startswith("[Systemhinweise für diese Unterhaltung]\nDu bist Testkapitän.")
    assert b._session_dir("ses_1") in first
    assert first.endswith("[Ende der Systemhinweise]\n\nHallo")
    # nächster Prompt: einmaliger Neuversuch über die API (klappt jetzt)
    oc.instructions_error = None
    b.on_post(post("Weiter"))
    wait_idle(b)
    assert oc.prompts[1]["text"] == "Weiter"
    assert list(oc.instructions["ses_1"]) == ["captain-persona", "captain-umgebung"]


def test_instructions_retry_only_once(env):
    b, mm, oc, store = env
    oc.instructions_error = OpencodeError("HTTP 500")
    calls = []
    orig = oc.set_instructions

    def counting(sid, entries):
        calls.append(sid)
        return orig(sid, entries)

    oc.set_instructions = counting
    for text in ("eins", "zwei", "drei"):
        b.on_post(post(text))
        wait_idle(b)
    assert len(calls) == 2  # beim Anlegen + ein Neuversuch
    texts = [c["text"] for c in oc.prompts]
    assert texts[0].startswith("[Systemhinweise") and texts[1].startswith("[Systemhinweise")
    assert texts[2] == "drei"


def test_neu_removes_session_dir_and_next_gets_new_one(env):
    b, mm, oc, store = env
    b.on_post(post("Schau mal", files=["f1"]))
    wait_idle(b)
    first = store.get("dm:c1")["directory"]
    assert os.path.isfile(first + "/f1.txt")
    b.on_post(post("!neu"))
    wait_idle(b)
    assert not os.path.exists(first)
    assert "directory" not in (store.get("dm:c1") or {})
    b.on_post(post("weiter"))
    wait_idle(b)
    second = store.get("dm:c1")["directory"]
    assert second != first and second.endswith("/ses_2") and os.path.isdir(second)


def test_existing_session_keeps_dir_and_recreates_missing(env):
    b, mm, oc, store = env
    b.on_post(post("eins"))
    wait_idle(b)
    directory = store.get("dm:c1")["directory"]
    os.rmdir(directory)  # z. B. Volume geleert
    b.on_post(post("zwei"))
    wait_idle(b)
    assert [c["sid"] for c in oc.prompts] == ["ses_1", "ses_1"]
    assert os.path.isdir(directory)


def test_legacy_workspace_entry_gets_new_session_without_deleting(env, tmp_path):
    b, mm, oc, store = env
    legacy = tmp_path / "workspaces" / "dm_c1"
    legacy.mkdir(parents=True)
    store.set("dm:c1", "ses_alt", str(legacy).replace("\\", "/"))
    b.on_post(post("Hallo"))
    wait_idle(b)
    assert oc.prompts[0]["sid"] == "ses_1"
    assert legacy.exists()  # außerhalb von SESSIONS_DIR wird nichts gelöscht


def test_remove_dir_only_below_sessions_dir(env, tmp_path):
    b, *_ = env
    outside = tmp_path / "fremd"
    outside.mkdir()
    b._remove_dir(str(outside))
    b._remove_dir(b.cfg.sessions_dir)
    b._remove_dir(b.cfg.sessions_dir + "/../fremd")
    assert outside.exists()
