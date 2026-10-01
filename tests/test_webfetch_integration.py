"""Integrationstests: webfetch-Allowlist (``CAPTAIN_WEBFETCH_ALLOW``) gegen echtes opencode.

Startet eigene opencode-Container aus dem Image (``CAPTAIN_OC_IMAGE``, Default
``captain/opencode:2.0.20``) mit dem Startskript, der Admin-Config-Vorlage und
zwei Hilfsservern im Testprozess:

- :mod:`tests.fake_llm` als Modell (ruft auf Kommando ``webfetch`` auf und
  hält fest, welche Tools opencode anbietet),
- :mod:`tests.web_testserver` als Web: ein Server, mehrere Hostnamen per
  ``--add-host <name>:host-gateway`` – ``text-example.org`` (erlaubt),
  ``docs.test`` (erlaubt nur unter ``/handbuch``), ``evil.test`` und
  ``text-example.org.evil.test`` (fremd). Unterschieden wird am Host-Header.

Nachweise: erlaubte URLs liefern Inhalt, fremde Hosts und Präfix-Tricks
werden abgelehnt (Session-Regel bzw. Policy), eine **Weiterleitung** vom
erlaubten auf einen fremden Host erreicht den fremden Host nicht
(Egress-Filter), eine Admin-Config mit ``webfetch * allow`` schaltet nicht
mehr frei, ohne Allowlist bleibt webfetch ganz aus.
Braucht Docker; ``pytest -m integration tests/test_webfetch_integration.py``.
"""

from __future__ import annotations

import json
import os
import pathlib
import shutil
import subprocess
import time
import uuid

import httpx
import pytest

import fake_llm
import web_testserver
from captain import webfetch
from captain.opencode import OpencodeClient, session_permissions
from captain.sessions import new_session_id

pytestmark = pytest.mark.integration

IMAGE = os.environ.get("CAPTAIN_OC_IMAGE", "captain/opencode:2.0.20")
ROOT = pathlib.Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / "infra" / "opencode" / "config" / "opencode.jsonc"
AGENTS = ROOT / "infra" / "opencode" / "config" / "AGENTS.md"
PASSWORD = "wf-" + uuid.uuid4().hex[:8]
DOCKER_ENV = {**os.environ, "MSYS_NO_PATHCONV": "1"}
HOSTS = ("text-example.org", "docs.test", "evil.test", "text-example.org.evil.test")
BLOCKED = "Blocked by configuration policy"
DENIED = "Permission denied: webfetch"
ALLOW_ALL = [{"action": "*", "resource": "*", "effect": "allow"}]
# Versuch, webfetch aus der Admin-Config ganz freizugeben
HOSTILE = """
  "permissions": [ { "action": "webfetch", "resource": "*", "effect": "allow" },
                   { "action": "*", "resource": "*", "effect": "allow" } ],
  "experimental": { "policies": [
    { "action": "permission", "resource": "webfetch:*", "effect": "allow" } ] },"""


def docker(*args: str, check: bool = True) -> str:
    out = subprocess.run(["docker", *args], capture_output=True, text=True,
                         encoding="utf-8", env=DOCKER_ENV, check=check)
    return out.stdout.strip()


def logs(name: str) -> str:
    out = subprocess.run(["docker", "logs", name], capture_output=True, text=True,
                         encoding="utf-8", errors="replace", env=DOCKER_ENV)
    return out.stdout + out.stderr


@pytest.fixture(scope="module")
def servers():
    try:
        docker("image", "inspect", IMAGE)
    except (OSError, subprocess.CalledProcessError):
        pytest.skip(f"Image {IMAGE} fehlt (docker compose -f compose.deploy.yml build)")
    llm = fake_llm.FakeLLM()
    web = web_testserver.WebServer()
    yield llm, web
    llm.close()
    web.close()


class Stack:
    def __init__(self, tmp_path, servers, allow: str, hostile: bool = False):
        self.llm, self.web = servers
        conf = tmp_path / f"config-{uuid.uuid4().hex[:6]}"
        conf.mkdir()
        text = TEMPLATE.read_text(encoding="utf-8")
        if hostile:
            text = text.replace('"permissions": [', '"permissions_orig": [', 1)
            text = text.replace('"model":', HOSTILE + '\n  "model":', 1)
        (conf / "opencode.jsonc").write_text(text, encoding="utf-8", newline="\n")
        shutil.copy(AGENTS, conf / "AGENTS.md")
        self.name = f"captain-wf-{uuid.uuid4().hex[:8]}"
        hosts = [a for h in HOSTS for a in ("--add-host", f"{h}:host-gateway")]
        docker(
            "run", "-d", "--rm", "--name", self.name, "-p", "127.0.0.1::4096",
            "--add-host", "host.docker.internal:host-gateway", *hosts,
            "-e", f"OPENCODE_SERVER_PASSWORD={PASSWORD}",
            "-e", f"LLM_BASE_URL=http://host.docker.internal:{self.llm.port}/v1",
            "-e", "LLM_API_KEY=k", "-e", "LLM_MODEL=fake", "-e", "OPENCODE_MODEL=llm/fake",
            "-e", f"CAPTAIN_WEBFETCH_ALLOW={allow}",
            "-v", f"{conf.as_posix()}:/etc/captain:ro",
            IMAGE,
        )
        port = docker("port", self.name, "4096").splitlines()[0].rsplit(":", 1)[1]
        url = f"http://127.0.0.1:{port}"
        deadline = time.monotonic() + 90
        while True:
            try:
                if httpx.get(f"{url}/api/info", auth=("opencode", PASSWORD), timeout=3).status_code == 200:
                    break
            except httpx.HTTPError:
                pass
            if time.monotonic() > deadline:
                text = logs(self.name)
                self.close()
                pytest.fail(f"opencode startet nicht:\n{text[-3000:]}")
            time.sleep(1)
        self.oc = OpencodeClient(url, PASSWORD)
        self.prefixes = webfetch.parse(allow)

    def fetch(self, url: str, permissions=None) -> tuple[str, list[str], list[tuple[str, str]]]:
        """Neue Session wie beim Bot, Modell ruft webfetch(url) → (Antwort, Tools, Web-Anfragen)."""
        sid = new_session_id()
        directory = f"/tmp/captain/{sid}"
        docker("exec", self.name, "mkdir", "-p", directory)
        rules = session_permissions(directory, self.prefixes) if permissions is None else permissions
        self.oc.create_session(directory, title="webfetch", permissions=rules, session_id=sid)
        n, m = len(self.llm.requests), len(self.web.requests)
        text = "TOOL:webfetch " + json.dumps({"url": url, "format": "text"})
        answer = self.oc.prompt(sid, text, directory=directory, model="llm/fake", idle_timeout=90)
        offered = next((r["tools"] for r in self.llm.requests[n:] if r["tools"]), [])
        assert (self.oc._request("GET", f"/api/session/{sid}/permission", sid) or []) == []
        return answer, offered, self.web.requests[m:]

    def opencode_env(self) -> str:
        script = (r'for p in /proc/[0-9]*; do c=$(tr "\000" " " < $p/cmdline 2>/dev/null); '
                  r'case "$c" in node*) ;; *" serve "*) tr "\000" "\n" < $p/environ; exit 0;; esac; done; exit 1')
        return docker("exec", self.name, "sh", "-c", script)

    def close(self):
        if getattr(self, "oc", None):
            self.oc.close()
        docker("rm", "-f", self.name, check=False)


@pytest.fixture
def stack(tmp_path, servers):
    made = []

    def make(allow: str, **kw) -> Stack:
        s = Stack(tmp_path, servers, allow, **kw)
        made.append(s)
        return s

    yield make
    for s in made:
        s.close()


def allow_value(port: int) -> str:
    # bewusst unnormalisiert: Groß-/Kleinschreibung, Schrägstrich am Ende
    return f"HTTP://Text-Example.org:{port}/ , http://docs.test:{port}/handbuch/"


def hosts(requests) -> set[str]:
    return {h.rsplit(":", 1)[0] for h, _ in requests}


def test_allowlist_rules_policies_and_redirects(stack, servers):
    _, web = servers
    p = web.port
    s = stack(allow_value(p))
    ok = f"http://text-example.org:{p}"

    # erlaubt: Inhalt kommt an, webfetch wird angeboten, Shell & Co. nicht
    answer, offered, seen = s.fetch(f"{ok}/hallo?x=1")
    assert f"WEBTEST host=text-example.org:{p} path=/hallo?x=1" in answer, answer
    assert "webfetch" in offered, offered
    for hidden in ("shell", "bash", "websearch", "execute", "subagent", "task", "skill", "question"):
        assert hidden not in offered, offered
    assert hosts(seen) == {"text-example.org"}

    answer, _, _ = s.fetch(f"http://docs.test:{p}/handbuch/kapitel-1")
    assert "path=/handbuch/kapitel-1" in answer, answer

    # abgelehnt: fremde Hosts, Präfix-Tricks, anderes Schema/Port/Schreibweise,
    # Pfad außerhalb des Präfixes und ..-Ausbruch daraus
    for url in (f"http://evil.test:{p}/x",
                f"http://text-example.org.evil.test:{p}/x",
                f"http://text-example.org:{p}@evil.test:{p}/x",
                f"https://text-example.org:{p}/x",
                f"http://TEXT-EXAMPLE.ORG:{p}/x",
                f"http://text-example.org:{p + 1 if p < 65535 else p - 1}/x",
                f"http://docs.test:{p}/geheim",
                f"http://docs.test:{p}/handbuch-geheim",
                f"http://docs.test:{p}/handbuch/../geheim",
                f"http://docs.test:{p}/handbuch/%2e%2e/geheim"):
        answer, _, seen = s.fetch(url)
        assert DENIED in answer, (url, answer)
        assert seen == [], (url, seen)

    # Backslash: opencode liest ihn als "/", der URL-Parser auch → bleibt beim erlaubten Host
    answer, _, seen = s.fetch(f"{ok}\\@evil.test:{p}/x")
    assert hosts(seen) == {"text-example.org"}, seen

    # Weiterleitung auf fremden Host: opencode folgt ohne erneute Prüfung, der
    # Egress-Filter blockt das Ziel – evil.test sieht keine Anfrage
    answer, _, seen = s.fetch(f"{ok}/redirect?to=http://evil.test:{p}/geheim")
    assert hosts(seen) == {"text-example.org"}, seen
    assert "path=/geheim" not in answer, answer
    assert f"blockiert: evil.test:{p}" in logs(s.name)
    # … auf denselben Host: erlaubt
    answer, _, seen = s.fetch(f"{ok}/redirect?to=/lokal")
    assert "path=/lokal" in answer, answer

    # Gegenprobe ohne Session-Sperren (Session-Regel "* * allow"): die Policies
    # aus start.mjs bilden die Allowlist als harte Obergrenze ab
    answer, _, seen = s.fetch(f"{ok}/policy", permissions=ALLOW_ALL)
    assert "path=/policy" in answer, answer
    for url in (f"http://evil.test:{p}/x", f"http://text-example.org.evil.test:{p}/x",
                f"http://docs.test:{p}/handbuch/../geheim"):
        answer, _, seen = s.fetch(url, permissions=ALLOW_ALL)
        assert BLOCKED in answer, (url, answer)
        assert seen == [], (url, seen)

    # opencode bekommt die Variable nicht, aber den Filter als Proxy
    env = s.opencode_env()
    assert "CAPTAIN_WEBFETCH_ALLOW" not in env
    assert "HTTP_PROXY=http://127.0.0.1:" in env and "NO_PROXY=localhost,127.0.0.1,::1" in env
    assert "MM_BOT_TOKEN" not in env


def test_hostile_admin_config_cannot_widen(stack, servers):
    _, web = servers
    p = web.port
    s = stack(f"http://text-example.org:{p}", hostile=True)
    answer, offered, _ = s.fetch(f"http://text-example.org:{p}/ok")
    assert "path=/ok" in answer, answer
    for hidden in ("shell", "bash", "websearch", "execute", "subagent", "task", "skill", "question"):
        assert hidden not in offered, offered
    answer, _, seen = s.fetch(f"http://evil.test:{p}/x")
    assert DENIED in answer and seen == [], (answer, seen)
    answer, _, seen = s.fetch(f"http://evil.test:{p}/x", permissions=ALLOW_ALL)
    assert BLOCKED in answer and seen == [], (answer, seen)
    assert "experimental" in logs(s.name)  # vom Startskript verworfen


def test_without_allowlist_webfetch_stays_off(stack, servers):
    _, web = servers
    p = web.port
    s = stack("")
    answer, offered, seen = s.fetch(f"http://text-example.org:{p}/x")
    assert "webfetch" not in offered, offered
    assert "No tool named" in answer and seen == [], (answer, seen)
    answer, _, seen = s.fetch(f"http://text-example.org:{p}/x", permissions=ALLOW_ALL)
    assert BLOCKED in answer and seen == [], (answer, seen)
    env = s.opencode_env()
    assert "127.0.0.1:" not in "".join(l for l in env.splitlines() if l.upper().startswith("HTTP"))
    assert "webfetch aus" in logs(s.name)


def test_invalid_allowlist_fails_closed(servers):
    """Ungueltiger Eintrag: opencode startet gar nicht (statt still etwas anderes freizugeben)."""
    out = subprocess.run(
        ["docker", "run", "--rm", "-e", f"OPENCODE_SERVER_PASSWORD={PASSWORD}",
         "-e", "CAPTAIN_WEBFETCH_ALLOW=http://text-example.org/*", IMAGE],
        capture_output=True, text=True, encoding="utf-8", errors="replace", env=DOCKER_ENV, timeout=60)
    assert out.returncode != 0
    assert "CAPTAIN_WEBFETCH_ALLOW" in out.stderr and "opencode startet nicht" in out.stderr, out.stderr
