#!/usr/bin/env python3
"""Seed fuer das lokale n8n (idempotent, stdlib + docker compose).

Legt den Owner an (falls noch keiner existiert), importiert die Credentials
"GitLab Webhook" (Secret aus infra/gitlab/generated.env) und "Captain API"
(CAPTAIN_API_TOKEN aus infra/opencode/.env) sowie den Workflow
workflows/captain-review.json (aktiv) und startet n8n neu, damit die Webhooks
greifen. Vorher: python infra/gitlab/seed.py

Aufruf (Host, Git Bash):  python infra/n8n/seed.py
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

# Nur Testdaten! Passwort ist absichtlich dokumentiert (README.md).
OWNER = {"email": "admin@captain.test", "firstName": "Admin", "lastName": "Captain",
         "password": "Admin-Test-1234"}
# Muss zu TARGETS.lokal.credentials in workflow.mjs passen
CRED_IDS = {"gitlab": "captainGitlabHk1", "captain": "captainApiToken1"}


def log(msg):
    print(f"[seed] {msg}", flush=True)


def read_env(path):
    env = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                if "=" in line and not line.lstrip().startswith("#"):
                    k, v = line.rstrip("\n").split("=", 1)
                    env[k.strip()] = v.strip().strip('"')
    return env


def wait_for_server(url, timeout=300):
    end = time.time() + timeout
    while time.time() < end:
        try:
            with urllib.request.urlopen(url + "/healthz", timeout=5) as r:
                if r.status == 200:
                    return
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(3)
    sys.exit("n8n nicht erreichbar – laeuft `docker compose up -d n8n`?")


def ensure_owner(url):
    req = urllib.request.Request(url + "/rest/owner/setup", data=json.dumps(OWNER).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    try:
        urllib.request.urlopen(req, timeout=30)
        log(f"Owner {OWNER['email']} angelegt")
    except urllib.error.HTTPError as e:
        if e.code != 400:  # 400: Owner existiert schon
            raise


def compose(*args, stdin=None):
    out = subprocess.run(["docker", "compose", *args], cwd=ROOT, input=stdin,
                         capture_output=True, text=True, check=False)
    if out.returncode != 0:
        sys.exit(f"docker compose {' '.join(args)} fehlgeschlagen:\n{out.stdout}\n{out.stderr}")
    return out.stdout


def n8n_import(kind, data):
    # Datei per stdin in den Container, dann n8n import:<kind>
    compose("exec", "-T", "n8n", "sh", "-c",
            f"cat > /tmp/captain-{kind}.json && n8n import:{kind} --input=/tmp/captain-{kind}.json"
            + f"; rc=$?; rm -f /tmp/captain-{kind}.json; exit $rc",
            stdin=json.dumps(data))


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--url", default=os.environ.get("N8N_URL", "http://localhost:5678"))
    args = ap.parse_args()

    gitlab = read_env(os.path.join(ROOT, "infra", "gitlab", "generated.env"))
    captain = read_env(os.path.join(ROOT, "infra", "opencode", ".env"))
    secret, token = gitlab.get("GITLAB_WEBHOOK_SECRET"), captain.get("CAPTAIN_API_TOKEN")
    if not secret:
        sys.exit("GITLAB_WEBHOOK_SECRET fehlt – erst python infra/gitlab/seed.py")
    if not token:
        sys.exit("CAPTAIN_API_TOKEN fehlt in infra/opencode/.env")

    wait_for_server(args.url)
    ensure_owner(args.url)

    log("importiere Credentials und Workflow")
    n8n_import("credentials", [
        {"id": CRED_IDS["gitlab"], "name": "GitLab Webhook", "type": "httpHeaderAuth",
         "data": {"name": "X-Gitlab-Token", "value": secret}},
        {"id": CRED_IDS["captain"], "name": "Captain API", "type": "httpHeaderAuth",
         "data": {"name": "Authorization", "value": f"Bearer {token}"}},
    ])
    with open(os.path.join(HERE, "workflows", "captain-review.json"), encoding="utf-8") as f:
        workflow = json.load(f)
    n8n_import("workflow", [workflow])
    # aktiv schalten (import:workflow kann das nur im Queue-Modus); greift nach dem Neustart
    compose("exec", "-T", "n8n", "n8n", "update:workflow", f"--id={workflow['id']}", "--active=true")

    log("starte n8n neu (Webhooks aktivieren)")
    compose("restart", "n8n")
    wait_for_server(args.url)
    log(f"fertig: {args.url} ({OWNER['email']} / {OWNER['password']})")


if __name__ == "__main__":
    main()
