#!/usr/bin/env python3
"""Ende-zu-Ende-Test gegen das laufende Test-Setup (Mattermost + opencode + Bot).

Postet als alice/bob per REST in DM, Gruppen-DM, privatem Kanal und Thread und
wartet auf die fertige Bot-Antwort. Eine Antwort gilt als fertig, sobald ihr
Text nicht mehr mit dem Streaming-Cursor „▌“ endet.

Geprüft wird die Mention-Logik: DM ohne Erwähnung → Antwort direkt im Chat;
Kanal/Gruppe ohne Erwähnung → still; mit ``@captain`` → Antwort im Thread
unter dem Post, mit Wissen aus den Kanal-Posts davor; Folgeantwort im Thread
ohne Erwähnung → Antwort mit Kontext; ``!help``.

Mit ``--restart`` läuft stattdessen nur der Neustart-Test (Nachholen, #16): Der
Bot-Container wird gestoppt, alice schreibt eine DM und eine Kanal-Erwähnung,
der Bot wird wieder gestartet; beide Posts müssen genau einmal mit
Verspätungshinweis beantwortet werden. Danach wird der Bot gestoppt, während
eine Antwort streamt: Der Zwischenstand muss als unterbrochen markiert und
der Post nach dem Start genau einmal fertig beantwortet werden. Er startet den Container ``captain`` neu (``docker compose``
mit ``compose.yml`` dieses Repos, Projekt ``--project``) und ist deshalb
getrennt auswählbar.

Mit ``--attachments`` läuft nur der Anhang-Test (#22): alice schickt per DM und
per ``@captain`` im Kanal ein Bild (rotes Quadrat mit weißer 7), eine
Textdatei (plus Folgefrage ohne Anhang, die das Modell per ``read`` aus dem
Session-Verzeichnis beantworten soll) und ein PDF; außerdem einen Anhang über
``MAX_ATTACHMENT_MB``. Geprüft wird, dass Anhänge in ``/tmp/captain/<id>/``
liegen, Textdateien verstanden werden und der zu große Anhang nicht geladen
und in der Antwort erwähnt wird. Bild und PDF sind **Befunde**: Das Skript
meldet, ob das Modell sie verstanden hat, wird dafür aber nicht rot (hängt vom
Modell ab). Alle Testdateien werden mit der stdlib erzeugt.

Aufruf:  python scripts/e2e.py [--env infra/mattermost/generated.env] [--timeout 300]
         python scripts/e2e.py --restart [--project captain]
         python scripts/e2e.py --attachments [--project captain] [--max-mb 20]

Nur stdlib. Voraussetzung: ``docker compose up -d`` und Seed (siehe README).
Mit einem lokalen CPU-Modell dauert die erste Antwort nach Kaltstart ~2 min.
Die Stille-Prüfungen warten je ``--quiet`` Sekunden (Default 15).
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import struct
import subprocess
import sys
import time
import urllib.request
import uuid
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "infra", "mattermost"))
from seed import Client, read_env  # noqa: E402

CURSOR = "▌"
LATE = "verspätet"  # Hinweis in nachgeholten Antworten (captain.catchup.LATE_NOTE)
INTERRUPTED = "unterbrochen"  # abgebrochene Antwort (captain.catchup.INTERRUPTED_NOTE)


def unfinished(message: str) -> bool:
    text = message.rstrip()
    return text.endswith(CURSOR) or INTERRUPTED in text


class E2E:
    def __init__(self, env: dict, timeout: float, quiet: float = 15.0):
        self.env = env
        self.quiet = quiet
        self.c = Client(env["MM_URL"])
        self.bot_id = env["MM_BOT_USER_ID"]
        self.tokens = {"alice": env["MM_ALICE_TOKEN"], "bob": env["MM_BOB_TOKEN"]}
        self.timeout = timeout
        self.results: list[tuple[str, bool, float, str]] = []
        self.findings: list[tuple[str, bool, float, str]] = []
        # für --attachments
        self.project = "captain"
        self.oc_url = "http://localhost:4096"
        self.oc_password = ""
        self.max_mb = 20
        self.vision_model = ""

    # -- REST ---------------------------------------------------------------

    def post(self, user: str, channel_id: str, message: str, root_id: str = "") -> dict:
        body = {"channel_id": channel_id, "message": message}
        if root_id:
            body["root_id"] = root_id
        return self.c.post("/posts", body, token=self.tokens[user])

    def bot_posts(self, channel_id: str, after: dict, root_id: str | None) -> list[dict]:
        """Bot-Posts im Kanal nach ``after`` (``root_id=None``: egal wo), älteste zuerst."""
        data = self.c.get(
            f"/channels/{channel_id}/posts?since={after['create_at']}", token=self.tokens["alice"]
        )
        posts = [
            p for p in data.get("posts", {}).values()
            if p["user_id"] == self.bot_id
            and p["create_at"] > after["create_at"]
            and (root_id is None or (p.get("root_id") or "") == root_id)
            and not p.get("delete_at")
        ]
        return sorted(posts, key=lambda p: p["create_at"])

    def wait_reply(self, channel_id: str, after: dict, root_id: str = "", *,
                   until=None, timeout: float | None = None) -> tuple[str, float]:
        """Wartet auf eine fertige Bot-Antwort; ``until(msgs)`` für eigene Bedingungen."""
        timeout = timeout or self.timeout
        t0 = time.monotonic()
        while time.monotonic() - t0 < timeout:
            posts = self.bot_posts(channel_id, after, root_id)
            msgs = [p["message"] for p in posts]
            if msgs and not any(m.rstrip().endswith(CURSOR) for m in msgs):
                if until is None or until(msgs):
                    return msgs[-1], time.monotonic() - t0
            time.sleep(1)
        raise TimeoutError(f"keine fertige Bot-Antwort nach {timeout:.0f} s")

    # -- Ablauf -------------------------------------------------------------

    def check(self, name: str, fn) -> None:
        print(f"… {name}", flush=True)
        t0 = time.monotonic()
        try:
            text, secs = fn()
            ok, detail = True, text
        except (AssertionError, TimeoutError) as e:
            ok, secs, detail = False, time.monotonic() - t0, str(e)
        except Exception as e:  # noqa: BLE001
            ok, secs, detail = False, time.monotonic() - t0, f"{type(e).__name__}: {e}"
        short = " ".join(detail.split())[:90]
        print(f"{'OK  ' if ok else 'FAIL'} {name} ({secs:.1f} s): {short}", flush=True)
        self.results.append((name, ok, secs, short))

    def ask(self, user, channel, message, root_id="", expect=None, *, thread=False,
            sent_to=None, **kw):
        """Postet und wartet auf die Antwort – bei ``thread`` im Thread unter dem Post.

        ``sent_to`` (dict) bekommt den gesendeten Post, z. B. für Folgeantworten.
        """
        sent = self.post(user, channel, message, root_id)
        if sent_to is not None:
            sent_to.update(sent)
        reply_root = root_id or (sent["id"] if thread else "")
        text, secs = self.wait_reply(channel, sent, reply_root, **kw)
        # nichts außerhalb des erwarteten Orts (z. B. flach statt im Thread)
        stray = [p for p in self.bot_posts(channel, sent, None)
                 if (p.get("root_id") or "") != reply_root]
        assert not stray, f"Antwort am falschen Ort: {stray[0]['message']!r}"
        assert "⚠️" not in text, f"Fehlerantwort: {text}"
        if expect is not None:
            assert expect(text), f"unerwartete Antwort: {text!r}"
        return text, secs

    def silent(self, user, channel, message, root_id=""):
        """Postet und prüft, dass Captain ``quiet`` Sekunden lang nirgends antwortet."""
        sent = self.post(user, channel, message, root_id)
        t0 = time.monotonic()
        while time.monotonic() - t0 < self.quiet:
            posts = self.bot_posts(channel, sent, None)
            assert not posts, f"unerwartete Antwort: {posts[0]['message']!r}"
            time.sleep(1)
        return f"still nach {self.quiet:.0f} s", time.monotonic() - t0

    def run(self) -> bool:
        e = self.env
        dm, group, familie = e["MM_DM_ALICE_BOT_ID"], e["MM_GROUP_DM_ID"], e["MM_CHANNEL_FAMILIE_ID"]
        has = lambda *words: lambda t: all(w in t.lower() for w in words)  # noqa: E731

        # -- DM: jede Nachricht, Antwort direkt im Chat
        self.check("DM: !neu", lambda: self.ask(
            "alice", dm, "!neu", expect=has("neue unterhaltung"), timeout=30))
        self.check("DM: !help", lambda: self.ask(
            "alice", dm, "!help", expect=has("!neu", "!modell", "@captain"), timeout=30))
        self.check("DM: Kontext 1/2 (Merk dir 42)", lambda: self.ask(
            "alice", dm, "Merk dir die Zahl 42. Antworte nur mit OK."))
        self.check("DM: Kontext 2/2 (Welche Zahl?)", lambda: self.ask(
            "alice", dm, "Welche Zahl solltest du dir merken? Antworte nur mit der Zahl.",
            expect=has("42")))

        # -- Privater Kanal: nur bei Erwähnung, Antwort im Thread
        self.check("Kanal: ohne Mention → still", lambda: self.silent(
            "alice", familie, "Kleine Info für alle: Mein Lieblingsobst ist die Mango."))
        self.check("Kanal: !help ohne Mention → still", lambda: self.silent(
            "bob", familie, "!help"))
        mention: dict = {}
        self.check("Kanal: Mention → Thread-Antwort mit Vorgeschichte", lambda: self.ask(
            "bob", familie,
            "@captain Was ist Alices Lieblingsobst? Antworte mit einem Wort.",
            thread=True, sent_to=mention, expect=has("mango")))
        self.check("Kanal: Folgeantwort im Thread ohne Mention", lambda: self.ask(
            "alice", familie,
            "Über welches Obst sprechen wir hier gerade? Antworte mit einem Wort.",
            root_id=mention["id"], expect=has("mango")))
        self.check("Kanal: @captain !help → Thread", lambda: self.ask(
            "bob", familie, "@captain !help", thread=True,
            expect=has("!neu", "!modell"), timeout=30))

        # -- Gruppen-DM: wie Kanal
        self.check("Gruppen-DM: ohne Mention → still", lambda: self.silent(
            "alice", group, "Ich bin Alice, und mein Lieblingstier ist die Katze."))
        gmention: dict = {}
        self.check("Gruppen-DM: Mention → Thread-Antwort mit Vorgeschichte", lambda: self.ask(
            "bob", group, "@Captain welches Tier mag Alice am liebsten? Antworte mit einem Wort.",
            thread=True, sent_to=gmention, expect=has("katze")))

        def group_queue():
            # zwei Thread-Antworten ohne Erwähnung direkt hintereinander: die
            # zweite wartet (⏳) und wird nach der ersten Antwort abgearbeitet
            root = gmention["id"]
            first = self.post("alice", group, "Hallo Captain, ich bin Alice.", root)
            second = self.post(
                "bob", group, "Und ich bin Bob. Wie heißen wir beide? Antworte in einem Satz.", root)
            # Normalfall: zwei Antworten nacheinander. Kam bobs Post an, bevor
            # die erste Ausführung startete, gibt es eine gebündelte Antwort –
            # dann gilt „fertig“, wenn sich 15 s nichts mehr tut.
            state = {"msgs": None, "since": time.monotonic()}

            def settled(msgs):
                if msgs != state["msgs"]:
                    state.update(msgs=msgs, since=time.monotonic())
                return len(msgs) >= 2 or time.monotonic() - state["since"] > 15

            text, secs = self.wait_reply(group, first, root, until=settled)
            posts = self.bot_posts(group, first, None)
            assert all(p.get("root_id") == root for p in posts), "Antwort außerhalb des Threads"
            assert all("⚠️" not in p["message"] for p in posts), text
            assert posts[-1]["create_at"] > second["create_at"], "bobs Post unbeantwortet"
            assert "bob" in text.lower(), f"Antwort ignoriert bob: {text!r}"
            return f"{len(posts)} Antwort(en), zuletzt: {text}", secs

        self.check("Gruppen-DM: Thread-Warteschlange alice+bob", group_queue)

        return self.summary()

    # -- Neustart: verpasste Posts nachholen ------------------------------------

    def compose(self, project: str, *args: str) -> None:
        cmd = ["docker", "compose", "-p", project, "-f", os.path.join(ROOT, "compose.yml"), *args]
        print("  $", " ".join(cmd), flush=True)
        subprocess.run(cmd, check=True, cwd=ROOT)

    def run_restart(self, project: str) -> bool:
        e = self.env
        dm, familie = e["MM_DM_ALICE_BOT_ID"], e["MM_CHANNEL_FAMILIE_ID"]
        has = lambda *words: lambda t: all(w in t.lower() for w in words)  # noqa: E731

        # Bot läuft und hat seine Cursor-Datei (sonst wäre der nächste Start ein
        # „erster Start“ ohne Nachholen): einmal live antworten lassen.
        self.check("Vorher: Bot antwortet live", lambda: self.ask(
            "alice", dm, "!help", expect=has("!neu"), timeout=120))
        time.sleep(3)  # gedrosselter Cursor ist spätestens jetzt geschrieben
        self.compose(project, "stop", "captain")
        sent: dict[str, dict] = {}
        sent["dm"] = self.post(
            "alice", dm, "Neustart-Test: Wie viel ist 2 plus 3? Antworte nur mit der Zahl.")
        sent["ch"] = self.post(
            "alice", familie,
            "@captain Neustart-Test: Wie viel ist 4 plus 4? Antworte nur mit der Zahl.")
        time.sleep(2)
        self.compose(project, "start", "captain")

        def late(key, channel, root, expect):
            def fn():
                text, secs = self.wait_reply(channel, sent[key], root)
                assert LATE in text.lower(), f"ohne Verspätungshinweis: {text!r}"
                assert "⚠️" not in text, f"Fehlerantwort: {text}"
                assert expect in text, f"unerwartete Antwort: {text!r}"
                posts = self.bot_posts(channel, sent[key], None)
                assert len(posts) == 1, f"{len(posts)} Antworten statt einer"
                return text, secs
            return fn

        self.check("Neustart: DM nachgeholt mit Hinweis", late("dm", dm, "", "5"))
        self.check("Neustart: Kanal-Mention nachgeholt im Thread mit Hinweis",
                   late("ch", familie, sent["ch"]["id"], "8"))
        self.check("Nachher: live ohne Hinweis", lambda: self.ask(
            "alice", dm, "!help", expect=lambda t: LATE not in t.lower(), timeout=60))

        # -- Stopp mitten in einer Antwort
        streaming: dict = {}

        def stop_while_streaming():
            sent["long"] = self.post(
                "alice", dm, "Neustart-Test: Zähle von 1 bis 60, jede Zahl in einer eigenen Zeile, "
                "ohne weiteren Text.")
            t0 = time.monotonic()
            while time.monotonic() - t0 < self.timeout:
                posts = self.bot_posts(dm, sent["long"], "")
                if posts and posts[-1]["message"].rstrip().endswith(CURSOR):
                    streaming.update(posts[-1])
                    break
                time.sleep(0.3)
            else:
                raise TimeoutError("keine streamende Antwort gesehen")
            self.compose(project, "stop", "captain")
            (partial,) = [p for p in self.bot_posts(dm, sent["long"], "") if p["id"] == streaming["id"]]
            assert INTERRUPTED in partial["message"], f"nicht als unterbrochen markiert: {partial['message']!r}"
            self.compose(project, "start", "captain")
            return f"gestoppt bei {len(streaming['message'])} Zeichen", time.monotonic() - t0

        def answered_once():
            t0 = time.monotonic()
            while time.monotonic() - t0 < self.timeout:
                posts = self.bot_posts(dm, sent["long"], "")
                if not any(p["message"].rstrip().endswith(CURSOR) for p in posts):
                    finals = [p for p in posts if not unfinished(p["message"])]
                    if finals:
                        assert len(finals) == 1, f"{len(finals)} fertige Antworten statt einer"
                        text = finals[0]["message"]
                        assert LATE in text.lower(), f"ohne Verspätungshinweis: {text!r}"
                        assert "60" in text, f"unvollständig: {text!r}"
                        return text, time.monotonic() - t0
                time.sleep(1)
            raise TimeoutError("keine fertige Antwort nach dem Neustart")

        self.check("Stopp während Antwort streamt → Zwischenstand unterbrochen", stop_while_streaming)
        if streaming:
            self.check("Neustart: unterbrochene Antwort genau einmal nachgeholt", answered_once)
            time.sleep(5)  # keine zweite Antwort hinterher
            self.check("Neustart: keine Doppelantwort", lambda: (
                self._single_final(dm, sent["long"]), 0.0))
        return self.summary()

    # -- Anhänge (#22) ----------------------------------------------------------

    def upload(self, user: str, channel_id: str, name: str, data: bytes) -> str:
        """``POST /files`` (multipart) → File-ID."""
        boundary = f"----captain{uuid.uuid4().hex}"
        body = b"".join([
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"channel_id\"\r\n\r\n"
            f"{channel_id}\r\n".encode(),
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"files\"; "
            f"filename=\"{name}\"\r\nContent-Type: application/octet-stream\r\n\r\n".encode(),
            data, f"\r\n--{boundary}--\r\n".encode(),
        ])
        req = urllib.request.Request(self.c.url + "/api/v4/files", data=body, method="POST")
        req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
        req.add_header("Authorization", "Bearer " + self.tokens[user])
        with urllib.request.urlopen(req, timeout=120) as resp:
            return json.loads(resp.read())["file_infos"][0]["id"]

    def ask_file(self, user, channel, message, name, data, *, thread=False, sent_to=None,
                 expect=None):
        """Wie :meth:`ask`, aber mit Anhang; Fehlerantworten (⚠️) bleiben erlaubt."""
        fid = self.upload(user, channel, name, data)
        sent = self.c.post("/posts", {"channel_id": channel, "message": message,
                                      "file_ids": [fid]}, token=self.tokens[user])
        if sent_to is not None:
            sent_to.update(sent)
        root = sent["id"] if thread else ""
        text, secs = self.wait_reply(channel, sent, root)
        if expect is not None:
            assert expect(text), f"unerwartete Antwort: {text!r}"
        return text, secs

    def container(self, *cmd: str) -> str:
        """Befehl im Bot-Container (``docker compose exec``)."""
        full = ["docker", "compose", "-p", self.project, "-f", os.path.join(ROOT, "compose.yml"),
                "exec", "-T", "captain", *cmd]
        return subprocess.run(full, check=True, cwd=ROOT, capture_output=True,
                              text=True, encoding="utf-8").stdout

    def session_of(self, key: str) -> dict:
        store = json.loads(self.container("cat", "/data/sessions.json"))
        assert key in store, f"keine Session für {key}"
        return store[key]

    def in_session_dir(self, key: str, name: str) -> tuple[str, float]:
        """Liegt ``name`` im Verzeichnis der Session ``key`` (``/tmp/captain/<id>/``)?"""
        entry = self.session_of(key)
        directory, sid = entry["directory"], entry["opencode_session_id"]
        assert directory == f"/tmp/captain/{sid}", f"unerwartetes Verzeichnis {directory}"
        found = self.container("find", "/tmp/captain", "-name", name).split()
        assert found == [f"{directory}/{name}"], f"{name} liegt in {found}"
        return f"{directory}/{name}", 0.0

    def oc_messages(self, session_id: str) -> list[dict]:
        """Nachrichten einer opencode-Session (neueste zuerst), für Befunde."""
        req = urllib.request.Request(
            f"{self.oc_url}/api/session/{session_id}/message?limit=20&order=desc")
        if self.oc_password:
            cred = base64.b64encode(f"opencode:{self.oc_password}".encode()).decode()
            req.add_header("Authorization", "Basic " + cred)
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read()).get("data") or []

    def tools_since(self, key: str, after_ms: int) -> list[str]:
        """Tool-Aufrufe der Session ``key`` seit ``after_ms`` (z. B. ``read``)."""
        sid = self.session_of(key)["opencode_session_id"]
        tools = []
        for msg in self.oc_messages(sid):
            if (msg.get("time") or {}).get("created", 0) < after_ms:
                continue
            for part in msg.get("content") or []:
                if part.get("type") in ("tool", "tool-call", "tool_call"):
                    tools.append(str(part.get("name") or part.get("tool") or "?"))
        return tools

    def finding(self, name: str, fn) -> None:
        """Wie :meth:`check`, aber für Befunde: nur Zeitüberschreitung/Ausnahme ist rot."""
        self.check(name, fn)
        self.findings.append(self.results[-1])

    def run_attachments(self) -> bool:
        e = self.env
        dm, familie = e["MM_DM_ALICE_BOT_ID"], e["MM_CHANNEL_FAMILIE_ID"]
        has = lambda *words: lambda t: all(w in t.lower() for w in words)  # noqa: E731
        tag = uuid.uuid4().hex[:6]  # eindeutige Dateinamen je Lauf
        png, txt, pdf = make_png_seven(), TEXT_FILE.encode(), make_pdf(PDF_TEXT)

        self.check("DM: !neu", lambda: self.ask(
            "alice", dm, "!neu", expect=has("neue unterhaltung"), timeout=30))

        # -- Bild
        self.finding("DM: Bild (rotes Quadrat mit 7)", lambda: self.ask_file(
            "alice", dm, IMAGE_QUESTION, f"bild_{tag}.png", png))
        self.check("DM: Bild liegt im Session-Verzeichnis", lambda: self.in_session_dir(
            f"dm:{dm}", f"bild_{tag}.png"))
        if self.vision_model:  # Gegenprobe mit einem anderen (multimodalen) Modell
            self.check(f"DM: !modell {self.vision_model}", lambda: self.ask(
                "alice", dm, f"!modell {self.vision_model}", timeout=60))
            self.finding(f"DM: Bild mit {self.vision_model}", lambda: self.ask_file(
                "alice", dm, IMAGE_QUESTION, f"bild2_{tag}.png", png))
            self.check("DM: !modell standard", lambda: self.ask(
                "alice", dm, "!modell standard", timeout=60))

        # -- Textdatei + Folgefrage ohne Anhang
        self.check("DM: Textdatei", lambda: self.ask_file(
            "alice", dm, TEXT_QUESTION, f"notiz_{tag}.txt", txt, expect=has("kolibri-8253")))
        self.check("DM: Textdatei liegt im Session-Verzeichnis", lambda: self.in_session_dir(
            f"dm:{dm}", f"notiz_{tag}.txt"))
        follow_ms = int(time.time() * 1000) - 5000  # Uhren Host/Container ±

        def follow_up():
            text, secs = self.ask(
                "alice", dm, f"Lies die Datei notiz_{tag}.txt noch einmal mit deinem "
                "read-Werkzeug und nenne nur die Lieblingsfarbe von Alice.", expect=has("ocker"))
            tools = self.tools_since(f"dm:{dm}", follow_ms)
            return f"[Tools: {', '.join(tools) or 'keine'}] {text}", secs

        self.check("DM: Folgefrage ohne Anhang (read)", follow_up)

        # -- PDF: nur Befund
        self.finding("DM: PDF", lambda: self.ask_file(
            "alice", dm, PDF_QUESTION, f"losung_{tag}.pdf", pdf))

        # -- Kanal: @-Mention mit Anhang → Antwort im Thread
        self.finding("Kanal: Bild per @captain", lambda: self.ask_file(
            "alice", familie, "@captain " + IMAGE_QUESTION, f"kbild_{tag}.png", png,
            thread=True))
        sent: dict = {}
        self.check("Kanal: Textdatei per @captain", lambda: self.ask_file(
            "alice", familie, "@captain " + TEXT_QUESTION, f"knotiz_{tag}.txt", txt,
            thread=True, sent_to=sent, expect=has("kolibri-8253")))
        if sent:
            self.check("Kanal: Textdatei liegt im Session-Verzeichnis", lambda: self.in_session_dir(
                f"th:{sent['id']}", f"knotiz_{tag}.txt"))
        self.finding("Kanal: PDF per @captain", lambda: self.ask_file(
            "alice", familie, "@captain " + PDF_QUESTION, f"klosung_{tag}.pdf", pdf,
            thread=True))

        # -- Größengrenze (MAX_ATTACHMENT_MB, Default 20)
        big = b"\0" * ((self.max_mb + 1) * 1024 * 1024)
        self.check(f"DM: Anhang > {self.max_mb} MB wird nicht geladen", lambda: self.ask_file(
            "alice", dm, "Was steht in dieser Datei?", f"gross_{tag}.bin", big,
            expect=has("nicht geladen", f"{self.max_mb} mb")))
        self.check("DM: großer Anhang nicht im Session-Verzeichnis", lambda: (
            self._absent(f"gross_{tag}.bin"), 0.0))

        ok = self.summary()
        print("\nBefunde (Antwort gekürzt, Zeit):")
        for name, _, secs, short in self.findings:
            print(f"  {name}: {classify(name, short)} ({secs:.1f} s) – {short}")
        return ok

    def _absent(self, name: str) -> str:
        found = self.container("find", "/tmp/captain", "-name", name).split()
        assert not found, f"{name} liegt doch in {found}"
        return "nicht vorhanden"

    def _single_final(self, channel: str, after: dict) -> str:
        finals = [p for p in self.bot_posts(channel, after, "") if not unfinished(p["message"])]
        assert len(finals) == 1, f"{len(finals)} fertige Antworten"
        return "genau eine fertige Antwort"

    def summary(self) -> bool:
        print()
        passed = sum(ok for _, ok, _, _ in self.results)
        width = max(len(n) for n, *_ in self.results)
        for name, ok, secs, _ in self.results:
            print(f"  {'OK  ' if ok else 'FAIL'} {name:<{width}}  {secs:6.1f} s")
        print(f"\n{passed}/{len(self.results)} bestanden")
        return passed == len(self.results)


# -- Testdateien (nur stdlib) ---------------------------------------------------

IMAGE_QUESTION = ("Beschreibe das angehängte Bild in einem Satz: Welche Farbe hat die Form, "
                  "welche Form ist es und welche Zahl steht darin?")
TEXT_FILE = ("Notiz für Captain\n"
             "Passwort: Kolibri-8253\n"
             "Lieblingsfarbe von Alice: Ocker\n"
             "Anzahl Katzen: 3\n")
TEXT_QUESTION = "Welches Passwort steht in der angehängten Datei? Antworte nur mit dem Passwort."
PDF_TEXT = "Die Losung lautet Seestern-5190."
PDF_QUESTION = "Wie lautet die Losung im angehängten PDF? Antworte nur mit der Losung."

_SEVEN = ("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#...")


def make_png(width: int, height: int, pixel) -> bytes:
    """PNG (RGB, 8 bit) aus ``pixel(x, y) -> (r, g, b)``."""
    rows = b"".join(
        b"\0" + b"".join(bytes(pixel(x, y)) for x in range(width)) for y in range(height))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + kind + data
                + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))


def make_png_seven(size: int = 256) -> bytes:
    """Weißer Hintergrund, rotes Quadrat, darin eine große weiße 7."""
    margin, scale = size // 8, size // 14
    ox, oy = (size - 5 * scale) // 2, (size - 7 * scale) // 2

    def pixel(x, y):
        gx, gy = (x - ox) // scale, (y - oy) // scale
        if 0 <= gx < 5 and 0 <= gy < 7 and x >= ox and y >= oy and _SEVEN[gy][gx] == "#":
            return (255, 255, 255)
        if margin <= x < size - margin and margin <= y < size - margin:
            return (220, 20, 20)
        return (255, 255, 255)

    return make_png(size, size, pixel)


def make_pdf(text: str) -> bytes:
    """Einseitiges PDF (Helvetica) mit einer Textzeile."""
    stream = f"BT /F1 18 Tf 72 720 Td ({text}) Tj ET".encode("latin-1")
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Length %d >>\nstream\n%s\nendstream" % (len(stream), stream),
    ]
    out, offsets = bytearray(b"%PDF-1.4\n"), []
    for i, obj in enumerate(objects, 1):
        offsets.append(len(out))
        out += b"%d 0 obj\n%s\nendobj\n" % (i, obj)
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
    out += b"".join(b"%010d 00000 n \n" % o for o in offsets)
    out += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (
        len(objects) + 1, xref)
    return bytes(out)


def classify(name: str, answer: str) -> str:
    """Grobe Einordnung einer Befund-Antwort."""
    low = answer.lower()
    if "⚠️" in answer or "fehler" in low:
        return "Fehler"
    if "pdf" in name.lower():
        return "verstanden" if "seestern-5190" in low else "nicht verstanden"
    return "verstanden" if ("rot" in low or "red" in low) and "7" in low else "nicht verstanden"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--env", default=os.path.join(ROOT, "infra", "mattermost", "generated.env"))
    ap.add_argument("--timeout", type=float, default=300.0,
                    help="Sekunden pro Antwort (Default 300, CPU-Modell braucht beim Kaltstart ~2 min)")
    ap.add_argument("--quiet", type=float, default=15.0,
                    help="Sekunden, die Captain bei nicht adressierten Posts still sein muss")
    ap.add_argument("--restart", action="store_true",
                    help="nur den Neustart-Test (stoppt und startet den Bot-Container)")
    ap.add_argument("--attachments", action="store_true",
                    help="nur den Anhang-Test (Bild, Textdatei, PDF, Größengrenze)")
    ap.add_argument("--project", default="captain",
                    help="Compose-Projekt (für --restart/--attachments)")
    ap.add_argument("--opencode-env", default=os.path.join(ROOT, "infra", "opencode", ".env"),
                    help="Passwort für die opencode-API (Befund Tool-Aufrufe, --attachments)")
    ap.add_argument("--opencode-url", default="http://localhost:4096")
    ap.add_argument("--max-mb", type=int, default=20,
                    help="MAX_ATTACHMENT_MB des Bots (für --attachments)")
    ap.add_argument("--vision-model", default="",
                    help="Bild zusätzlich mit diesem Modell prüfen (z. B. ollama/gemma3:4b)")
    args = ap.parse_args()
    env = read_env(args.env)
    if not env.get("MM_BOT_TOKEN"):
        print(f"Keine Seed-Daten in {args.env} – erst infra/mattermost/seed.py ausführen.")
        return 2
    e2e = E2E(env, args.timeout, args.quiet)
    e2e.project, e2e.oc_url, e2e.max_mb = args.project, args.opencode_url, args.max_mb
    e2e.vision_model = args.vision_model
    e2e.oc_password = read_env(args.opencode_env).get("OPENCODE_SERVER_PASSWORD", "")
    if args.restart:
        ok = e2e.run_restart(args.project)
    elif args.attachments:
        ok = e2e.run_attachments()
    else:
        ok = e2e.run()
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
