#!/usr/bin/env python3
"""Prueft den Seed per REST (Akzeptanz #2).

- Bot ist Mitglied in allgemein, familie, Gruppen-DM und DM alice<->Bot.
- alice postet in jeden dieser Kanaele; der Post ist per
  GET /api/v4/channels/{id}/posts mit dem Bot-Token lesbar.

Aufruf:  python infra/mattermost/verify.py [--env infra/mattermost/generated.env]
"""

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from seed import ApiError, Client, read_env  # noqa: E402

CHANNEL_KEYS = ["MM_CHANNEL_ALLGEMEIN_ID", "MM_CHANNEL_FAMILIE_ID",
                "MM_GROUP_DM_ID", "MM_DM_ALICE_BOT_ID"]


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--env", default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                  "generated.env"))
    env = read_env(ap.parse_args().env)
    c = Client(env["MM_URL"])
    bot, alice = env["MM_BOT_TOKEN"], env["MM_ALICE_TOKEN"]

    me = c.get("/users/me", token=bot)
    assert me["id"] == env["MM_BOT_USER_ID"] and me.get("is_bot"), "Bot-Token gehoert nicht zum Bot"
    print(f"Bot-Token ok: @{me['username']} ({me['id']})")

    failed = False
    for key in CHANNEL_KEYS:
        cid = env[key]
        try:
            ch = c.get(f"/channels/{cid}", token=bot)
            member = c.get(f"/channels/{cid}/members/{env['MM_BOT_USER_ID']}", token=bot)
            text = f"verify {key} {time.time():.0f}"
            post = c.post("/posts", {"channel_id": cid, "message": text}, token=alice)
            posts = c.get(f"/channels/{cid}/posts?per_page=20", token=bot)["posts"]
            seen = post["id"] in posts and posts[post["id"]]["user_id"] == env["MM_ALICE_USER_ID"]
            print(f"{key:26} type={ch['type']} bot_member={member['user_id'] == me['id']} "
                  f"alice_post_lesbar={seen}")
            failed |= not seen
        except ApiError as e:
            print(f"{key:26} FEHLER {e}")
            failed = True
    print("ERGEBNIS:", "FEHLGESCHLAGEN" if failed else "OK")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
