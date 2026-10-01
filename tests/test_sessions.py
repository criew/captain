import json
import re
import threading

import pytest

from captain import sessions
from captain.sessions import SessionStore, channel_lock, session_key


@pytest.fixture
def path(tmp_path):
    return str(tmp_path / "sub" / "sessions.json")


def test_session_key():
    assert session_key("D", "c1") == "dm:c1"
    assert session_key("G", "c1") == "ch:c1"
    assert session_key("O", "c1") == "ch:c1"
    assert session_key("P", "c1", "") == "ch:c1"
    assert session_key("P", "c1", "r1") == "th:r1"
    assert session_key("D", "c1", "r1") == "th:r1"


def test_get_missing(path):
    assert SessionStore(path).get("dm:x") is None


def test_set_get_persist(path):
    store = SessionStore(path)
    entry = store.set("dm:a", "ses_1", "/tmp/captain/ses_1")
    assert entry == {"opencode_session_id": "ses_1", "directory": "/tmp/captain/ses_1"}
    assert SessionStore(path).get("dm:a") == entry
    with open(path) as f:
        assert json.load(f) == {"dm:a": entry}


def test_get_returns_copy(path):
    store = SessionStore(path)
    store.set("dm:a", "ses_1", "/w")
    store.get("dm:a")["opencode_session_id"] = "manipuliert"
    assert store.get("dm:a")["opencode_session_id"] == "ses_1"


def test_settings_survive_set_and_reset(path):
    store = SessionStore(path)
    store.set_setting("ch:a", model="anthropic/m", variant="high")
    store.set("ch:a", "ses_1", "/w")
    assert store.get("ch:a") == {
        "opencode_session_id": "ses_1", "directory": "/w",
        "model": "anthropic/m", "variant": "high",
    }
    store.reset("ch:a")
    assert SessionStore(path).get("ch:a") == {"model": "anthropic/m", "variant": "high"}


def test_reset_without_settings_removes_entry(path):
    store = SessionStore(path)
    store.set("th:r", "ses_1", "/w")
    store.reset("th:r")
    store.reset("th:unbekannt")
    assert store.get("th:r") is None
    assert SessionStore(path).get("th:r") is None


def test_set_setting_none_removes(path):
    store = SessionStore(path)
    store.set_setting("dm:a", model="m", variant="v")
    assert store.set_setting("dm:a", variant=None) == {"model": "m"}
    assert store.set_setting("dm:a", model=None) == {}
    assert store.get("dm:a") is None


def test_set_setting_rejects_unknown(path):
    with pytest.raises(ValueError):
        SessionStore(path).set_setting("dm:a", effort="max")


def test_corrupt_file_is_moved_aside(path, tmp_path):
    (tmp_path / "sub").mkdir()
    with open(path, "w") as f:
        f.write("{kaputt")
    store = SessionStore(path)
    assert store.get("dm:a") is None
    with open(path + ".corrupt") as f:
        assert f.read() == "{kaputt"


def test_no_temp_files_left(path, tmp_path):
    store = SessionStore(path)
    store.set("dm:a", "s", "/w")
    store.set_setting("dm:a", model="m")
    assert [p.name for p in (tmp_path / "sub").iterdir()] == ["sessions.json"]


def test_failed_write_keeps_old_file(path, monkeypatch):
    store = SessionStore(path)
    store.set("dm:a", "s1", "/w")

    def boom(*_):
        raise OSError("disk voll")

    monkeypatch.setattr(sessions.os, "replace", boom)
    with pytest.raises(OSError):
        store.set("dm:a", "s2", "/w")
    monkeypatch.undo()
    assert SessionStore(path).get("dm:a")["opencode_session_id"] == "s1"


def test_concurrent_writes(path):
    store = SessionStore(path)
    threads = [
        threading.Thread(target=store.set, args=(f"dm:{i}", f"s{i}", "/w"))
        for i in range(30)
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    reloaded = SessionStore(path)
    assert all(reloaded.get(f"dm:{i}")["opencode_session_id"] == f"s{i}" for i in range(30))


def test_channel_lock_same_key_same_lock():
    assert channel_lock("dm:a") is channel_lock("dm:a")
    assert channel_lock("dm:a") is not channel_lock("dm:b")


def test_channel_lock_serializes():
    lock = channel_lock("ch:serial")
    active, peak = 0, 0
    guard = threading.Lock()

    def work():
        nonlocal active, peak
        with lock:
            with guard:
                active += 1
                peak = max(peak, active)
            threading.Event().wait(0.01)
            with guard:
                active -= 1

    threads = [threading.Thread(target=work) for _ in range(5)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert peak == 1


def test_failed_write_keeps_memory_state(path, monkeypatch):
    store = SessionStore(path)
    store.set("dm:a", "s1", "/w")
    store.set_setting("dm:a", model="m")

    def boom(*_):
        raise OSError("disk voll")

    monkeypatch.setattr(sessions.os, "replace", boom)
    with pytest.raises(OSError):
        store.set("dm:a", "s2", "/w")
    with pytest.raises(OSError):
        store.set_setting("dm:a", model=None)
    with pytest.raises(OSError):
        store.reset("dm:a")
    with pytest.raises(OSError):
        store.set("dm:neu", "s3", "/w")
    assert store.get("dm:a") == {"opencode_session_id": "s1", "directory": "/w", "model": "m"}
    assert store.get("dm:neu") is None


def test_new_session_id():
    a, b = sessions.new_session_id(), sessions.new_session_id()
    assert a != b
    assert re.fullmatch(r"ses_[0-9a-f]{32}", a)


def test_session_instructions_order_and_path():
    entries = sessions.session_instructions("  Du bist X.  ", "/tmp/captain/ses_1")
    assert list(entries) == ["captain-persona", "captain-umgebung"]
    assert entries["captain-persona"] == "Du bist X."
    env = entries["captain-umgebung"]
    assert "`/tmp/captain/ses_1`" in env and "Shell" in env
    assert list(sessions.session_instructions("", "/d")) == ["captain-umgebung"]


def test_channel_lock_is_released_when_unused():
    import gc

    lock = channel_lock("th:kurzlebig")
    assert "th:kurzlebig" in sessions._channel_locks
    del lock
    gc.collect()
    assert "th:kurzlebig" not in sessions._channel_locks
