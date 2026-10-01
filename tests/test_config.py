import json

import pytest

from captain import config

BASE = {
    "MM_URL": "http://localhost:8065/",
    "MM_BOT_TOKEN": "tok",
    "OPENCODE_URL": "http://localhost:4096",
}


def test_env_only_with_defaults():
    cfg = config.load(env=BASE)
    assert cfg.mm_url == "http://localhost:8065"
    assert cfg.mm_bot_token == "tok"
    assert cfg.opencode_url == "http://localhost:4096"
    assert cfg.opencode_password is None
    assert cfg.opencode_model is None
    assert cfg.opencode_variant is None
    assert cfg.sessions_dir == "/tmp/captain"
    assert cfg.system_prompt == config.DEFAULT_SYSTEM_PROMPT
    assert cfg.data_dir == "data"
    assert cfg.allowed_users == frozenset()
    assert cfg.history_max_posts == 50
    assert cfg.history_max_chars == 8000


def test_history_limits():
    cfg = config.load(env={**BASE, "HISTORY_MAX_POSTS": " 10 ", "HISTORY_MAX_CHARS": "0"})
    assert (cfg.history_max_posts, cfg.history_max_chars) == (10, 0)


@pytest.mark.parametrize("key,value", [
    ("HISTORY_MAX_POSTS", "viele"), ("HISTORY_MAX_CHARS", "-1"), ("HISTORY_MAX_POSTS", "1.5"),
])
def test_history_limits_invalid(key, value):
    with pytest.raises(config.ConfigError, match=key):
        config.load(env={**BASE, key: value})


def test_history_limits_from_json(tmp_path):
    path = tmp_path / "c.json"
    path.write_text(json.dumps({**BASE, "HISTORY_MAX_POSTS": 5}))
    assert config.load(str(path), env={}).history_max_posts == 5


def test_missing_required_values():
    with pytest.raises(config.ConfigError) as e:
        config.load(env={"MM_URL": "x"})
    assert "MM_BOT_TOKEN" in str(e.value) and "OPENCODE_URL" in str(e.value)


def test_json_file_and_env_override(tmp_path):
    path = tmp_path / "captain.json"
    path.write_text(json.dumps({
        **BASE,
        "OPENCODE_MODEL": "anthropic/x",
        "SESSIONS_DIR": "/ws/",
        "ALLOWED_USERS": ["Alice", "@bob"],
    }))
    cfg = config.load(env={"CAPTAIN_CONFIG": str(path), "OPENCODE_MODEL": "openai/y", "MM_BOT_TOKEN": ""})
    assert cfg.opencode_model == "openai/y"  # Umgebung gewinnt
    assert cfg.mm_bot_token == "tok"  # leere Umgebung überschreibt nicht
    assert cfg.sessions_dir == "/ws"
    assert cfg.allowed_users == {"alice", "bob"}


def test_explicit_path_argument(tmp_path):
    path = tmp_path / "c.json"
    path.write_text(json.dumps(BASE))
    assert config.load(str(path), env={}).mm_bot_token == "tok"


def test_allowed_users_from_env():
    cfg = config.load(env={**BASE, "ALLOWED_USERS": " alice, Bob ,,"})
    assert cfg.allowed_users == {"alice", "bob"}
    assert cfg.is_allowed("ALICE")
    assert not cfg.is_allowed("carol")


def test_empty_allowed_users_allows_everyone():
    assert config.load(env=BASE).is_allowed("anyone")


@pytest.mark.parametrize("content, msg", [
    ("{kaputt", "kein gültiges JSON"),
    ("[1, 2]", "JSON-Objekt"),
    ('{"FOO": 1}', "Unbekannte"),
])
def test_invalid_file(tmp_path, content, msg):
    path = tmp_path / "c.json"
    path.write_text(content)
    with pytest.raises(config.ConfigError, match=msg):
        config.load(str(path), env=BASE)


def test_missing_file(tmp_path):
    with pytest.raises(config.ConfigError, match="fehlt"):
        config.load(str(tmp_path / "nope.json"), env=BASE)


def test_opencode_server_password_fallback():
    base = {"MM_URL": "http://mm", "MM_BOT_TOKEN": "t", "OPENCODE_URL": "http://oc"}
    cfg = config.load(env={**base, "OPENCODE_SERVER_PASSWORD": "srv"})
    assert cfg.opencode_password == "srv"
    cfg = config.load(env={**base, "OPENCODE_SERVER_PASSWORD": "srv", "OPENCODE_PASSWORD": "pw"})
    assert cfg.opencode_password == "pw"


def test_system_prompt_from_env():
    cfg = config.load(env={**BASE, "CAPTAIN_SYSTEM_PROMPT": "  Du bist CaptainSuperman.  "})
    assert cfg.system_prompt == "Du bist CaptainSuperman."


def test_system_prompt_file_wins(tmp_path):
    path = tmp_path / "persona.txt"
    path.write_text("Du bist CaptainSuperman,\nlustig.\n", encoding="utf-8")
    cfg = config.load(env={
        **BASE, "CAPTAIN_SYSTEM_PROMPT": "Env", "CAPTAIN_SYSTEM_PROMPT_FILE": str(path),
    })
    assert cfg.system_prompt == "Du bist CaptainSuperman,\nlustig."


def test_system_prompt_file_missing(tmp_path):
    with pytest.raises(config.ConfigError):
        config.load(env={**BASE, "CAPTAIN_SYSTEM_PROMPT_FILE": str(tmp_path / "fehlt.txt")})


@pytest.mark.parametrize("value", ["/", "relativ/pfad", "data", "/tmp/..", "C:/", "//"])
def test_sessions_dir_rejected(value):
    with pytest.raises(config.ConfigError):
        config.load(env={**BASE, "SESSIONS_DIR": value})


def test_sessions_dir_normalized():
    assert config.load(env={**BASE, "SESSIONS_DIR": "/tmp/captain//"}).sessions_dir == "/tmp/captain"


def test_catchup_max_age():
    assert config.load(env=BASE).catchup_max_age == 24 * 3600
    assert config.load(env={**BASE, "CATCHUP_MAX_AGE": "90m"}).catchup_max_age == 5400
    assert config.load(env={**BASE, "CATCHUP_MAX_AGE": "3600"}).catchup_max_age == 3600
    assert config.parse_duration("2d") == 2 * 86400
    assert config.parse_duration(1.5) == 1.5
    with pytest.raises(config.ConfigError):
        config.load(env={**BASE, "CATCHUP_MAX_AGE": "einen Tag"})


def test_catchup_max_age_zero_and_negative():
    assert config.load(env={**BASE, "CATCHUP_MAX_AGE": "0"}).catchup_max_age == 0
    for bad in ("-5", "-1h"):
        with pytest.raises(config.ConfigError):
            config.load(env={**BASE, "CATCHUP_MAX_AGE": bad})
    with pytest.raises(config.ConfigError):
        config.parse_duration(-5)


def test_max_attachment_mb_default_and_values():
    cfg = config.load(env=BASE)
    assert cfg.max_attachment_mb == 20
    assert cfg.max_attachment_bytes == 20 * 1024 * 1024
    assert config.load(env={**BASE, "MAX_ATTACHMENT_MB": "0,5"}).max_attachment_bytes == 512 * 1024
    assert config.load(env={**BASE, "MAX_ATTACHMENT_MB": "0"}).max_attachment_bytes is None


@pytest.mark.parametrize("value", ["-1", "zwanzig", "inf", "nan"])
def test_max_attachment_mb_invalid(value):
    with pytest.raises(config.ConfigError, match="MAX_ATTACHMENT_MB"):
        config.load(env={**BASE, "MAX_ATTACHMENT_MB": value})
