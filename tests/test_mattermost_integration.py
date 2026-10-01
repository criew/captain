"""Integrationstests gegen das laufende Mattermost-Test-Setup (infra/mattermost).

Zugangsdaten aus der von ``infra/mattermost/seed.py`` erzeugten env-Datei;
Pfad per ``CAPTAIN_MM_ENV`` überschreibbar. Fehlt die Datei, wird übersprungen.
"""

import os
import threading
import time
import uuid

import httpx
import pytest

from captain.mattermost import MattermostClient, PostStreamer

pytestmark = pytest.mark.integration

ENV_PATH = os.environ.get("CAPTAIN_MM_ENV", "C:/source/captain-shared/mattermost.env")


def read_env(path):
    values = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip().strip('"').strip("'")
    return values


@pytest.fixture(scope="module")
def env():
    if not os.path.exists(ENV_PATH):
        pytest.skip(f"Mattermost-Testdaten fehlen: {ENV_PATH}")
    return read_env(ENV_PATH)


@pytest.fixture
def bot(env):
    with MattermostClient(env["MM_URL"], env["MM_BOT_TOKEN"]) as client:
        yield client


@pytest.fixture
def alice(env):
    with httpx.Client(
        base_url=env["MM_URL"].rstrip("/") + "/api/v4",
        headers={"Authorization": f"Bearer {env['MM_ALICE_TOKEN']}"},
        timeout=10,
    ) as http:
        yield http


def alice_post(alice, channel_id, message):
    resp = alice.post("/posts", json={"channel_id": channel_id, "message": message})
    resp.raise_for_status()
    return resp.json()["id"]


def test_listen_receives_dm_group_and_private(env, bot, alice):
    assert bot.me["id"] == env["MM_BOT_USER_ID"]
    marker = uuid.uuid4().hex[:8]
    got = {}
    all_three = threading.Event()

    def on_post(post):
        if marker in post.message:
            got[post.channel_id] = post
            if len(got) >= 3:
                all_three.set()

    t = threading.Thread(target=bot.listen, args=(on_post,), daemon=True)
    t.start()
    try:
        assert bot.connected.wait(10), "kein hello vom WebSocket"
        channels = {
            env["MM_DM_ALICE_BOT_ID"]: "D",
            env["MM_GROUP_DM_ID"]: "G",
            env["MM_CHANNEL_FAMILIE_ID"]: "P",
        }
        bot.create_post(env["MM_DM_ALICE_BOT_ID"], f"eigener Post {marker}")
        for channel_id in channels:
            alice_post(alice, channel_id, f"Hallo Captain {marker}")
        assert all_three.wait(10), f"nur {sorted(got)} empfangen"
    finally:
        bot.close()
        t.join(10)
    assert not t.is_alive()
    for channel_id, channel_type in channels.items():
        post = got[channel_id]
        assert post.channel_type == channel_type
        assert post.user_id == env["MM_ALICE_USER_ID"]
        assert post.sender_name == env["MM_ALICE_USERNAME"]
        assert post.message == f"Hallo Captain {marker}"
    assert all(p.user_id != env["MM_BOT_USER_ID"] for p in got.values())


def bot_posts_since(bot, channel_id, since_ms):
    resp = bot._request("GET", f"/channels/{channel_id}/posts", params={"since": since_ms})
    posts = resp.json()["posts"].values()
    return [
        p
        for p in posts
        if p["user_id"] == bot.me["id"] and p["create_at"] >= since_ms and not p.get("delete_at")
    ]


def test_post_streamer_single_post(env, bot):
    channel = env["MM_DM_ALICE_BOT_ID"]
    start = int(time.time() * 1000) - 1
    streamer = PostStreamer(bot, channel, min_interval=0.2)
    streamer.status("🔍 Recherchiere…")
    text = ""
    for word in "Das ist eine gestreamte Antwort in genau einem Post".split():
        text += word + " "
        streamer.update(text)
        time.sleep(0.05)
    final = text + "– fertig."
    streamer.finish(final)

    posts = bot_posts_since(bot, channel, start)
    assert len(posts) == 1
    assert posts[0]["id"] == streamer.post_ids[0]
    assert posts[0]["message"] == final


def test_post_streamer_splits_long_text(env, bot):
    channel = env["MM_DM_ALICE_BOT_ID"]
    start = int(time.time() * 1000) - 1
    streamer = PostStreamer(bot, channel, min_interval=0)
    streamer.update("Anfang")
    final = ("Zeile mit etwas Text\n" * 1000).rstrip()  # ~21000 Zeichen
    streamer.finish(final)

    posts = sorted(bot_posts_since(bot, channel, start), key=lambda p: p["create_at"])
    assert len(posts) == 2 == len(streamer.post_ids)
    assert "\n".join(p["message"] for p in posts) == final


def test_listen_resumes_after_disconnect(env, bot, alice):
    marker = uuid.uuid4().hex[:8]
    got = threading.Event()
    bot.backoff_initial = 0.1

    def on_post(post):
        if marker in post.message:
            got.set()

    t = threading.Thread(target=bot.listen, args=(on_post,), daemon=True)
    t.start()
    try:
        assert bot.connected.wait(10)
        first_id = bot.connection_id
        bot._ws.close()  # Abbruch simulieren
        time.sleep(0.05)
        alice_post(alice, env["MM_DM_ALICE_BOT_ID"], f"nach dem Abbruch {marker}")
        assert got.wait(15), "Post nach Reconnect nicht empfangen"
        assert bot.connected.wait(5)
        assert bot.connection_id == first_id  # Server hat die Verbindung fortgesetzt
    finally:
        bot.close()
        t.join(10)
