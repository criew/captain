#!/usr/bin/env python3
"""Seed fuer das lokale GitLab CE (idempotent, REST, stdlib).

Legt an: Service-Account `captain-bot` mit fine-grained Token (MCP), Gruppe
`team` mit Projekt `playground`, Webhook auf n8n. Schreibt Tokens nach
infra/gitlab/generated.env und den MCP-Token nach infra/gitlab/mcp.env
(nur diese Datei bekommt opencode).

Mehrfach ausfuehrbar: Vorhandenes wird wiederverwendet, gueltige Tokens aus
einer bestehenden generated.env werden weiterbenutzt.

Aufruf (Host, Git Bash):
  python infra/gitlab/seed.py          # Seed
  python infra/gitlab/seed.py --mr     # zusaetzlich einen neuen Test-MR oeffnen
"""

import argparse
import datetime
import json
import os
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

BOT = {"username": "captain-bot", "name": "Captain Bot", "email": "captain-bot@captain.test"}
GROUP = "team"
PROJECT = "playground"
HOOK_URL = "http://n8n:5678/webhook/gitlab"

# Rechte des MCP-Tokens. execute_mcp_tool gilt fuer den User, der Rest fuer
# alle Projekte, in denen der Bot Mitglied ist. create_merge_request enthaelt
# das Recht auf MR-Kommentare (ein eigenes Notiz-Recht gibt es in 19.2 nicht).
MCP_SCOPES = [
    {"access": "user", "permissions": ["execute_mcp_tool"]},
    {"access": "all_memberships", "permissions": [
        "read_project", "read_merge_request", "create_merge_request", "read_code",
        "read_repository", "read_commit", "read_pipeline", "read_job"]},
]

# Ruby fuer den ersten Admin-Token (die API kann keinen Token ohne Token anlegen)
ADMIN_TOKEN_RB = """
u = User.find_by_username("root")
u.personal_access_tokens.where(name: "captain-seed").each(&:revoke!)
t = u.personal_access_tokens.create!(name: "captain-seed", scopes: [:api, :sudo, :admin_mode],
  expires_at: 360.days.from_now, organization: u.organization)
puts "CAPTAIN_SEED_TOKEN=#{t.token}"
"""

# Beispiel-Code fuer den Test-MR, mit Absicht nicht ganz sauber
SAMPLE = """package de.example.konto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KontoService {

    @Value("${konto.api-url}")
    private String apiUrl;

    private final KontoRepository repository;

    public KontoService(KontoRepository repository) {
        this.repository = repository;
    }

    public void ueberweise(String von, String an, long cent) {
        abbuchen(von, cent);
        gutschreiben(an, cent);
    }

    @Transactional
    void abbuchen(String konto, long cent) {
        Konto k = repository.findById(konto).get();
        k.setSaldo(k.getSaldo() - cent);
    }

    @Transactional
    void gutschreiben(String konto, long cent) {
        Konto k = repository.findById(konto).get();
        k.setSaldo(k.getSaldo() + cent);
    }
}
"""


class ApiError(Exception):
    def __init__(self, status, body):
        super().__init__(f"HTTP {status}: {body}")
        self.status = status


class Client:
    def __init__(self, url, token=None):
        self.url = url.rstrip("/")
        self.token = token

    def call(self, method, path, data=None, headers=None):
        body = None if data is None else json.dumps(data).encode()
        req = urllib.request.Request(self.url + "/api/v4" + path, data=body, method=method)
        req.add_header("Content-Type", "application/json")
        if self.token:
            req.add_header("PRIVATE-TOKEN", self.token)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                payload = resp.read()
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
    print(f"[seed] {msg}", flush=True)


def q(path):
    return urllib.parse.quote(path, safe="")


def wait_for_server(url, timeout=900):
    log(f"warte auf GitLab unter {url} (erster Start: 3–5 min) …")
    end = time.time() + timeout
    while time.time() < end:
        try:
            with urllib.request.urlopen(url + "/users/sign_in", timeout=10) as r:
                if r.status == 200:
                    return
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(10)
    sys.exit("GitLab nicht erreichbar – laeuft `docker compose up -d gitlab`?")


def read_env(path):
    env = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                if "=" in line and not line.lstrip().startswith("#"):
                    k, v = line.rstrip("\n").split("=", 1)
                    env[k.strip()] = v.strip()
    return env


def token_ok(url, token):
    if not token:
        return False
    try:
        Client(url, token).get("/personal_access_tokens/self")
        return True
    except ApiError:
        return False


def admin_token(url, existing):
    if token_ok(url, existing):
        return existing
    log("lege Admin-Token per gitlab-rails runner an (dauert ~1–2 min) …")
    out = subprocess.run(
        ["docker", "compose", "exec", "-T", "gitlab", "gitlab-rails", "runner", ADMIN_TOKEN_RB],
        cwd=ROOT, capture_output=True, text=True, check=False)
    for line in out.stdout.splitlines():
        if line.startswith("CAPTAIN_SEED_TOKEN="):
            return line.split("=", 1)[1].strip()
    sys.exit(f"Admin-Token fehlgeschlagen:\n{out.stdout}\n{out.stderr}")


def ensure_settings(c):
    # Webhooks an n8n im selben Docker-Netz; MCP-Server ist ab 19.2 an, hier nur sicherstellen
    c.put("/application/settings", {
        "allow_local_requests_from_web_hooks_and_services": True,
        "signup_enabled": False,
        "mcp_server_enabled": True,
    })


def ensure_bot(c):
    users = c.get(f"/users?username={BOT['username']}")
    if users:
        return users[0]
    log(f"lege Service-Account {BOT['username']} an")
    return c.post("/service_accounts", BOT)


def ensure_project(c, bot_id):
    try:
        group = c.get(f"/groups/{GROUP}")
    except ApiError:
        group = c.post("/groups", {"name": "Team", "path": GROUP, "visibility": "private"})
    try:
        project = c.get(f"/projects/{q(GROUP + '/' + PROJECT)}")
    except ApiError:
        log(f"lege Projekt {GROUP}/{PROJECT} an")
        project = c.post("/projects", {"name": PROJECT, "namespace_id": group["id"],
                                       "initialize_with_readme": True, "visibility": "private"})
    try:
        c.post(f"/projects/{project['id']}/members", {"user_id": bot_id, "access_level": 30})
    except ApiError as e:
        if e.status != 409:
            raise
    return project


def mcp_token(c, url, existing):
    if token_ok(url, existing):
        return existing
    # Die Admin-Route nimmt (19.2) keine granular_scopes; per Sudo legt der Bot ihn selbst an.
    log("lege fine-grained Token (MCP) fuer den Bot an")
    expires = (datetime.date.today() + datetime.timedelta(days=360)).isoformat()
    t = c.post("/user/personal_access_tokens",
               {"name": "captain-mcp", "expires_at": expires, "granular_scopes": MCP_SCOPES},
               headers={"Sudo": BOT["username"]})
    return t["token"]


def ensure_hook(c, project_id, secret, url):
    for h in c.get(f"/projects/{project_id}/hooks"):
        if h["url"] == url:
            c.put(f"/projects/{project_id}/hooks/{h['id']}", hook_spec(url, secret))
            return
    log(f"lege Webhook {url} an")
    c.post(f"/projects/{project_id}/hooks", hook_spec(url, secret))


def hook_spec(url, secret):
    return {"url": url, "token": secret, "merge_requests_events": True, "note_events": True,
            "pipeline_events": True, "push_events": False, "enable_ssl_verification": False}


def open_mr(c, project_id):
    branch = "feature/konto-" + time.strftime("%Y%m%d-%H%M%S")
    c.post(f"/projects/{project_id}/repository/commits", {
        "branch": branch, "start_branch": "main", "commit_message": "Ueberweisung zwischen Konten",
        "actions": [{"action": "create", "file_path": "src/main/java/de/example/konto/KontoService.java",
                     "content": SAMPLE}]})
    mr = c.post(f"/projects/{project_id}/merge_requests", {
        "source_branch": branch, "target_branch": "main", "title": "Ueberweisung zwischen Konten",
        "description": "Erste Version der Ueberweisung. Bitte reviewen.", "remove_source_branch": True})
    log(f"Test-MR: {mr['web_url']}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--url", default=os.environ.get("GITLAB_URL", "http://localhost:8929"))
    ap.add_argument("--out", default=os.path.join(HERE, "generated.env"))
    ap.add_argument("--mcp-out", default=os.path.join(HERE, "mcp.env"))
    ap.add_argument("--hook-url", default=HOOK_URL, help="Webhook-Ziel (n8n, aus Sicht von GitLab)")
    ap.add_argument("--mr", action="store_true", help="zusaetzlich einen neuen Test-MR oeffnen")
    args = ap.parse_args()

    wait_for_server(args.url)
    old = read_env(args.out)
    c = Client(args.url, admin_token(args.url, old.get("GITLAB_ADMIN_TOKEN")))
    ensure_settings(c)
    bot = ensure_bot(c)
    project = ensure_project(c, bot["id"])
    token = mcp_token(c, args.url, read_env(args.mcp_out).get("MCP_GITLAB_TOKEN"))
    secret = old.get("GITLAB_WEBHOOK_SECRET") or secrets.token_hex(16)
    ensure_hook(c, project["id"], secret, args.hook_url)

    with open(args.out, "w", encoding="utf-8") as f:
        f.write("# Von infra/gitlab/seed.py erzeugt – nicht einchecken.\n")
        f.write(f"GITLAB_URL={args.url}\nGITLAB_ADMIN_TOKEN={c.token}\n")
        f.write(f"GITLAB_BOT_USERNAME={BOT['username']}\nGITLAB_PROJECT_ID={project['id']}\n")
        f.write(f"GITLAB_WEBHOOK_SECRET={secret}\n")
    # Getrennt, damit opencode nur den MCP-Token sieht, nicht den Admin-Token
    with open(args.mcp_out, "w", encoding="utf-8") as f:
        f.write("# Von infra/gitlab/seed.py erzeugt – nicht einchecken. opencode: {env:MCP_GITLAB_TOKEN}\n")
        f.write(f"MCP_GITLAB_TOKEN={token}\n")
    log(f"geschrieben: {args.out}, {args.mcp_out}")

    if args.mr:
        open_mr(c, project["id"])


if __name__ == "__main__":
    main()
