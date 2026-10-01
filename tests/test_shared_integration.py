"""Integrationstests: geteiltes Verzeichnis (``CAPTAIN_SHARED_DIR``) gegen echtes opencode.

Startet eigene opencode-Container aus dem Image (``CAPTAIN_OC_IMAGE``, Default
``captain/opencode:2.0.20``) mit Startskript, Admin-Config-Vorlage und
:mod:`tests.fake_llm` als Modell (ruft auf Kommando ``read``/``glob``/
``grep``/``write`` auf). ``/shared`` ist ein Docker-Volume, das wie im
Betrieb **schreibgeschützt** eingebunden ist; befüllt (und für die
Symlink-Tests nachträglich verändert) wird es über Hilfscontainer.

Nachweise: Datei unter ``/shared`` lesen, ``glob``/``grep`` dort funktionieren;
schreiben, ``..``-Ausbrüche, ``/sharedX``, Großschreibung und fremde
Session-Verzeichnisse werden abgelehnt; Symlinks unter ``/shared`` lassen
opencode nicht starten bzw. beenden es (die Gegenprobe ohne Prüfung zeigt,
dass opencode Symlinks sonst folgt); ohne Variable gibt es keinen Zugriff,
auch nicht mit Session-Regel ``* * allow``.
Braucht Docker; ``pytest -m integration tests/test_shared_integration.py``.
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
from captain.opencode import OpencodeClient, session_permissions
from captain.sessions import new_session_id

pytestmark = pytest.mark.integration

IMAGE = os.environ.get("CAPTAIN_OC_IMAGE", "captain/opencode:2.0.20")
ROOT = pathlib.Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / "infra" / "opencode" / "config" / "opencode.jsonc"
AGENTS = ROOT / "infra" / "opencode" / "config" / "AGENTS.md"
PASSWORD = "sh-" + uuid.uuid4().hex[:8]
DOCKER_ENV = {**os.environ, "MSYS_NO_PATHCONV": "1"}
HOST_PATH = "/srv/captain-shared"  # Wert der Variable (Host-Pfad, nur geprüft)
SECRET = "GEHEIM-ANDERE-SESSION"
DOC = "Handbuch Zeile 1\nStichwort: Leuchtturm\n"
BLOCKED = "Blocked by configuration policy"
DENIED_EXT = "Permission denied: external_directory"
ALLOW_ALL = [{"action": "*", "resource": "*", "effect": "allow"}]
# Inhalt des geteilten Volumes: Datei, Unterverzeichnis, Datei mit Leerzeichen
SEED = (
    "mkdir -p /w/sub /w/leer && printf '%s' \"$DOC\" > /w/handbuch.txt"
    " && printf 'Unterordner Leuchtturm\\n' > /w/sub/b.md && printf 'x\\n' > '/w/mit leer.txt'"
)


def docker(*args: str, check: bool = True, input: str | None = None) -> str:
    out = subprocess.run(["docker", *args], capture_output=True, text=True, encoding="utf-8",
                         errors="replace", env=DOCKER_ENV, check=check, input=input)
    return out.stdout.strip()


def logs(name: str) -> str:
    out = subprocess.run(["docker", "logs", name], capture_output=True, text=True,
                         encoding="utf-8", errors="replace", env=DOCKER_ENV)
    return out.stdout + out.stderr


class Volume:
    """Docker-Volume als Quelle von /shared, beschreibbar nur über Hilfscontainer."""

    def __init__(self):
        self.name = f"captain-sh-{uuid.uuid4().hex[:8]}"
        docker("volume", "create", self.name)
        self.sh(SEED)

    def sh(self, script: str) -> str:
        return docker("run", "--rm", "-e", f"DOC={DOC}", "-v", f"{self.name}:/w", "--entrypoint", "sh",
                      IMAGE, "-c", script)

    def close(self):
        docker("volume", "rm", "-f", self.name, check=False)


@pytest.fixture(scope="module")
def llm():
    try:
        docker("image", "inspect", IMAGE)
    except (OSError, subprocess.CalledProcessError):
        pytest.skip(f"Image {IMAGE} fehlt (docker compose -f compose.deploy.yml build)")
    server = fake_llm.FakeLLM()
    yield server
    server.close()


@pytest.fixture
def volume():
    v = Volume()
    yield v
    v.close()


def run_args(name: str, llm, conf: pathlib.Path, shared: str, mount: str | None, extra=()) -> list[str]:
    args = ["run", "-d", "--name", name, "-p", "127.0.0.1::4096",
            "--add-host", "host.docker.internal:host-gateway",
            "-e", f"OPENCODE_SERVER_PASSWORD={PASSWORD}",
            "-e", f"LLM_BASE_URL=http://host.docker.internal:{llm.port}/v1",
            "-e", "LLM_API_KEY=k", "-e", "LLM_MODEL=fake", "-e", "OPENCODE_MODEL=llm/fake",
            "-e", f"CAPTAIN_SHARED_DIR={shared}", "-e", "CAPTAIN_HOME=/opt/captain",
            "-v", f"{conf.as_posix()}:/etc/captain:ro"]
    if mount:
        args += ["-v", mount]
    return [*args, *extra, IMAGE]


def admin_conf(tmp_path) -> pathlib.Path:
    conf = tmp_path / f"config-{uuid.uuid4().hex[:6]}"
    conf.mkdir()
    shutil.copy(TEMPLATE, conf / "opencode.jsonc")
    shutil.copy(AGENTS, conf / "AGENTS.md")
    return conf


class Stack:
    def __init__(self, tmp_path, llm, shared: str = HOST_PATH, mount: str | None = None, extra=(), cmd=()):
        self.llm = llm
        self.name = f"captain-sh-{uuid.uuid4().hex[:8]}"
        docker(*run_args(self.name, llm, admin_conf(tmp_path), shared, mount, extra), *cmd)
        port = docker("port", self.name, "4096").splitlines()[0].rsplit(":", 1)[1]
        url = f"http://127.0.0.1:{port}"
        deadline = time.monotonic() + 90
        while True:
            try:
                if httpx.get(f"{url}/api/info", auth=("opencode", PASSWORD), timeout=3).status_code == 200:
                    break
            except httpx.HTTPError:
                pass
            if time.monotonic() > deadline or not self.running():
                text = logs(self.name)
                self.close()
                pytest.fail(f"opencode startet nicht:\n{text[-3000:]}")
            time.sleep(1)
        self.oc = OpencodeClient(url, PASSWORD)
        self.shared = bool(shared)
        # fremde Session mit Geheimnis
        self.other = f"/tmp/captain/{new_session_id()}"
        docker("exec", self.name, "sh", "-c", f"mkdir -p {self.other} && echo {SECRET} > {self.other}/geheim.txt")

    def running(self) -> bool:
        return docker("inspect", "-f", "{{.State.Running}}", self.name, check=False) == "true"

    def call(self, tool: str, args: dict, permissions=None) -> tuple[str, list[str], str]:
        """Neue Session wie beim Bot, Modell ruft ``tool(args)`` → (Antwort, Tools, Verzeichnis)."""
        sid = new_session_id()
        directory = f"/tmp/captain/{sid}"
        docker("exec", self.name, "mkdir", "-p", directory)
        rules = session_permissions(directory, (), self.shared) if permissions is None else permissions
        self.oc.create_session(directory, title="shared", permissions=rules, session_id=sid)
        n = len(self.llm.requests)
        text = f"TOOL:{tool} " + json.dumps(args)
        answer = self.oc.prompt(sid, text, directory=directory, model="llm/fake", idle_timeout=90)
        offered = next((r["tools"] for r in self.llm.requests[n:] if r["tools"]), [])
        assert (self.oc._request("GET", f"/api/session/{sid}/permission", sid) or []) == []
        return answer, offered, directory

    def close(self):
        if getattr(self, "oc", None):
            self.oc.close()
        docker("rm", "-f", self.name, check=False)


@pytest.fixture
def stack(tmp_path, llm):
    made = []

    def make(**kw) -> Stack:
        s = Stack(tmp_path, llm, **kw)
        made.append(s)
        return s

    yield make
    for s in made:
        s.close()


def read(s: Stack, path: str, **kw) -> str:
    return s.call("read", {"path": path}, **kw)[0]


def opencode_env(s: Stack) -> str:
    script = (r'for p in /proc/[0-9]*; do c=$(tr "\000" " " < $p/cmdline 2>/dev/null); '
              r'case "$c" in node*) ;; *" serve "*) tr "\000" "\n" < $p/environ; exit 0;; esac; done; exit 1')
    return docker("exec", s.name, "sh", "-c", script)


def start_fails(tmp_path, llm, shared: str = HOST_PATH, mount: str | None = None, extra=()) -> str:
    """opencode darf nicht starten → Log des Startskripts."""
    name = f"captain-sh-{uuid.uuid4().hex[:8]}"
    args = run_args(name, llm, admin_conf(tmp_path), shared, mount, extra)
    args.remove("-d")
    try:
        out = subprocess.run(["docker", *args], capture_output=True, text=True, encoding="utf-8",
                             errors="replace", env=DOCKER_ENV, timeout=90)
    finally:
        docker("rm", "-f", name, check=False)
    assert out.returncode != 0, out.stdout[-2000:] + out.stderr[-2000:]
    assert "opencode startet nicht" in out.stderr, out.stderr[-2000:]
    return out.stderr


def test_read_glob_grep_allowed_write_and_escapes_denied(volume, stack):
    volume.sh("mkdir -p /w/repo/.git && printf '[remote]\\n url = https://user:TOKEN-123@git.example/x\\n' "
              "> /w/repo/.git/config && printf 'Repo-Inhalt Leuchtturm\\n' > /w/repo/README.md")
    s = stack(mount=f"{volume.name}:/shared:ro")

    # lesen: Datei, Verzeichnis, Unterordner, Leerzeichen, relativ aus dem Session-Verzeichnis
    answer, offered, _ = s.call("read", {"path": "/shared/handbuch.txt"})
    assert "Stichwort: Leuchtturm" in answer, answer
    assert sorted(offered) == ["edit", "glob", "grep", "read", "write"], offered
    assert "handbuch.txt" in read(s, "/shared") and "sub/" in read(s, "/shared/")
    assert "Unterordner Leuchtturm" in read(s, "/shared/sub/b.md")
    assert "1: x" in read(s, "/shared/mit leer.txt")
    assert "Stichwort" in read(s, "../../../shared/handbuch.txt")

    # glob/grep unter /shared (ohne .git-Inhalte)
    answer = s.call("glob", {"pattern": "**/*.md", "path": "/shared"})[0]
    assert "/shared/sub/b.md" in answer and "/shared/repo/README.md" in answer, answer
    answer = s.call("grep", {"pattern": "Leuchtturm", "path": "/shared"})[0]
    assert "/shared/handbuch.txt" in answer and "/shared/sub/b.md" in answer, answer
    for tool, args in (("grep", {"pattern": "TOKEN-123", "path": "/shared"}),
                       ("grep", {"pattern": "TOKEN", "path": "/shared", "include": "**/.git/**"}),
                       ("glob", {"pattern": "**/.git/**", "path": "/shared"}),
                       ("glob", {"pattern": "../tmp/captain/**", "path": "/shared"})):
        answer = s.call(tool, args)[0]
        assert "TOKEN-123" not in answer and "geheim" not in answer and ".git/config" not in answer, (args, answer)

    # .git gesperrt, auch über Umwege und als Suchpfad von glob/grep
    for path in ("/shared/repo/.git/config", "/shared/repo/.git", "/shared/repo/./.git/config",
                 "/shared/sub/../repo/.git/config", "/shared/repo/.GIT/config", "/shared/.netrc"):
        answer = read(s, path)
        assert "TOKEN-123" not in answer and "permission.rejected" in answer, (path, answer)
    for tool, args in (("grep", {"pattern": "TOKEN", "path": "/shared/repo/.git"}),
                       ("glob", {"pattern": "*", "path": "/shared/repo/.git"})):
        answer = s.call(tool, args)[0]
        assert DENIED_EXT in answer, (args, answer)

    # abgelehnt: fremde Session (direkt und per ..), /sharedX, Großschreibung, /etc, Wurzel
    for path in (f"{s.other}/geheim.txt", f"/shared/../{s.other}/geheim.txt",
                 f"/shared/sub/../..{s.other}/geheim.txt", "/sharedX/x",
                 "/shared-x/handbuch.txt", "/SHARED/handbuch.txt", "/etc/passwd", "/"):
        answer = read(s, path)
        assert DENIED_EXT in answer and SECRET not in answer, (path, answer)
    answer = s.call("grep", {"pattern": "GEHEIM", "path": "/shared/../tmp/captain"})[0]
    assert DENIED_EXT in answer and SECRET not in answer, answer

    # schreiben verboten (Session-Regel); mit "* * allow" greift die Policy
    for tool, args in (("write", {"path": "/shared/neu.txt", "content": "x"}),
                       ("edit", {"path": "/shared/handbuch.txt", "oldString": "Zeile", "newString": "X"})):
        answer = s.call(tool, args)[0]
        assert "Permission denied: edit" in answer, (tool, answer)
        answer = s.call(tool, args, permissions=ALLOW_ALL)[0]
        assert BLOCKED in answer, (tool, answer)
    assert "neu.txt" not in docker("exec", s.name, "ls", "/shared")
    assert "Zeile 1" in read(s, "/shared/handbuch.txt")

    # Gegenprobe ohne Session-Sperren: die Policies aus start.mjs sind die Obergrenze
    assert "Stichwort" in read(s, "/shared/handbuch.txt", permissions=ALLOW_ALL)
    for path in ("/etc/passwd", f"{s.other}/geheim.txt", "/shared/repo/.git/config", "/sharedX/x",
                 "/root/.local/share/opencode/opencode.db"):
        answer = read(s, path, permissions=ALLOW_ALL)
        assert BLOCKED in answer and SECRET not in answer and "TOKEN-123" not in answer, (path, answer)

    # Änderungen ohne Neustart sichtbar
    volume.sh("printf 'neu hinzugekommen\\n' > /w/neu.txt")
    assert "neu hinzugekommen" in read(s, "/shared/neu.txt")

    # opencode selbst bekommt die Variablen nicht, nur die Policies
    env = opencode_env(s)
    assert "CAPTAIN_SHARED_DIR" not in env and "CAPTAIN_HOME" not in env
    assert '"resource":"external_directory:/shared/*","effect":"allow"' in env


def test_without_variable_no_access(volume, stack):
    """Ohne CAPTAIN_SHARED_DIR: auch ein eingebundenes /shared bleibt gesperrt."""
    s = stack(shared="", mount=f"{volume.name}:/shared:ro")
    assert not s.shared
    answer = read(s, "/shared/handbuch.txt")
    assert DENIED_EXT in answer and "Stichwort" not in answer, answer
    # selbst mit Session-Regeln für /shared (Bot falsch konfiguriert) bzw. "* * allow"
    directory = f"/tmp/captain/{new_session_id()}"
    for rules in (session_permissions(directory, (), True), ALLOW_ALL):
        answer = read(s, "/shared/handbuch.txt", permissions=rules)
        assert BLOCKED in answer and "Stichwort" not in answer, answer
    answer = s.call("grep", {"pattern": "Leuchtturm", "path": "/shared"}, permissions=ALLOW_ALL)[0]
    assert BLOCKED in answer, answer
    assert "geteiltes Verzeichnis aus" in logs(s.name)


@pytest.mark.parametrize("bad", [
    "ln -s /etc/passwd /w/passwd",
    "ln -s /tmp/captain /w/sessions",
    "mkdir -p /w/a/b && ln -s ../../handbuch.txt /w/a/b/rel",
    "ln -s /root/.local/share/opencode /w/sub/db",
    "ln /w/handbuch.txt /w/hart.txt",
    "mkfifo /w/fifo",
    "mkdir -p /w/m.git/objects /w/m.git/refs && echo ref > /w/m.git/HEAD",
    "echo 'machine x password y' > /w/sub/.netrc",
], ids=["passwd", "sessions", "relativ", "opencode-db", "hardlink", "fifo", "bare-repo", "netrc"])
def test_symlinks_and_special_files_block_start(tmp_path, llm, volume, bad):
    volume.sh(bad)
    err = start_fails(tmp_path, llm, mount=f"{volume.name}:/shared:ro")
    assert "/shared/" in err and "nicht erlaubt" in err, err


def test_gegenprobe_opencode_folgt_symlinks(volume, stack):
    """Ohne die Prüfung im Startskript liest opencode über Symlinks fremde Daten.

    Startet opencode mit genau der Umgebung aus start.mjs (Policies mit
    /shared-Freigabe), aber ohne Prüfung von /shared – Beleg, dass opencode
    Pfade nicht per realpath auflöst und die Prüfung nötig ist.
    """
    volume.sh("ln -s /tmp/captain /w/sessions && ln -s /etc/passwd /w/passwd")
    script = (
        'import("/opt/captain/start.mjs").then((m) => {'
        ' const fs = require("fs"); const b = m.buildConfig(fs.readFileSync(m.SOURCE, "utf8"), process.env);'
        ' fs.mkdirSync("/run/captain", { recursive: true }); fs.writeFileSync(m.TARGET, b.json);'
        ' const env = m.environment(process.env, m.enabledProviders(b.clean, process.env), { shared: true });'
        ' require("child_process").spawn(m.COMMAND[0], m.COMMAND.slice(1), { stdio: "inherit", env })'
        '.on("exit", (c) => process.exit(c ?? 1)); })'
    )
    s = stack(mount=f"{volume.name}:/shared:ro", extra=("--entrypoint", "node"), cmd=("-e", script))
    assert SECRET in read(s, f"/shared/sessions/{s.other.rsplit('/', 1)[1]}/geheim.txt")
    assert "root:x:0:0" in read(s, "/shared/passwd")


def test_symlink_at_runtime_stops_opencode(volume, stack):
    s = stack(mount=f"{volume.name}:/shared:ro")
    assert "Stichwort" in read(s, "/shared/handbuch.txt")
    volume.sh("ln -s /tmp/captain /w/sessions")
    deadline = time.monotonic() + 30
    while s.running() and time.monotonic() < deadline:
        time.sleep(1)
    assert not s.running(), "opencode läuft trotz Symlink weiter"
    text = logs(s.name)
    assert "FEHLER" in text and "/shared/sessions: Symlink" in text, text[-2000:]
    # und startet damit auch nicht wieder
    docker("start", s.name)
    time.sleep(5)
    assert not s.running()
    assert "opencode startet nicht" in logs(s.name)


@pytest.mark.parametrize("value", ["shared", "./shared", "/", "/etc", "/srv", "/srv/../etc", "/srv//x",
                                   "C:/shared", "/srv/a:b", "/opt/captain", "/opt/captain/sharedX",
                                   "/opt/captain/config", "/opt/captain/sessions/x", "/opt"])
def test_invalid_variable_fails_closed(tmp_path, llm, volume, value):
    err = start_fails(tmp_path, llm, shared=value, mount=f"{volume.name}:/shared:ro")
    assert "CAPTAIN_SHARED_DIR" in err, err


def test_writable_or_missing_mount_fails_closed(tmp_path, llm, volume):
    err = start_fails(tmp_path, llm, mount=f"{volume.name}:/shared")
    assert "beschreibbar" in err, err
    err = start_fails(tmp_path, llm, mount=None)
    assert "/shared fehlt" in err, err


def test_captain_data_below_shared_fails_closed(tmp_path, llm, volume):
    """Geteiltes Verzeichnis = Session-Verzeichnisse (z. B. CAPTAIN_HOME/sessions)."""
    err = start_fails(tmp_path, llm, mount=f"{volume.name}:/shared:ro", extra=("-v", f"{volume.name}:/tmp/captain"))
    assert "Captain-Daten" in err, err


def test_mount_source_may_be_symlink(stack):
    """CAPTAIN_SHARED_DIR darf auf einen Symlink zeigen: Docker löst die Quelle beim Anlegen auf.

    Pfade im Docker-Host (unter Docker Desktop: in dessen VM).
    """
    tag = uuid.uuid4().hex[:8]
    target, link = f"/tmp/captain-ziel-{tag}", f"/tmp/captain-link-{tag}"
    host = ("-v", "/tmp:/host-tmp", "--entrypoint", "sh", IMAGE, "-c")
    docker("run", "--rm", *host, f"mkdir -p /host-tmp/captain-ziel-{tag} && echo per-link > "
           f"/host-tmp/captain-ziel-{tag}/a.txt && ln -s {target} /host-tmp/captain-link-{tag}")
    try:
        s = stack(shared=link, mount=f"{link}:/shared:ro")
        assert "per-link" in read(s, "/shared/a.txt")
        assert docker("exec", s.name, "stat", "-c", "%F", "/shared") == "directory"
    finally:
        docker("run", "--rm", *host, f"rm -rf /host-tmp/captain-ziel-{tag} /host-tmp/captain-link-{tag}", check=False)


def test_mountpoint_below_shared_fails_closed(tmp_path, llm, volume):
    """Bind-Mount unter dem geteilten Verzeichnis (z. B. sessions/<id>): anderes Dateisystem."""
    other = Volume()
    try:
        err = start_fails(tmp_path, llm, mount=f"{volume.name}:/shared:ro", extra=("-v", f"{other.name}:/shared/sub:ro"))
        assert "/shared/sub: Mountpoint" in err, err
    finally:
        other.close()


def test_scan_limit_fails_closed(tmp_path, llm, volume):
    err = start_fails(tmp_path, llm, mount=f"{volume.name}:/shared:ro", extra=("-e", "CAPTAIN_SHARED_MAX_ENTRIES=3"))
    assert "mehr als 3 Eintraege" in err and "CAPTAIN_SHARED_MAX_ENTRIES" in err, err


def test_home_shared_subdir_allowed(volume, stack):
    """CAPTAIN_SHARED_DIR=<CAPTAIN_HOME>/shared/… ist erlaubt (Container bekommt CAPTAIN_HOME=/opt/captain)."""
    s = stack(shared="/opt/captain/shared/infos", mount=f"{volume.name}:/shared:ro")
    assert "Stichwort" in read(s, "/shared/handbuch.txt")
