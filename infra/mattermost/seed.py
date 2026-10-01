#!/usr/bin/env python3
"""Seed fuer die lokale Mattermost-Testumgebung (idempotent, nur REST, stdlib).

Legt Admin, Testnutzer, Team, Kanaele, Gruppen-DM, DM und den Bot `captain`
an und schreibt IDs/Tokens nach infra/mattermost/generated.env.

Mehrfach ausfuehrbar: Vorhandenes wird wiederverwendet, gueltige Tokens aus
einer bestehenden generated.env werden weiterbenutzt statt neu erzeugt.

Aufruf (Host, Git Bash):  python infra/mattermost/seed.py [--url URL] [--out DATEI]
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))

# Nur Testdaten! Passwoerter sind absichtlich dokumentiert (README.md).
ADMIN = {"username": "admin", "password": "Admin-Test-1234", "email": "admin@captain.test",
         "first_name": "Admin", "last_name": "Captain"}
USERS = [
    {"username": "alice", "password": "Alice-Test-1234", "email": "alice@captain.test",
     "first_name": "Alice", "last_name": "Test"},
    {"username": "bob", "password": "Bob-Test-1234", "email": "bob@captain.test",
     "first_name": "Bob", "last_name": "Test"},
    {"username": "carol", "password": "Carol-Test-1234", "email": "carol@captain.test",
     "first_name": "Carol", "last_name": "Test"},
]
TEAM = {"name": "captain", "display_name": "Captain", "type": "O"}
BOT = {"username": "captain", "display_name": "Captain",
       "description": "Captain - Mattermost <-> opencode (Test)"}
CHANNELS = [
    # name, display_name, type, mitglieder (Nutzernamen; Bot kommt immer dazu)
    ("allgemein", "Allgemein", "O", ["admin", "alice", "bob", "carol"]),
    ("familie", "Familie", "P", ["alice", "bob"]),
]


class ApiError(Exception):
    def __init__(self, status, body):
        super().__init__(f"HTTP {status}: {body}")
        self.status = status
        self.body = body


class Client:
    def __init__(self, url, token=None):
        self.url = url.rstrip("/")
        self.token = token

    def call(self, method, path, data=None, token=None, raw=False):
        tok = token if token is not None else self.token
        body = None if data is None else json.dumps(data).encode()
        req = urllib.request.Request(self.url + "/api/v4" + path, data=body, method=method)
        req.add_header("Content-Type", "application/json")
        req.add_header("X-Requested-With", "XMLHttpRequest")
        if tok:
            req.add_header("Authorization", "Bearer " + tok)
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                payload = resp.read()
                if raw:
                    return resp, payload
                return json.loads(payload) if payload else None
        except urllib.error.HTTPError as e:
            raise ApiError(e.code, e.read().decode(errors="replace")) from None

    def get(self, path, **kw):
        return self.call("GET", path, **kw)

    def post(self, path, data=None, **kw):
        return self.call("POST", path, data if data is not None else {}, **kw)

    def put(self, path, data=None, **kw):
        return self.call("PUT", path, data if data is not None else {}, **kw)


def log(msg):
    print(msg, flush=True)


def wait_for_server(c, timeout=300):
    deadline = time.time() + timeout
    while True:
        try:
            r = c.get("/system/ping", token="")
            if r and r.get("status") == "OK":
                return
        except (ApiError, urllib.error.URLError, ConnectionError, OSError):
            pass
        if time.time() > deadline:
            sys.exit(f"Mattermost unter {c.url} nicht erreichbar (Timeout {timeout}s).")
        log("  warte auf Mattermost ...")
        time.sleep(3)


def login(c, username, password):
    resp, _ = c.call("POST", "/users/login", {"login_id": username, "password": password},
                     token="", raw=True)
    return resp.headers["Token"]


def read_env(path):
    env = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    env[k] = v
    return env


def token_valid(c, token, user_id):
    if not token:
        return False
    try:
        return c.get("/users/me", token=token)["id"] == user_id
    except ApiError:
        return False


def ensure_admin(c):
    try:
        return login(c, ADMIN["username"], ADMIN["password"])
    except ApiError as e:
        if e.status != 401:
            raise
    # Erster Nutzer eines frischen Servers wird automatisch System-Admin.
    log("  lege Admin an")
    try:
        c.post("/users", ADMIN, token="")
    except ApiError as e:
        sys.exit("Admin konnte weder angemeldet noch angelegt werden "
                 f"(Server nicht frisch? Passwort geaendert?): {e}")
    return login(c, ADMIN["username"], ADMIN["password"])


def ensure_user(c, spec):
    try:
        user = c.get(f"/users/username/{spec['username']}")
    except ApiError as e:
        if e.status != 404:
            raise
        log(f"  lege Nutzer {spec['username']} an")
        user = c.post("/users", spec)
    # Passwort auf den dokumentierten Stand bringen (falls manuell geaendert)
    c.put(f"/users/{user['id']}/password", {"new_password": spec["password"]})
    return user


def ensure_bot(c):
    try:
        user = c.get(f"/users/username/{BOT['username']}")
    except ApiError as e:
        if e.status != 404:
            raise
        log("  lege Bot captain an")
        return c.post("/bots", BOT)["user_id"]
    if not user.get("is_bot"):
        sys.exit(f"Nutzer {BOT['username']} existiert, ist aber kein Bot.")
    bot = c.get(f"/bots/{user['id']}?include_deleted=true")
    if bot.get("delete_at"):
        log("  aktiviere Bot captain wieder")
        c.post(f"/bots/{user['id']}/enable")
    c.put(f"/bots/{user['id']}", {"username": BOT["username"],
                                  "display_name": BOT["display_name"],
                                  "description": BOT["description"]})
    return user["id"]


def ensure_team(c):
    try:
        return c.get(f"/teams/name/{TEAM['name']}")
    except ApiError as e:
        if e.status != 404:
            raise
    log(f"  lege Team {TEAM['name']} an")
    return c.post("/teams", TEAM)


def ensure_team_member(c, team_id, user_id):
    c.post(f"/teams/{team_id}/members", {"team_id": team_id, "user_id": user_id})


def ensure_channel(c, team_id, name, display, ctype):
    try:
        return c.get(f"/teams/{team_id}/channels/name/{name}?include_deleted=true")
    except ApiError as e:
        if e.status != 404:
            raise
    log(f"  lege Kanal {name} an")
    return c.post("/channels", {"team_id": team_id, "name": name,
                                "display_name": display, "type": ctype})


def ensure_channel_member(c, channel_id, user_id):
    members = c.get(f"/channels/{channel_id}/members?per_page=200")
    if any(m["user_id"] == user_id for m in members):
        return
    c.post(f"/channels/{channel_id}/members", {"user_id": user_id})


def ensure_pat(c, user_id, existing, description):
    """Personal Access Token: vorhandenes gueltiges wiederverwenden, sonst neu."""
    if token_valid(c, existing, user_id):
        return existing
    log(f"  erzeuge Token fuer {user_id} ({description})")
    return c.post(f"/users/{user_id}/tokens", {"description": description})["token"]


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--url", default=os.environ.get("MM_URL", "http://localhost:8065"))
    ap.add_argument("--out", default=os.path.join(HERE, "generated.env"))
    args = ap.parse_args()

    c = Client(args.url)
    log(f"Seed gegen {c.url}")
    wait_for_server(c)
    old = read_env(args.out)

    c.token = ensure_admin(c)
    admin = c.get("/users/me")
    if "system_admin" not in admin["roles"]:
        sys.exit("Nutzer admin ist kein System-Admin.")

    users = {"admin": admin}
    for spec in USERS:
        users[spec["username"]] = ensure_user(c, spec)
    bot_id = ensure_bot(c)

    team = ensure_team(c)
    for u in users.values():
        ensure_team_member(c, team["id"], u["id"])
    ensure_team_member(c, team["id"], bot_id)

    channel_ids = {}
    for name, display, ctype, member_names in CHANNELS:
        ch = ensure_channel(c, team["id"], name, display, ctype)
        if ch.get("delete_at"):
            c.post(f"/channels/{ch['id']}/restore")
        for uname in member_names:
            ensure_channel_member(c, ch["id"], users[uname]["id"])
        ensure_channel_member(c, ch["id"], bot_id)
        if "admin" not in member_names:
            # Ersteller (admin) ist automatisch Mitglied - wieder austragen
            members = c.get(f"/channels/{ch['id']}/members?per_page=200")
            if any(m["user_id"] == admin["id"] for m in members):
                c.call("DELETE", f"/channels/{ch['id']}/members/{admin['id']}")
                log(f"  admin aus {name} ausgetragen")
        channel_ids[name] = ch["id"]

    # Tokens: Bot und Testnutzer (PAT, laufen nicht ab)
    bot_token = ensure_pat(c, bot_id, old.get("MM_BOT_TOKEN"), "captain-bot")
    tokens = {"admin": ensure_pat(c, admin["id"], old.get("MM_ADMIN_TOKEN"), "seed-admin")}
    for spec in USERS:
        key = f"MM_{spec['username'].upper()}_TOKEN"
        tokens[spec["username"]] = ensure_pat(c, users[spec["username"]]["id"],
                                              old.get(key), "e2e-test")

    # DM und Gruppen-DM aus Sicht von alice anlegen (POST ist idempotent)
    alice = users["alice"]["id"]
    dm = c.post("/channels/direct", [alice, bot_id], token=tokens["alice"])
    group_ids = [alice, users["bob"]["id"], users["carol"]["id"], bot_id]
    gm = c.post("/channels/group", group_ids, token=tokens["alice"])

    env = {
        "MM_URL": c.url,
        "MM_TEAM_ID": team["id"],
        "MM_TEAM_NAME": TEAM["name"],
        "MM_BOT_USERNAME": BOT["username"],
        "MM_BOT_USER_ID": bot_id,
        "MM_BOT_TOKEN": bot_token,
        "MM_CHANNEL_ALLGEMEIN_ID": channel_ids["allgemein"],
        "MM_CHANNEL_FAMILIE_ID": channel_ids["familie"],
        "MM_GROUP_DM_ID": gm["id"],
        "MM_DM_ALICE_BOT_ID": dm["id"],
        "MM_ADMIN_USERNAME": ADMIN["username"],
        "MM_ADMIN_PASSWORD": ADMIN["password"],
        "MM_ADMIN_USER_ID": admin["id"],
        "MM_ADMIN_TOKEN": tokens["admin"],
    }
    for spec in USERS:
        p = f"MM_{spec['username'].upper()}"
        env[p + "_USERNAME"] = spec["username"]
        env[p + "_PASSWORD"] = spec["password"]
        env[p + "_USER_ID"] = users[spec["username"]]["id"]
        env[p + "_TOKEN"] = tokens[spec["username"]]

    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("# Erzeugt von infra/mattermost/seed.py - nicht einchecken, nur Testdaten.\n")
        for k, v in env.items():
            f.write(f"{k}={v}\n")
    log(f"OK - geschrieben: {args.out}")


if __name__ == "__main__":
    main()
