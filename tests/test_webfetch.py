"""webfetch-Allowlist: Normalisierung, Session-Regeln, Angriffsmuster.

Die Regelauswertung bildet opencode 2.0.20 nach (aus dem Binary):
``wildcard`` = Funktion ``JD`` (``\\`` im Wert → ``/``, ``*`` → ``.*``,
``?`` → ``.``, Regex mit Flag ``s``, Groß-/Kleinschreibung zählt), Regeln:
letzte passende gewinnt, ohne Treffer ``ask``; ein Tool ist unsichtbar, wenn
die letzte Regel seiner Aktion ``resource: "*"`` + ``deny`` ist. Dass opencode
wirklich so entscheidet, belegt ``tests/test_webfetch_integration.py``.
"""

from __future__ import annotations

import json
import pathlib
import re

import pytest

from captain import config, webfetch
from captain.opencode import session_permissions
from captain.sessions import environment_prompt, session_instructions

CASES = json.loads((pathlib.Path(__file__).parent / "data" / "webfetch_allow.json").read_text(encoding="utf-8"))
DIR = "/tmp/captain/ses_abc"


def wildcard(value: str, pattern: str) -> bool:
    value = value.replace("\\", "/")
    rx = re.sub(r"[.+^${}()|\[\]\\]", lambda m: "\\" + m.group(), pattern.replace("\\", "/"))
    rx = rx.replace("*", ".*").replace("?", ".")
    if rx.endswith(" .*"):
        rx = rx[:-3] + "( .*)?"
    return re.match("^" + rx + "$", value, re.DOTALL) is not None


def evaluate(rules: list[dict], action: str, resource: str) -> str:
    for rule in reversed(rules):
        if wildcard(action, rule["action"]) and wildcard(resource, rule["resource"]):
            return rule["effect"]
    return "ask"


def visible(rules: list[dict], action: str) -> bool:
    last = next((r for r in reversed(rules) if wildcard(action, r["action"])), None)
    return not (last and last["resource"] == "*" and last["effect"] == "deny")


def test_wildcard_replica():
    assert wildcard("a/b", "a/*") and wildcard("a\\b", "a/*")
    assert wildcard("abc", "a?c") and not wildcard("ABC", "abc")
    assert wildcard("x\ny", "x*y")


@pytest.mark.parametrize("case", CASES["valid"], ids=lambda c: repr(c["input"]))
def test_parse_valid(case):
    assert list(webfetch.parse(case["input"])) == case["prefixes"]


@pytest.mark.parametrize("entry", CASES["invalid"])
def test_parse_invalid(entry):
    with pytest.raises(ValueError, match="CAPTAIN_WEBFETCH_ALLOW"):
        webfetch.parse(entry)
    with pytest.raises(ValueError):  # auch zwischen gültigen Einträgen
        webfetch.parse(f"http://ok.example, {entry}")


@pytest.mark.parametrize("group", CASES["match"], ids=lambda g: g["allow"])
def test_patterns_and_attacks(group):
    prefixes = webfetch.parse(group["allow"])
    assert webfetch.allow_patterns(prefixes) == group["patterns"]
    for pattern in group["patterns"]:
        assert "?" not in pattern  # wäre ein Platzhalter für ein beliebiges Zeichen
    rules = session_permissions(DIR, prefixes)
    for url, allowed in group["cases"]:
        assert evaluate(rules, "webfetch", url) == ("allow" if allowed else "deny"), url


@pytest.mark.parametrize("admin", [
    [{"action": "webfetch", "resource": "*", "effect": "allow"}],
    [{"action": "*", "resource": "*", "effect": "allow"}],
    [{"action": "webfetch", "resource": "http://evil.com/*", "effect": "allow"}],
])
def test_admin_config_cannot_widen_allowlist(admin):
    """Global: Basis ``* * deny`` + Admin-Freigaben, danach die Session-Regeln."""
    group = CASES["match"][0]
    prefixes = webfetch.parse(group["allow"])
    rules = [{"action": "*", "resource": "*", "effect": "deny"}, *admin, *session_permissions(DIR, prefixes)]
    for url, allowed in group["cases"]:
        assert evaluate(rules, "webfetch", url) == ("allow" if allowed else "deny"), url
    assert evaluate(rules, "webfetch", "http://evil.com/x") == "deny"
    # ohne Allowlist bleibt webfetch ganz aus – auch bei Admin-Freigabe
    rules = [{"action": "*", "resource": "*", "effect": "deny"}, *admin, *session_permissions(DIR)]
    assert evaluate(rules, "webfetch", "http://text-example.org/") == "deny"
    assert not visible(rules, "webfetch")


def test_session_rules_layout():
    base = session_permissions(DIR)
    rules = session_permissions(DIR, ("http://text-example.org",))
    assert rules[: len(base)] == base  # Allowlist nur hinten angehängt
    extra = rules[len(base):]
    assert {"action": "webfetch", "resource": "*", "effect": "deny"} in base
    assert extra[:2] == [
        {"action": "webfetch", "resource": "http://text-example.org", "effect": "allow"},
        {"action": "webfetch", "resource": "http://text-example.org/*", "effect": "allow"},
    ]
    assert all(r["action"] == "webfetch" and r["effect"] == "deny" for r in extra[2:])
    assert visible(rules, "webfetch") and not visible(base, "webfetch")


def test_nothing_else_unlocked():
    rules = session_permissions(DIR, ("http://text-example.org",))
    for action in ("shell", "websearch", "execute", "subagent", "skill", "question", "external_directory",
                   "opencode_read_mcp_resource"):
        assert evaluate(rules, action, "http://text-example.org/") == "deny", action
        assert not visible(rules, action), action
    assert evaluate(rules, "read", "/etc/passwd") == "deny"


def test_config_reads_allowlist():
    env = {"MM_URL": "http://mm", "MM_BOT_TOKEN": "t", "OPENCODE_URL": "http://oc"}
    assert config.load(env=env).webfetch_allow == ()
    cfg = config.load(env={**env, "CAPTAIN_WEBFETCH_ALLOW": "HTTP://Text-Example.org/, https://b.org:443"})
    assert cfg.webfetch_allow == ("http://text-example.org", "https://b.org")
    with pytest.raises(config.ConfigError, match="CAPTAIN_WEBFETCH_ALLOW"):
        config.load(env={**env, "CAPTAIN_WEBFETCH_ALLOW": "http://text-example.org/*"})


def test_environment_prompt_mentions_allowlist():
    off = environment_prompt(DIR)
    assert "keinen Internet-Zugriff" in off and "webfetch" not in off
    on = environment_prompt(DIR, ("http://text-example.org", "https://b.org/x"))
    assert "`webfetch`" in on and "`http://text-example.org`" in on and "`https://b.org/x`" in on
    assert "keinen Internet-Zugriff" not in on and "keine Websuche" in on and "**keine** Shell" in on
    entries = session_instructions("P", DIR, ("http://text-example.org",))
    assert entries["captain-umgebung"] == environment_prompt(DIR, ("http://text-example.org",))
