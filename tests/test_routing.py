"""Unit-Tests für captain.routing (reine Entscheidungslogik)."""

from __future__ import annotations

import pytest

from captain.mattermost import Post
from captain.routing import (
    channel_history,
    conversation_key,
    is_mention,
    reply_root,
    settings_key,
    should_answer,
    strip_mention,
)

BOT = dict(bot_id="bot1", bot_username="captain")


def p(text="hallo", *, ctype="O", root="", user="u1", from_bot=False, pid="p1"):
    return Post(pid, "c1", ctype, root, user, "alice", text, [], from_bot)


@pytest.mark.parametrize("text, hit", [
    ("@captain hilf mir", True),
    ("Hey @Captain, was meinst du?", True),
    ("frag mal @CAPTAIN.", True),
    ("(@captain)", True),
    ("@captain-bot hallo", False),
    ("@captain.x hallo", False),
    ("@captainhook", False),
    ("mail@captain.de", False),
    ("captain ohne at", False),
    ("schreib `@captain !help` in den Kanal", False),  # Code zählt nicht
    ("```\n@captain\n```", False),
    ("`code` und @captain", True),
])
def test_is_mention_text(text, hit):
    assert is_mention(p(text), **BOT) is hit


def test_group_broadcast_is_no_mention():
    assert not is_mention(p("@channel Essen ist fertig"), **BOT)
    assert not should_answer(p("@all hallo", ctype="G"), **BOT)


def test_strip_mention():
    assert strip_mention("@captain !help", "captain") == "!help"
    assert strip_mention("@Captain, wie spät?", "captain") == "wie spät?"
    assert strip_mention("@captain: @captain hallo", "captain") == "hallo"
    assert strip_mention("Frag @captain.", "captain") == "Frag Captain."
    assert strip_mention("Was meinst du, @captain?", "captain") == "Was meinst du, Captain?"
    assert strip_mention("  normal  ", "captain") == "normal"
    assert strip_mention("`@captain` bleibt", "captain") == "`@captain` bleibt"
    # Anderer Bot-Name: Erwähnung mitten im Satz wird zum Anzeigenamen
    assert strip_mention("Frag @superman mal.", "superman", "CaptainSuperman") == "Frag CaptainSuperman mal."
    assert strip_mention("@superman, wie spät?", "superman", "CaptainSuperman") == "wie spät?"


CODE = "def f():\n    return  1\n\n| a  |  b |\n|----|----|\n\tx"


def test_strip_mention_keeps_whitespace():
    assert strip_mention(CODE, "captain") == CODE  # ohne Erwähnung unverändert
    assert strip_mention("@captain " + CODE, "captain") == CODE
    assert strip_mention("Schau @captain:\n" + CODE, "captain") == "Schau Captain:\n" + CODE


@pytest.mark.parametrize("post, in_thread, expected", [
    # DM: immer
    (p("hallo", ctype="D"), False, True),
    (p("im DM-Thread", ctype="D", root="r1"), False, True),
    # Kanal/Gruppe Top-Level: nur mit Erwähnung
    (p("hallo", ctype="O"), False, False),
    (p("hallo", ctype="P"), False, False),
    (p("hallo", ctype="G"), False, False),
    (p("@captain hallo", ctype="O"), False, True),
    (p("@captain hallo", ctype="G"), False, True),
    (p("!help", ctype="O"), False, False),
    (p("@captain !help", ctype="P"), False, True),
    # Top-Level: in_thread spielt keine Rolle
    (p("hallo", ctype="O"), True, False),
    # Thread: mit Captain-Beteiligung ohne Erwähnung, sonst nur mit
    (p("weiter", ctype="O", root="r1"), True, True),
    (p("!neu", ctype="G", root="r1"), True, True),
    (p("weiter", ctype="O", root="r1"), False, False),
    (p("@captain weiter", ctype="O", root="r1"), False, True),
    # nie: eigene Posts, Bots/Webhooks
    (p("@captain", ctype="O", user="bot1"), False, False),
    (p("hallo", ctype="D", from_bot=True), False, False),
    (p("@captain", ctype="O", root="r1", from_bot=True), True, False),
])
def test_should_answer(post, in_thread, expected):
    assert should_answer(post, **BOT, in_thread=in_thread) is expected


def test_reply_root_and_keys():
    dm = p(ctype="D", pid="d1")
    assert reply_root(dm) is None
    assert conversation_key(dm) == "dm:c1"
    assert settings_key(dm) == "dm:c1"
    dm_thread = p(ctype="D", root="r0")
    assert reply_root(dm_thread) == "r0" and conversation_key(dm_thread) == "th:r0"

    top = p("@captain", ctype="O", pid="t1")
    assert reply_root(top) == "t1"  # Thread unter dem erwähnenden Post
    assert conversation_key(top) == "th:t1"
    assert settings_key(top) == "ch:c1"  # !modell oben gilt für den Kanal

    reply = p("weiter", ctype="G", root="t1", pid="t2")
    assert reply_root(reply) == "t1"
    assert conversation_key(reply) == "th:t1"
    assert settings_key(reply) == "th:t1"


# ---------------------------------------------------------------- Vorgeschichte


def rp(pid, at, user="u1", msg=None, root="", **extra):
    return {"id": pid, "create_at": at, "user_id": user, "root_id": root,
            "message": msg if msg is not None else f"m{pid}", **extra}


def ids(posts):
    return [x["id"] for x in posts]


def test_history_without_participation_takes_recent_top_level():
    posts = [rp("a", 1), rp("b", 2), rp("r", 3, root="a"), rp("c", 4), rp("now", 5)]
    got = channel_history(posts, bot_id="bot1", before=5, exclude={"now"})
    assert ids(got) == ["a", "b", "c"]  # nur Top-Level, vor dem Post


def test_history_since_last_participation():
    posts = [
        rp("old", 1), rp("t1", 2), rp("x", 3),
        rp("b1", 4, user="bot1", root="t1"),  # Captain antwortet im Thread t1
        rp("y", 5), rp("t1r", 6, root="t1"),
        rp("b2", 7, user="bot1", root="t1"),  # später nochmal in t1
        rp("z", 8), rp("now", 9),
    ]
    got = channel_history(posts, bot_id="bot1", before=9)
    # t1 (inkl. Kanal davor) kannte Captain schon → erst danach
    assert ids(got) == ["x", "y", "z"]


def test_history_uses_newest_root_not_newest_reply():
    posts = [
        rp("t1", 1), rp("t2", 3), rp("x", 4),
        rp("b2", 5, user="bot1", root="t2"),
        rp("b1", 6, user="bot1", root="t1"),  # jüngste Antwort, aber alter Root
        rp("y", 7),
    ]
    assert ids(channel_history(posts, bot_id="bot1", before=100)) == ["x", "y"]


def test_history_bot_top_level_post_counts_and_root_outside_window():
    posts = [rp("a", 1), rp("b", 2, user="bot1"), rp("c", 3),
             rp("d", 4, user="bot1", root="weit-weg"), rp("e", 5)]
    assert ids(channel_history(posts, bot_id="bot1", before=100)) == ["c", "e"]


def test_history_filters_bots_system_empty_deleted():
    posts = [
        rp("a", 1, user="robot"),
        rp("b", 2, props={"from_webhook": "true"}),
        rp("c", 3, type="system_join_channel"),
        rp("d", 4, msg="   "),
        rp("e", 5, delete_at=9),
        rp("f", 6),
    ]
    got = channel_history(posts, bot_id="bot1", before=100, is_bot=lambda u: u == "robot")
    assert ids(got) == ["f"]


def test_history_caps_newest_win():
    posts = [rp(str(i), i, msg="x" * 10) for i in range(1, 101)]
    got = channel_history(posts, bot_id="bot1", before=1000)
    assert len(got) == 50 and got[-1]["id"] == "100" and got[0]["id"] == "51"
    # Budget zählt „Name: Text\n“: 3 + 2 + 10 + 1 = 16 je Zeile
    got = channel_history(posts, bot_id="bot1", before=1000, max_chars=48,
                          name_of=lambda uid: "Bob")
    assert ids(got) == ["98", "99", "100"] and got[0]["name"] == "Bob"
    assert len(channel_history(posts, bot_id="bot1", before=1000, max_chars=47,
                               name_of=lambda uid: "Bob")) == 2
    long = [rp("a", 1, msg="kurz"), rp("b", 2, msg="y" * 5000)]
    got = channel_history(long, bot_id="bot1", before=10, max_chars=8000, post_chars=2000)
    assert got[-1]["message"] == "y" * 2000 + " […]" and ids(got) == ["a", "b"]
