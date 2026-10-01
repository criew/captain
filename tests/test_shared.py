"""Geteiltes Verzeichnis (``CAPTAIN_SHARED_DIR``): Pfadprüfung, Session-Regeln, Hinweis.

Die Regelauswertung bildet opencode 2.0.20 nach (wie in ``test_webfetch.py``):
letzte passende Regel gewinnt, ``*`` passt auch auf ``/``, Groß-/Kleinschreibung
zählt. Pfade außerhalb des Session-Verzeichnisses prüft opencode zuerst mit
``external_directory`` = ``<lexikalisch aufgelöstes Verzeichnis>/*``, danach
``read`` mit dem absoluten Pfad. Dass opencode wirklich so entscheidet, belegt
``tests/test_shared_integration.py``.
"""

from __future__ import annotations

import json
import pathlib
import posixpath

import pytest

from captain import config, shared
from captain.opencode import session_permissions
from captain.sessions import environment_prompt, session_instructions
from test_webfetch import evaluate, visible

CASES = json.loads((pathlib.Path(__file__).parent / "data" / "shared_dir.json").read_text(encoding="utf-8"))
DIR = "/tmp/captain/ses_abc"
BASE = [{"action": "*", "resource": "*", "effect": "deny"}]


def access(rules: list[dict], path: str, is_dir: bool = False) -> str:
    """Wie opencode ``read`` prüft: relativ im Session-Verzeichnis, sonst erst external_directory."""
    absolute = posixpath.normpath(posixpath.join(DIR, path))
    if absolute == DIR or absolute.startswith(DIR + "/"):
        return evaluate(rules, "read", posixpath.relpath(absolute, DIR))
    parent = absolute if is_dir else posixpath.dirname(absolute)
    if evaluate(rules, "external_directory", posixpath.join(parent, "*")) != "allow":
        return "deny"
    return evaluate(rules, "read", absolute)


@pytest.mark.parametrize("case", CASES["valid"], ids=lambda c: repr(c["input"]))
def test_normalize_valid(case):
    assert shared.normalize(case["input"]) == case["path"]


@pytest.mark.parametrize("value", CASES["invalid"])
def test_normalize_invalid(value):
    with pytest.raises(ValueError, match="CAPTAIN_SHARED_DIR"):
        shared.normalize(value)


def test_config():
    env = {"MM_URL": "http://mm", "MM_BOT_TOKEN": "t", "OPENCODE_URL": "http://oc"}
    cfg = config.load(env=env)
    assert cfg.shared_dir is None and not cfg.shared
    cfg = config.load(env={**env, "CAPTAIN_SHARED_DIR": "/srv/captain-shared/"})
    assert cfg.shared_dir == "/srv/captain-shared" and cfg.shared
    for bad in ("relativ", "/", "/etc", "/srv/../etc"):
        with pytest.raises(config.ConfigError, match="CAPTAIN_SHARED_DIR"):
            config.load(env={**env, "CAPTAIN_SHARED_DIR": bad})


@pytest.mark.parametrize("admin", [
    [],
    [{"action": "*", "resource": "*", "effect": "allow"}],
    [{"action": "external_directory", "resource": "*", "effect": "allow"},
     {"action": "read", "resource": "*", "effect": "allow"}, {"action": "edit", "resource": "*", "effect": "allow"}],
])
def test_rules_read_only_below_shared(admin):
    """Global: Basis + Admin-Freigaben, danach die Session-Regeln – Admin-Config ändert nichts."""
    rules = [*BASE, *admin, *session_permissions(DIR, (), True)]
    for path, is_dir in (("/shared", True), ("/shared/", True), ("/shared/a.txt", False),
                         ("/shared/sub/dir/b.md", False), ("/shared/mit leer.txt", False),
                         ("../../../shared/a.txt", False), ("/shared/sub/../a.txt", False),
                         ("/shared/repo/.github/x.yml", False), ("/shared/a.git/x", False),
                         ("/shared/.gitignore", False), ("notiz.txt", False)):
        assert access(rules, path, is_dir) == "allow", path
    for path, is_dir in (("/shared/../etc/passwd", False), ("/sharedX/a.txt", False), ("/shared-x/a", False),
                         ("/SHARED/a.txt", False), ("/Shared/a.txt", False), ("/etc/passwd", False),
                         ("/tmp/captain/ses_other/x.txt", False), ("/shared/../tmp/captain/ses_other/x", False),
                         ("/shared/sub/../../tmp/captain/ses_other/x", False), ("/", True), ("/tmp", True),
                         ("/root/.local/share/opencode/opencode.db", False),
                         ("/shared/.git/config", False), ("/shared/.git", True),
                         ("/shared/repo/.git/config", False), ("/shared/repo/.git", True),
                         ("/shared/a/b/c/.git/objects/x", False), ("/shared/repo/.git", False)):
        assert access(rules, path, is_dir) == "deny", path
    # schreiben nie: edit prüft den absoluten Pfad (nach external_directory)
    for path in ("/shared", "/shared/a.txt", "/shared/sub/neu.md"):
        assert evaluate(rules, "edit", path) == "deny", path
    assert evaluate(rules, "edit", "notiz.txt") == "allow"
    # glob/grep: Muster frei, Suchpfad über external_directory
    assert evaluate(rules, "glob", "**/*.md") == "allow" and evaluate(rules, "grep", "x") == "allow"
    assert evaluate(rules, "external_directory", "/shared/*") == "allow"
    assert evaluate(rules, "external_directory", "/shared/repo/.git/*") == "deny"
    assert evaluate(rules, "external_directory", "/tmp/captain/*") == "deny"


def test_without_shared_nothing_changes():
    assert shared.session_rules(False) == []
    assert session_permissions(DIR, (), False) == session_permissions(DIR)
    rules = [*BASE, *session_permissions(DIR)]
    assert access(rules, "/shared/a.txt") == "deny" and access(rules, "/shared", True) == "deny"


def test_rules_appended_last_and_nothing_else_unlocked():
    off = session_permissions(DIR, ("http://text-example.org",))
    on = session_permissions(DIR, ("http://text-example.org",), True)
    assert on[: len(off)] == off
    extra = on[len(off):]
    assert extra == shared.session_rules(True)
    assert all(r["action"] in ("external_directory", "read", "edit") for r in extra)
    assert all(r["resource"].startswith("/shared") for r in extra)
    assert [r for r in extra if r["effect"] == "allow"] == [
        {"action": "external_directory", "resource": "/shared/*", "effect": "allow"},
        {"action": "read", "resource": "/shared", "effect": "allow"},
        {"action": "read", "resource": "/shared/*", "effect": "allow"},
    ]
    for action in ("shell", "websearch", "execute", "subagent", "skill", "question", "opencode_read_mcp_resource"):
        assert evaluate(on, action, "/shared/a.txt") == "deny", action
        assert not visible(on, action), action
    assert visible(on, "read") and visible(on, "glob") and visible(on, "grep")


def test_environment_prompt():
    off = environment_prompt(DIR)
    assert "/shared" not in off
    on = environment_prompt(DIR, (), True)
    assert "`/shared`" in on and "**nur lesen und suchen**" in on and "PDF" in on
    assert "Zugriffe außerhalb werden abgelehnt – einzige Ausnahme ist `/shared`" in on
    entries = session_instructions("P", DIR, (), True)
    assert entries["captain-umgebung"] == on
