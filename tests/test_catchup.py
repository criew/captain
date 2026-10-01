"""Nachholen verpasster Posts: Zustand, Auswahl und Bot-Ablauf (ohne Netz).

Ziel: nach einem Neustart geht keine an Captain gerichtete Nachricht verloren,
und keine wird doppelt beantwortet.
"""

from __future__ import annotations

import json
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import pytest

from captain.bot import Bot
from captain.catchup import (
    INTERRUPTED_NOTE,
    LATE_NOTE,
    answered_in,
    answers_props,
    fetch_after,
    is_unfinished,
    order_missed,
    stale_partials,
    start_for_new,
)
from captain.config import Config
from captain.cursors import CursorStore
from captain.mattermost import Post
from captain.opencode import Interrupted
from captain.sessions import SessionStore
from test_bot import FakeMM, FakeOC, wait_idle

H = 3600 * 1000
NOW = 100 * H  # „jetzt“ in ms


def read(path) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def wait_for(cond, timeout=5.0, what="Bedingung"):
    deadline = time.monotonic() + timeout
    while not cond():
        assert time.monotonic() < deadline, f"{what} nicht erreicht"
        time.sleep(0.01)


# ---------------------------------------------------------------- CursorStore


def store(path, **kw):
    return CursorStore(str(path), interval=0, clock=lambda: NOW, **kw)


def test_store_not_written_before_first_start(tmp_path):
    path = tmp_path / "d" / "cursors.json"
    c = store(path)
    assert not c.initialized and c.get("c1") is None
    c.advance("c1", 5)
    c.release("c1", "p1")
    c.close()
    assert not path.exists()  # kein halber Zustand vor dem ersten erfolgreichen Start


def test_store_roundtrip(tmp_path):
    path = tmp_path / "d" / "cursors.json"
    c = store(path)
    c.set_all(["c1", "c2"], 500)
    c.advance("c1", 700)
    c.advance("c1", 600)  # nie zurück
    c.advance("", 900)  # ohne Kanal: ignoriert
    c.mark_answered("p9")
    c.close()
    assert read(path) == {"version": 2, "initialized": True,
                          "channels": {"c1": 700, "c2": 500}, "answered": {"p9": NOW}}
    again = store(path)
    assert again.initialized and again.get("c1") == 700 and again.is_answered("p9")
    assert not [n for n in os.listdir(tmp_path / "d") if n.endswith(".tmp")]


def test_store_version_1_counts_as_initialized(tmp_path):
    path = tmp_path / "cursors.json"
    path.write_text(json.dumps({"version": 1, "channels": {"c1": 5}}), encoding="utf-8")
    c = store(path)
    assert c.initialized and c.get("c1") == 5


def test_store_low_watermark(tmp_path):
    path = tmp_path / "cursors.json"
    c = store(path)
    c.set_all(["c1"], 100)
    c.hold("c1", "p1", 200)  # angenommen, noch nicht beantwortet
    c.advance("c1", 200)
    c.hold("c1", "p2", 300)
    c.advance("c1", 400)
    assert c.snapshot() == {"c1": 199}
    c.release("c1", "p2")
    assert c.snapshot() == {"c1": 199}
    c.release("c1", "p1")
    assert c.snapshot() == {"c1": 400}
    c.release("c1", "p3", answered=False)
    c.close()
    assert read(path)["answered"] == {"p1": NOW, "p2": NOW}


def test_store_freeze(tmp_path):
    c = store(tmp_path / "cursors.json")
    c.set_all(["c1", "c2"], 100)
    c.freeze("c1", 100)
    c.advance("c1", 500)
    c.advance("c2", 500)
    assert c.snapshot() == {"c1": 100, "c2": 500}
    c.unfreeze("c1")
    assert c.snapshot() == {"c1": 500, "c2": 500}
    c.flush()
    c.freeze_all()  # Kanalliste nicht ladbar: nichts Neues schreiben
    c.advance("c1", 900)
    c.mark_known("c3", 900)
    assert c.snapshot() == {"c1": 500, "c2": 500}
    c.unfreeze_all()
    assert c.snapshot() == {"c1": 900, "c2": 500, "c3": 900}


def test_store_answered_expire(tmp_path):
    path = tmp_path / "cursors.json"
    now = [NOW]
    c = CursorStore(str(path), interval=0, answered_ttl=3600, clock=lambda: now[0])
    c.set_all([], 0)
    c.mark_answered("alt")
    now[0] += 2 * H
    c.mark_answered("neu")
    c.close()
    assert read(path)["answered"] == {"neu": NOW + 2 * H}


def test_store_throttled(tmp_path):
    path = str(tmp_path / "cursors.json")
    c = CursorStore(path, interval=1.0)
    c.set_all(["c1"], 1)  # schreibt sofort
    c.advance("c1", 2)
    c.advance("c1", 3)
    assert read(path)["channels"] == {"c1": 1}  # noch gedrosselt

    def written():
        try:
            return read(path)["channels"] == {"c1": 3}
        except (OSError, ValueError):  # Windows: Datei wird gerade ersetzt
            return False

    wait_for(written, what="gedrosseltes Schreiben")
    c.close()


def test_store_corrupt_counts_as_first_start(tmp_path):
    path = tmp_path / "cursors.json"
    path.write_text("{kaputt", encoding="utf-8")
    c = store(path)
    assert not c.initialized and c.snapshot() == {}
    assert (tmp_path / "cursors.json.corrupt").exists()


# ---------------------------------------------------------------- Auswahl


def test_start_for_new():
    bot, oldest = "bot1", NOW - 24 * H
    assert start_for_new({"type": "D", "create_at": NOW - 5 * H}, [], bot, oldest) == NOW - 5 * H - 1
    assert start_for_new({"type": "G", "create_at": 1}, [], bot, oldest) == oldest
    posts = [
        {"type": "system_add_to_channel", "user_id": "adm", "props": {"addedUserId": "u2"},
         "create_at": NOW - 1 * H},
        {"type": "system_add_to_channel", "user_id": "adm", "props": {"addedUserId": bot},
         "create_at": NOW - 5 * H},
        {"type": "system_join_channel", "user_id": bot, "create_at": NOW - 4 * H},
    ]
    assert start_for_new({"type": "O"}, posts, bot, oldest) == NOW - 4 * H
    assert start_for_new({"type": "P"}, posts[:1], bot, oldest) == oldest  # Beitritt älter


def test_fetch_after_caps_age():
    assert fetch_after(NOW - H, NOW, 24 * H) == (NOW - H, False)
    assert fetch_after(NOW - 25 * H, NOW, 24 * H) == (NOW - 24 * H, True)
    assert fetch_after(NOW - H, NOW, 0) == (NOW, True)  # 0 = nichts nachholen


def test_order_missed_chronological_and_deduplicated():
    posts = [
        {"id": "b", "create_at": 3}, {"id": "a", "create_at": 1},
        {"id": "c", "create_at": 3}, {"id": "a", "create_at": 1}, {"create_at": 2},
    ]
    assert [p["id"] for p in order_missed(posts)] == ["a", "b", "c"]


def test_answered_in_uses_props_not_timestamps():
    posts = [
        {"user_id": "bot1", "message": "fertig", "create_at": 10,
         "props": {"from_bot": "true", "captain_answers": ["d1", "d2"]}},
        {"user_id": "bot1", "message": "x ▌", "create_at": 20, "props": {"from_bot": "true"}},
        {"user_id": "bot1", "message": "gelöscht", "create_at": 30, "delete_at": 31,
         "props": {"captain_answers": ["d3"]}},
        {"user_id": "u_alice", "message": "gefälscht", "create_at": 40,
         "props": {"captain_answers": ["d4"]}},
    ]
    assert answered_in(posts, "bot1") == {"d1", "d2"}
    assert answers_props(["a", "", "b"]) == {"captain_answers": ["a", "b"]}
    assert is_unfinished("a ▌ ") and is_unfinished(f"a\n\n{INTERRUPTED_NOTE}")
    assert not is_unfinished("fertig")
    assert [p["create_at"] for p in stale_partials(posts, "bot1", 25)] == [20]


# ---------------------------------------------------------------- Bot


class HistMM(FakeMM):
    """FakeMM, dessen Kanalverlauf auch die eigenen Posts des Bots enthält.

    Überlebt „Neustarts“ (mehrere Bots nacheinander auf demselben Objekt).
    """

    def __init__(self):
        super().__init__()
        self.t = NOW + 1000  # Bot-Posts nach den Nutzer-Posts der Tests
        self.fail_channels: set[str] = set()
        self.fail_list = False
        self.update_calls: list[tuple[str, str]] = []

    def create_post(self, channel_id, message, root_id=None, file_ids=None, props=None):
        pid = super().create_post(channel_id, message, root_id, file_ids, props)
        with self.lock:
            self.t += 1
            self.history.setdefault(channel_id, []).append(
                {**raw(pid, channel_id, "bot1", message, self.t, root=root_id or ""),
                 "props": dict(props or {})})
        return pid

    def update_post(self, post_id, message, props=None):
        if post_id in self.posts:
            super().update_post(post_id, message, props)
        else:
            self.update_calls.append((post_id, message))
        with self.lock:
            for posts in self.history.values():
                for p in posts:
                    if p["id"] == post_id:
                        p["message"] = message
                        if props is not None:
                            p["props"] = dict(props)

    def my_channels(self):
        if self.fail_list:
            raise OSError("Kanalliste weg")
        return super().my_channels()

    def get_posts_after(self, channel_id, after, per_page=200):
        if channel_id in self.fail_channels:
            raise OSError("Kanal weg")
        with self.lock:
            return super().get_posts_after(channel_id, after, per_page)

    def user_post(self, pid, ch, user, text, at, root=""):
        self.history.setdefault(ch, []).append(raw(pid, ch, user, text, at, root))
        return Post(pid, ch, {"dm1": "D"}.get(ch, "P"), root, user,
                    {"u_alice": "alice", "u_bob": "bob"}[user], text, [], False, at)

    def bot_messages(self, ch):
        with self.lock:
            return [p for p in self.history.get(ch, []) if p["user_id"] == "bot1"]


CHANNELS = [
    {"id": "dm1", "type": "D", "create_at": 1},
    {"id": "fam", "type": "P", "create_at": 1},
]


def raw(pid, ch, user, text, at, root=""):
    return {"id": pid, "channel_id": ch, "user_id": user, "message": text,
            "create_at": at, "update_at": at, "root_id": root, "type": ""}


@pytest.fixture
def make(tmp_path):
    """Baut einen Bot; ``cursors`` = Inhalt einer vorhandenen Cursor-Datei.

    ``mm`` wiederverwenden = derselbe Server nach einem Neustart.
    """
    bots = []
    data = tmp_path / "data"

    def build(cursors: dict | None = None, *, mm=None, interval=0.0, **cfg_kw):
        if cursors is not None:
            data.mkdir(exist_ok=True)
            (data / "cursors.json").write_text(json.dumps(
                {"version": 2, "initialized": True, "channels": cursors, "answered": {}}),
                encoding="utf-8")
        cfg = Config(
            mm_url="http://mm", mm_bot_token="t", opencode_url="http://oc",
            sessions_dir=str(tmp_path / "ws").replace("\\", "/"), data_dir=str(data),
            **cfg_kw,
        )
        if mm is None:
            mm = HistMM()
            mm.channels = [dict(c) for c in CHANNELS]
        oc = FakeOC()
        cur = CursorStore(str(data / "cursors.json"), interval=interval,
                          answered_ttl=cfg.catchup_max_age, clock=lambda: NOW)
        b = Bot(cfg, mm, oc, SessionStore(str(data / "sessions.json")), cursors=cur)
        b._side = ThreadPoolExecutor(1)
        b._clock = lambda: NOW
        b._started_ms = mm.t + 1  # Zwischenstände bis hierher: früherer Prozess
        b.catchup_backoff = (0.05, 0.1)
        bots.append(b)
        return b, mm, oc

    build.path = data / "cursors.json"
    yield build
    for b in bots:
        b.stop()


def replies(mm):
    with mm.lock:
        return [dict(p) for p in mm.posts.values()]


def prompted(oc):
    return "\n".join(p["text"] for p in oc.prompts)


def test_first_start_sets_cursors_to_now_and_answers_nothing(make):
    b, mm, oc = make()
    mm.user_post("d1", "dm1", "u_alice", "alt", NOW - H)
    assert b.catch_up() is True
    wait_idle(b)
    assert oc.prompts == [] and mm.after_calls == []
    b.cursors.close()
    assert read(make.path)["channels"] == {"dm1": NOW, "fam": NOW}


def test_restart_answers_missed_with_late_note(make):
    b, mm, oc = make({"dm1": NOW - 2 * H, "fam": NOW - 2 * H})
    mm.history["dm1"] = [
        raw("d0", "dm1", "u_alice", "schon gesehen", NOW - 3 * H),
        raw("d1", "dm1", "u_alice", "Hallo, bist du da?", NOW - 30_000),
    ]
    mm.history["fam"] = [
        raw("f1", "fam", "u_bob", "ohne Erwähnung", NOW - 60_000),
        raw("f2", "fam", "u_bob", "@captain Frage im Kanal", NOW - 50_000),
        {**raw("f3", "fam", "u_bob", "", NOW - 40_000), "type": "system_join_channel"},
    ]
    routed = []
    orig = b.on_post
    b.on_post = lambda p: (routed.append(p.id), orig(p))
    assert b.catch_up() is True
    wait_idle(b)
    assert routed == ["f2", "d1"]  # chronologisch über alle Kanäle
    assert mm.after_calls == [("dm1", NOW - 2 * H), ("fam", NOW - 2 * H)]
    assert sorted(p["text"] for p in oc.prompts) == ["Bobby: Frage im Kanal", "Hallo, bist du da?"]
    posts = replies(mm)
    assert len(posts) == 2
    assert all(p["message"].startswith(LATE_NOTE + "\n\n") for p in posts)
    by_channel = {p["channel_id"]: p for p in posts}
    assert by_channel["fam"]["root_id"] == "f2" and by_channel["dm1"]["root_id"] is None
    # beantwortet → Cursor hinter allem Gesehenen, IDs gemerkt
    assert b.cursors.get("dm1") == NOW - 30_000 and b.cursors.get("fam") == NOW - 40_000
    assert b.cursors.is_answered("d1") and b.cursors.is_answered("f2")


def test_last_post_at_skips_quiet_channels(make):
    b, mm, oc = make({"dm1": NOW - H, "fam": NOW - H})
    mm.channels[0]["last_post_at"] = NOW - H  # nichts Neues
    mm.channels[1]["last_post_at"] = NOW - 10
    mm.user_post("f1", "fam", "u_bob", "@captain hallo", NOW - 10)
    assert b.catch_up() is True
    assert [c for c, _ in mm.after_calls] == ["fam"]


def test_older_than_max_age_skipped(make):
    b, mm, oc = make({"dm1": NOW - 25 * H, "fam": NOW})
    mm.user_post("d1", "dm1", "u_alice", "zu alt", NOW - 24 * H - 1)
    mm.user_post("d2", "dm1", "u_alice", "frisch", NOW - H)
    b.catch_up()
    wait_idle(b)
    assert ("dm1", NOW - 24 * H) in mm.after_calls
    assert [p["text"] for p in oc.prompts] == ["frisch"]


def test_configurable_max_age(make):
    b, mm, oc = make({"dm1": NOW - 5 * H, "fam": NOW}, catchup_max_age=3600.0)
    mm.user_post("d1", "dm1", "u_alice", "2 h alt", NOW - 2 * H)
    b.catch_up()
    wait_idle(b)
    assert ("dm1", NOW - H) in mm.after_calls and oc.prompts == []


def test_max_age_zero_catches_up_nothing(make):
    b, mm, oc = make({"dm1": NOW - 5 * H, "fam": NOW}, catchup_max_age=0.0)
    mm.user_post("d1", "dm1", "u_alice", "eben", NOW - 1)
    b.catch_up()
    wait_idle(b)
    assert oc.prompts == []


def test_dedupe_with_live_post(make):
    b, mm, oc = make({"dm1": NOW - H, "fam": NOW})
    b.on_post(mm.user_post("d1", "dm1", "u_alice", "Hallo", NOW - 1000))  # kam schon live
    wait_idle(b)
    assert b.catch_up() is True
    wait_idle(b)
    assert len(oc.prompts) == 1
    assert not replies(mm)[0]["message"].startswith(LATE_NOTE)


def test_channel_without_cursor(make):
    b, mm, oc = make({"fam": NOW})  # dm1, neu und alt fehlen
    mm.channels[0]["create_at"] = NOW - 2 * H  # DM während der Pause entstanden
    mm.channels += [{"id": "neu", "type": "O", "create_at": 1},
                    {"id": "alt", "type": "O", "create_at": 1}]
    mm.user_post("d1", "dm1", "u_alice", "erste DM", NOW - 2 * H + 10)
    mm.history["neu"] = [
        raw("n1", "neu", "u_bob", "@captain vor dem Beitritt", NOW - H - 10),
        {"id": "j", "channel_id": "neu", "type": "system_add_to_channel", "user_id": "adm",
         "props": {"addedUserId": "bot1"}, "create_at": NOW - H, "message": ""},
        raw("n2", "neu", "u_bob", "@captain nach dem Beitritt", NOW - H + 10),
    ]
    mm.history["alt"] = [  # Beitritt länger her: Höchstalter, nur Adressiertes
        raw("a1", "alt", "u_bob", "Plauderei", NOW - 3 * H),
        raw("a2", "alt", "u_bob", "@captain länger her", NOW - 2 * H),
    ]
    assert b.catch_up() is True
    wait_idle(b)
    oldest = NOW - 24 * H
    assert {("dm1", oldest), ("neu", oldest), ("alt", oldest)} <= set(mm.after_calls)
    assert sorted(p["text"] for p in oc.prompts) == [
        "Bobby: länger her", "Bobby: nach dem Beitritt", "erste DM"]
    assert b.cursors.get("neu") == NOW - H + 10 and b.cursors.get("alt") == NOW - 2 * H


def test_thread_reply_needs_participation(make):
    b, mm, oc = make({"dm1": NOW, "fam": NOW - H})
    mm.roots["r1"] = [raw("r1", "fam", "u_bob", "Frage", 1), raw("x1", "fam", "bot1", "Antwort", 2)]
    mm.user_post("t1", "fam", "u_alice", "Nachfrage im Thread", NOW - 100, root="r1")
    mm.user_post("t2", "fam", "u_alice", "fremder Thread", NOW - 90, root="r2")
    b.catch_up()
    wait_idle(b)
    (prompt,) = oc.prompts
    assert prompt["text"].endswith("\n\nAlice Test: Nachfrage im Thread")
    (reply,) = replies(mm)
    assert reply["root_id"] == "r1" and reply["message"].startswith(LATE_NOTE)


def test_late_note_on_command_but_not_on_error(make):
    b, mm, oc = make({"dm1": NOW - H, "fam": NOW - H})
    mm.user_post("d1", "dm1", "u_alice", "!help", NOW - 100)
    mm.user_post("f1", "fam", "u_bob", "@captain kaputt", NOW - 90)
    oc.behaviour = [lambda call: (_ for _ in ()).throw(RuntimeError("weg"))]
    b.catch_up()
    wait_idle(b)
    msgs = {p["channel_id"]: p["message"] for p in replies(mm)}
    assert msgs["dm1"].startswith(LATE_NOTE + "\n\n**Captain**")
    assert msgs["fam"].startswith("⚠️ Fehler")  # kein Hinweis vor der Fehlernotiz


def test_live_posts_buffered_until_catch_up_done(make):
    b, mm, oc = make({"dm1": NOW - H, "fam": NOW})
    missed = mm.user_post("d1", "dm1", "u_alice", "verpasst", NOW - 100)
    gate = threading.Event()
    orig = mm.my_channels

    def slow_channels():
        assert gate.wait(5)
        return orig()

    mm.my_channels = slow_channels
    routed = []
    orig_on_post = b.on_post
    b.on_post = lambda p: (routed.append(p.id), orig_on_post(p))
    b._on_hello()  # neue Verbindung → Nachholen startet, Live wird gepuffert
    live = Post("L1", "dm1", "D", "", "u_alice", "alice", "live", [], False, NOW + 10)
    b._on_ws_post(live)
    b._on_ws_post(missed)  # kam per REST und live
    assert routed == []  # noch gepuffert
    gate.set()
    assert b.catchup_done.wait(5)
    wait_idle(b)
    assert routed[:2] == ["d1", "L1"]  # erst nachgeholt, dann Puffer
    texts = [p["message"] for p in replies(mm)]
    assert prompted(oc).count("verpasst") == 1 and "live" in prompted(oc)
    assert texts[0].startswith(LATE_NOTE)  # Antwort auf d1 kommt zuerst
    b._on_ws_post(Post("L2", "dm1", "D", "", "u_alice", "alice", "direkt", [], False, NOW + 20))
    assert routed[-1] == "L2"  # nach dem Nachholen wieder direkt


def test_second_hello_during_catch_up_runs_again(make):
    b, mm, oc = make({"dm1": NOW - H, "fam": NOW})
    gate = threading.Event()
    calls = []
    orig = mm.my_channels

    def slow_channels():
        calls.append(1)
        if len(calls) == 1:
            assert gate.wait(5)
        return orig()

    mm.my_channels = slow_channels
    b._on_hello()
    wait_for(lambda: calls, what="erstes Nachholen")
    b._on_hello()  # Reconnect ohne Replay, während noch nachgeholt wird
    gate.set()
    wait_for(lambda: len(calls) == 2 and b.catchup_ok.is_set(), what="zweites Nachholen")


# -- Neustart: nichts verlieren, nichts doppelt ------------------------------


def test_stop_while_streaming_and_queued_answers_once_after_restart(make):
    """(a) Stopp während eine Antwort streamt und eine weitere wartet."""
    b1, mm, oc1 = make({"dm1": NOW - H, "fam": NOW - H})
    gate, streaming = threading.Event(), threading.Event()

    def slow(call):
        call["on_delta"]("Teil")
        streaming.set()
        assert gate.wait(5)
        raise Interrupted("abgebrochen")  # opencode nach abort

    oc1.behaviour = [slow]
    b1.on_post(mm.user_post("d1", "dm1", "u_alice", "Erste Frage", NOW + 1))
    assert streaming.wait(5)
    wait_for(lambda: mm.bot_messages("dm1"), what="Zwischenstand")
    b1.on_post(mm.user_post("d2", "dm1", "u_alice", "Zweite Frage", NOW + 2))  # wartet (⏳)
    b1.stop()  # SIGTERM
    gate.set()
    b1.wait_quiet(5)
    (partial,) = mm.bot_messages("dm1")
    assert partial["message"].endswith(INTERRUPTED_NOTE)
    assert oc1.aborted  # laufende Antwort abgebrochen
    saved = read(make.path)
    assert saved["channels"]["dm1"] == NOW + 1 - 1 and saved["answered"] == {}

    b2, _, oc2 = make(mm=mm)  # Neustart, gleicher Server
    assert b2.catch_up() is True
    wait_idle(b2)
    text = prompted(oc2)
    assert text.count("Erste Frage") == 1 and text.count("Zweite Frage") == 1
    finals = [p for p in mm.bot_messages("dm1") if not is_unfinished(p["message"])]
    assert len(finals) == len(oc2.prompts) and finals[0]["message"].startswith(LATE_NOTE)
    b2.cursors.flush()
    assert read(make.path)["channels"]["dm1"] > NOW + 2
    assert b2.catch_up() is True  # nochmal: nichts doppelt
    wait_idle(b2)
    assert len(oc2.prompts) == len(finals)


def test_crash_after_answer_before_cursor_saved_no_double(make):
    """(b) Antwort steht, Cursor/IDs noch nicht geschrieben → kein zweites Mal."""
    b1, mm, oc1 = make({"dm1": NOW - H, "fam": NOW - H}, interval=60)
    b1.on_post(mm.user_post("d1", "dm1", "u_alice", "Frage", NOW + 1))
    b1.on_post(mm.user_post("f1", "fam", "u_bob", "@captain Frage", NOW + 2))
    wait_idle(b1)
    assert len(oc1.prompts) == 2
    assert read(make.path)["channels"] == {"dm1": NOW - H, "fam": NOW - H}  # Absturz: nichts gesichert
    b2, _, oc2 = make(mm=mm)
    assert b2.catch_up() is True
    wait_idle(b2)
    assert oc2.prompts == []
    assert b2.cursors.is_answered("d1") and b2.cursors.is_answered("f1")


def test_answered_ids_skip_on_catch_up(make):
    b, mm, oc = make({"dm1": NOW - H, "fam": NOW})
    mm.user_post("d1", "dm1", "u_alice", "Frage", NOW - 100)
    b.cursors.mark_answered("d1")
    b.catch_up()
    wait_idle(b)
    assert oc.prompts == []


def test_failed_channel_is_frozen_and_retried(make):
    """(c) Nachholen scheitert für einen Kanal → Cursor bleibt, Wiederholung holt nach."""
    b, mm, oc = make({"dm1": NOW - H, "fam": NOW - H})
    mm.user_post("f1", "fam", "u_bob", "@captain verpasst", NOW - 100)
    mm.user_post("d1", "dm1", "u_alice", "verpasst", NOW - 90)
    mm.fail_channels.add("fam")
    b._on_hello()
    assert b.catchup_done.wait(5)
    wait_idle(b)
    assert prompted(oc) == "verpasst"  # DM ging
    # live geht weiter, der Cursor von fam wandert trotzdem nicht über die Lücke
    b._on_ws_post(mm.user_post("f2", "fam", "u_bob", "nur Plauderei", NOW + 5))
    time.sleep(0.2)  # mehrere erfolglose Wiederholungen
    assert b.cursors.snapshot()["fam"] == NOW - H and not b.catchup_ok.is_set()
    mm.fail_channels.clear()
    assert b.catchup_ok.wait(5)
    wait_idle(b)
    assert prompted(oc).count("Bobby: verpasst") == 1
    assert b.cursors.snapshot()["fam"] == NOW + 5


def test_failed_channel_list_freezes_all(make):
    b, mm, oc = make({"dm1": NOW - H, "fam": NOW - H})
    mm.fail_list = True
    mm.user_post("d1", "dm1", "u_alice", "verpasst", NOW - 90)
    b._on_hello()
    assert b.catchup_done.wait(5)
    b._on_ws_post(mm.user_post("d2", "dm1", "u_alice", "live", NOW + 5))
    wait_idle(b)
    b.cursors.flush()
    assert read(make.path)["channels"] == {"dm1": NOW - H, "fam": NOW - H}
    mm.fail_list = False
    assert b.catchup_ok.wait(5)
    wait_idle(b)
    assert prompted(oc).count("verpasst") == 1 and prompted(oc).count("live") == 1


def test_first_start_with_failing_catch_up_no_flood_later(make):
    """(d) Erster Start scheitert → keine Datei → nächster Start wieder „erster Start“."""
    b1, mm, oc1 = make()
    mm.fail_list = True
    mm.user_post("d0", "dm1", "u_alice", "uralt", NOW - 20 * H)
    b1._on_hello()
    assert b1.catchup_done.wait(5)
    b1._on_ws_post(mm.user_post("d1", "dm1", "u_alice", "live", NOW + 1))
    wait_idle(b1)
    b1.stop()
    assert not make.path.exists()
    mm.fail_list = False
    b2, _, oc2 = make(mm=mm)
    assert b2.catch_up() is True
    wait_idle(b2)
    assert oc2.prompts == []  # keine Flut aus 24 h DMs
    assert read(make.path)["initialized"] is True


def test_queued_post_not_lost_when_earlier_answer_is_newer(make):
    """Nutzer schickt d1, dann d2, bevor die Antwort auf d1 angelegt ist; d1
    wird fertig beantwortet (Antwort-Post jünger als d2), d2 streamt, SIGTERM →
    nach dem Neustart wird d2 beantwortet (nicht wegen der jüngeren Antwort
    auf d1 übersprungen)."""
    b1, mm, oc1 = make({"dm1": NOW - H, "fam": NOW - H})
    gate1, started1 = threading.Event(), threading.Event()
    gate2, streaming2 = threading.Event(), threading.Event()

    def first(call):
        started1.set()
        assert gate1.wait(5)
        call["on_delta"]("Antwort 1")
        return "Antwort 1"

    def second(call):
        call["on_delta"]("Teil 2")
        streaming2.set()
        assert gate2.wait(5)
        raise Interrupted("abgebrochen")

    oc1.behaviour = [first, second]
    b1.on_post(mm.user_post("d1", "dm1", "u_alice", "Erste Frage", NOW + 1))
    assert started1.wait(5)
    b1.on_post(mm.user_post("d2", "dm1", "u_alice", "Zweite Frage", NOW + 2))
    gate1.set()
    assert streaming2.wait(5)
    b1.stop()
    gate2.set()
    b1.wait_quiet(5)
    b2, _, oc2 = make(mm=mm)
    assert b2.catch_up() is True
    wait_idle(b2)
    assert prompted(oc2) == "Zweite Frage"  # d1 nicht noch einmal, d2 nicht verloren


def test_command_reply_does_not_cover_later_post(make):
    """Wie oben, aber die frühere Antwort ist eine Befehlsantwort (!help)."""
    b1, mm, oc1 = make({"dm1": NOW - H, "fam": NOW - H}, interval=60)
    gate, streaming = threading.Event(), threading.Event()

    def slow(call):
        call["on_delta"]("Teil")
        streaming.set()
        assert gate.wait(5)
        raise Interrupted("abgebrochen")

    oc1.behaviour = [slow]
    b1.on_post(mm.user_post("d1", "dm1", "u_alice", "Frage", NOW + 1))
    assert streaming.wait(5)
    b1.on_post(mm.user_post("d2", "dm1", "u_alice", "!help", NOW + 2))  # sofort beantwortet
    wait_for(lambda: len(mm.bot_messages("dm1")) == 2, what="Hilfe")
    # Absturz: nichts gespeichert (kein close), laufende Antwort endet ohne Abschluss
    b1._stopping.set()
    gate.set()
    b1.wait_quiet(5)
    assert read(make.path)["channels"]["dm1"] == NOW - H
    b2, _, oc2 = make(mm=mm)
    assert b2.catch_up() is True
    wait_idle(b2)
    assert prompted(oc2) == "Frage"  # !help nicht doppelt, d1 nicht verloren


def test_multipart_answer_marked_only_when_complete(make):
    """Antwort über mehrere Posts: nur der letzte Teil trägt die IDs, erst am Ende."""
    b, mm, oc = make({"dm1": NOW - H, "fam": NOW - H})
    long = "x" * 16000 + "\n" + "y" * 100
    oc.behaviour = [lambda call: long]
    b.on_post(mm.user_post("d1", "dm1", "u_alice", "Lang bitte", NOW + 1))
    wait_idle(b)
    first, last = mm.bot_messages("dm1")
    assert first["props"] == {} and last["props"] == {"captain_answers": ["d1"]}
    assert answered_in([first], "bot1") == set()  # ein Teil allein zählt nicht
