"""Captain-Bot: Mattermost-Posts → opencode-Sessions → Antwort als Live-Post.

Ablauf
------
``MattermostClient.listen`` ruft :meth:`Bot.on_post` im WebSocket-Thread auf.
Dort wird nur gefiltert (Bots/Webhooks, Allowlist, Duplikate, nicht an Captain
gerichtet – siehe :mod:`captain.routing`) und der Post an eine **Spur** pro
Session-Key übergeben; die eigentliche Arbeit läuft in einem Thread-Pool.

In Kanälen und Gruppen-DMs ist jede Unterhaltung ein Thread (``th:<root>``):
Eine Top-Level-Erwähnung öffnet einen Thread unter dem Post; dessen neue
Session bekommt die Kanal-Posts seit Captains letzter Beteiligung als
Vorgeschichte.

Pro Session-Key gibt es höchstens einen aktiven Abarbeiter (zusätzlich
abgesichert über :func:`captain.sessions.channel_lock`), denn opencode stellt
einen zweiten Prompt während einer laufenden Ausführung per ``steer`` zu und
würde ihn in die laufende Antwort hineinlenken. Verschiedene Keys laufen
parallel.

Warteschlange und Bündelung
---------------------------
Nachrichten, die eintreffen, während eine Antwort läuft, gehen nicht verloren:
Sie bekommen eine ⏳-Reaktion und werden nach der laufenden Antwort
abgearbeitet – und zwar **gebündelt** in *einem* Prompt. Begründung: Das
lokale Modell braucht pro Ausführung spürbar Zeit; in einem Gruppenchat mit
mehreren Beiträgen wäre eine Antwort pro Beitrag langsam und inhaltlich
überholt, sobald sie erscheint. Ein Prompt mit allen Beiträgen (``Name: Text``
je Zeile) liefert eine zusammenhängende Antwort auf den aktuellen Stand.
``!neu`` bleibt in der Reihenfolge (trennt Bündel), die übrigen Befehle wirken
sofort.

Nachholen
---------
Nach dem Start und nach jedem Reconnect ohne Replay holt :meth:`Bot.catch_up`
verpasste Posts per REST nach (Cursor pro Kanal, :mod:`captain.cursors`);
Live-Posts werden solange gepuffert. Details in :mod:`captain.catchup`.
"""

from __future__ import annotations

import logging
import os
import shutil
import threading
import time
from collections import OrderedDict, deque
from concurrent.futures import ThreadPoolExecutor

from .catchup import (
    INTERRUPTED_NOTE,
    LATE_NOTE,
    answered_in,
    answers_props,
    fetch_after,
    order_missed,
    stale_partials,
    start_for_new,
)
from .config import Config
from .cursors import CursorStore
from .mattermost import AttachmentTooLarge, MattermostClient, Post, PostStreamer, post_from_dict
from .opencode import Interrupted, OpencodeClient, OpencodeError, SessionGone, session_permissions
from .routing import (
    channel_history,
    conversation_key,
    reply_root,
    settings_key,
    should_answer,
    strip_mention,
)
from .sessions import SessionStore, channel_lock, new_session_id, session_instructions

log = logging.getLogger(__name__)

CURSOR = " ▌"  # hängt an jedem Zwischenstand; der Endstand hat ihn nie
QUEUED_EMOJI = "hourglass_flowing_sand"
PROMPT_IDLE_TIMEOUT = 600.0  # Sekunden ohne Event, bevor abgebrochen wird
THREAD_CONTEXT_POSTS = 20  # so viele Thread-Posts gehen in eine neue Thread-Session
THREAD_CONTEXT_CHARS = 2000  # pro Post
THREADS_MAX = 5000  # so viele Thread-Roots mit Captain-Beteiligung merken
NO_THREAD_MAX = 1000  # Negativ-Cache „Captain nicht im Thread“ …
NO_THREAD_TTL = 300.0  # … und wie lange ein Eintrag gilt (Sekunden)
LATE_MAX = 1000  # so viele nachgeholte Post-IDs für den Verspätungshinweis merken
SEEN_MAX = 20000  # Dedupe live/nachgeholt (dazu die gespeicherten beantworteten IDs)
CATCHUP_BACKOFF = (1.0, 60.0)  # Wiederholung eines gescheiterten Nachholens (Start, max)
STOP_GRACE = 10.0  # Sekunden, die laufende Antworten beim Beenden für ihre Notiz bekommen
ABORT_TIMEOUT = 3.0  # Sekunden für alle opencode-Abbrüche beim Beenden zusammen

HELP = """\
**Captain** – ich reiche Nachrichten an opencode weiter und antworte hier.

**Wann ich antworte**
- Direktnachricht: auf jede Nachricht, direkt im Chat.
- Kanal oder Gruppe: nur wenn du mich mit `@{name}` erwähnst – ich antworte \
im Thread unter deinem Post und kenne die Kanal-Nachrichten seit meiner letzten Antwort dort.
- Thread, in dem ich schon geschrieben habe: auf jede Antwort, ohne Erwähnung.

**Befehle** (in Kanälen und Gruppen oben mit Erwähnung, z. B. `@{name} !help`)
- `!neu` – neue Unterhaltung (Verlauf vergessen, Modell bleibt); im Thread nur für den \
Thread – in Kanälen beginnt jede Erwähnung oben ohnehin eine neue
- `!modell` – verfügbare Modelle anzeigen
- `!modell <provider/modell> [variante]` – Modell setzen (in Kanälen oben: für den \
ganzen Kanal, im Thread: nur für den Thread)
- `!modell standard` – zurück zum Standardmodell
- `!stopp` – laufende Antwort abbrechen, Wartende verwerfen (in Kanälen oben: alle im Kanal)
- `!help` / `!hilfe` – diese Hilfe

Nachrichten, die während einer Antwort kommen (⏳), beantworte ich danach gesammelt."""

COMMANDS = {
    "!neu": "neu", "!new": "neu", "!reset": "neu",
    "!hilfe": "hilfe", "!help": "hilfe",
    "!modell": "modell", "!model": "modell",
    "!stopp": "stopp", "!stop": "stopp",
}
QUEUED_COMMANDS = {"neu"}  # wirken erst nach allem, was vorher kam


def _mb(size: int) -> str:
    """Bytes → MB mit höchstens einer Nachkommastelle, deutsches Komma."""
    return f"{size / (1024 * 1024):.1f}".removesuffix(".0").replace(".", ",")


def parse_command(text: str) -> tuple[str, str] | None:
    """``"!modell a b"`` → ``("modell", "a b")``; ``None`` für normalen Text."""
    if not text.startswith("!"):
        return None
    word, _, rest = text.strip().partition(" ")
    name = COMMANDS.get(word.lower())
    return (name, rest.strip()) if name else None


def display_name(user: dict) -> str:
    """Anzeigename wie in Mattermost: Spitzname, sonst Vor-/Nachname, sonst Username."""
    full = " ".join(p for p in (user.get("first_name"), user.get("last_name")) if p)
    return user.get("nickname") or full or user.get("username") or "?"


class _Live:
    """Zwischenstände einer Antwort: Text + Tool-Status, Typing, Nachreichen.

    Ein Ticker-Thread schickt bis zum ersten Text regelmäßig den
    Typing-Indicator und reicht gedrosselte Zwischenstände nach, damit ein
    Post nicht auf einem veralteten Stand stehen bleibt (z. B. vor einem
    langen Tool-Lauf).
    """

    def __init__(
        self,
        mm: MattermostClient,
        streamer: PostStreamer,
        channel_id: str,
        root_id: str | None,
        *,
        typing_interval: float = 4.0,
        flush_interval: float = 1.0,
    ):
        self.mm = mm
        self.streamer = streamer
        self.channel_id = channel_id
        self.root_id = root_id
        self.typing_interval = typing_interval
        self.flush_interval = flush_interval
        self.text = ""
        self.status = ""
        self.prefix = ""  # z. B. Verspätungshinweis, steht vor jedem Zwischenstand
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._tick, name="captain-live", daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread.is_alive() and self._thread is not threading.current_thread():
            self._thread.join(timeout=5)

    def reset(self) -> None:
        with self._lock:
            self.text = ""
            self.status = ""

    def on_delta(self, text: str) -> None:
        with self._lock:
            self.text = text
            self.status = ""  # Text nach einem Tool-Lauf ersetzt den Status
            self._push()

    def on_status(self, status: str) -> None:
        with self._lock:
            self.status = status
            self._push()

    def render(self) -> str:
        body = self.text
        if self.status:
            body = f"{body}\n\n_{self.status}_" if body else f"_{self.status}_"
        return self.prefix + body + CURSOR

    def _push(self) -> None:
        if self._stop.is_set():
            return
        if self.text:
            self.streamer.update(self.render())
        elif self.status:
            self.streamer.status(self.render())

    def _tick(self) -> None:
        last_typing = 0.0
        while not self._stop.wait(self.flush_interval):
            try:
                with self._lock:
                    if self.streamer.post_ids:
                        self._push()  # gedrosselt; unverändert → kein Request
                    has_text = bool(self.text)
                now = time.monotonic()
                if not has_text and now - last_typing >= self.typing_interval:
                    last_typing = now
                    self.mm.typing(self.channel_id, self.root_id)
            except Exception as e:  # noqa: BLE001 – nur Kosmetik
                log.debug("Live-Aktualisierung fehlgeschlagen: %s", e)


class Bot:
    def __init__(
        self,
        cfg: Config,
        mm: MattermostClient,
        oc: OpencodeClient,
        store: SessionStore,
        *,
        workers: int = 8,
        prompt_timeout: float = PROMPT_IDLE_TIMEOUT,
        cursors: CursorStore | None = None,
    ):
        self.cfg = cfg
        self.mm = mm
        self.oc = oc
        self.store = store
        self.cursors = cursors or CursorStore(
            os.path.join(cfg.data_dir, "cursors.json"), answered_ttl=cfg.catchup_max_age
        )
        self.prompt_timeout = prompt_timeout
        self._pool = ThreadPoolExecutor(workers, thread_name_prefix="captain-session")
        self._side = ThreadPoolExecutor(2, thread_name_prefix="captain-side")
        self._mutex = threading.Lock()
        self._lanes: dict[str, deque[Post]] = {}
        self._active: set[str] = set()  # Keys mit laufendem Abarbeiter
        self._no_instructions: set[str] = set()  # Sessions ohne Systemanweisungen (API-Fehler)
        self._running: dict[str, str] = {}  # Key → opencode-Session der laufenden Antwort
        self._queued: set[str] = set()  # Post-IDs mit ⏳-Reaktion
        self._seen: OrderedDict[str, None] = OrderedDict()
        self._channels: dict[str, str] = {}  # aktiver Key → Kanal (für !stopp im Kanal)
        # Thread-Beteiligung: positiv (LRU) und negativ (LRU mit Ablaufzeit)
        self._threads: OrderedDict[str, None] = OrderedDict()
        self._no_thread: OrderedDict[str, float] = OrderedDict()
        # Nachschlagen per REST außerhalb des WebSocket-Threads, in Reihenfolge
        self._lookup = ThreadPoolExecutor(1, thread_name_prefix="captain-lookup")
        self._pending: dict[str, int] = {}  # Root → Posts, die aufs Nachschlagen warten
        self._stopping = threading.Event()
        # Nachholen: Live-Posts puffern, solange es läuft (siehe _on_hello)
        self._buf_lock = threading.Lock()
        self._buffering = False
        self._catchup_again = False
        self._buffer: deque[Post] = deque()
        self._late: OrderedDict[str, None] = OrderedDict()  # nachgeholte Post-IDs
        self.catchup_done = threading.Event()  # Nachholen samt Puffer (erstmals) fertig
        self.catchup_ok = threading.Event()  # letztes Nachholen vollständig
        self._wakeup = threading.Event()
        self._worker: threading.Thread | None = None
        self.catchup_backoff = CATCHUP_BACKOFF
        self._clock = lambda: int(time.time() * 1000)  # ms, für Tests austauschbar
        self._started_ms = self._clock()

    # -- Lebenszyklus --------------------------------------------------------

    def wait_for_mattermost(self, timeout: float | None = None) -> dict | None:
        """Wartet, bis Mattermost den Bot-Token akzeptiert; liefert den Bot-User.

        ``None``, falls vorher :meth:`stop` aufgerufen wurde.
        """
        deadline = None if timeout is None else time.monotonic() + timeout
        delay = 1.0
        while True:
            try:
                return self.mm.me
            except Exception as e:  # noqa: BLE001 – Server startet evtl. noch
                if deadline is not None and time.monotonic() >= deadline:
                    raise
                log.info("Mattermost noch nicht erreichbar (%s), neuer Versuch in %.0f s", e, delay)
                if self._stopping.wait(delay):
                    return None
                delay = min(delay * 2, 15.0)

    def run(self) -> None:
        """Blockiert bis :meth:`stop`."""
        me = self.wait_for_mattermost()
        if me is None:
            return
        os.makedirs(self.cfg.sessions_dir, exist_ok=True)
        log.info("Captain läuft als @%s (%s)", me.get("username"), me.get("id"))
        self.mm.listen(self._on_ws_post, on_hello=self._on_hello)
        self.wait_quiet(STOP_GRACE)

    def stop(self) -> None:
        """Beenden: Cursor sichern, laufende Antworten abbrechen.

        Offene Posts (Warteschlange, Nachschlagen, laufende Antwort) gelten
        danach nicht als beantwortet – der Cursor bleibt davor, der nächste
        Start holt sie nach. Abgebrochene Antworten enden mit
        :data:`INTERRUPTED_NOTE`.
        """
        self._stopping.set()
        self._wakeup.set()
        self.cursors.close()  # vor allem Weiteren: späteres „erledigt“ zählt nicht
        self.mm.close()
        self._abort_all(list(self._running.values()))
        self._pool.shutdown(wait=False, cancel_futures=True)
        self._side.shutdown(wait=False, cancel_futures=True)
        self._lookup.shutdown(wait=False, cancel_futures=True)

    def _abort_all(self, sessions: list[str]) -> None:
        """Laufende opencode-Ausführungen parallel abbrechen, höchstens ``ABORT_TIMEOUT``."""

        def abort(sid: str) -> None:
            try:
                self.oc.abort(sid)
            except Exception as e:  # noqa: BLE001
                log.debug("Abbruch von %s beim Beenden fehlgeschlagen: %s", sid, e)

        threads = [threading.Thread(target=abort, args=(sid,), daemon=True) for sid in sessions]
        for t in threads:
            t.start()
        deadline = time.monotonic() + ABORT_TIMEOUT
        for t in threads:
            t.join(max(0.0, deadline - time.monotonic()))

    # -- Nachholen -----------------------------------------------------------

    def _on_hello(self) -> None:
        """Neue WebSocket-Verbindung ohne Replay (WebSocket-Thread).

        Ab jetzt werden Live-Posts gepuffert; der Nachhol-Thread holt die
        Lücke per REST und arbeitet danach den Puffer ab. Kommt währenddessen
        ein weiteres ``hello``, wird danach noch einmal nachgeholt.
        """
        with self._buf_lock:
            self._catchup_again = True
            self._buffering = True
            if self._worker is None:
                self._worker = threading.Thread(
                    target=self._catchup_worker, name="captain-catchup", daemon=True)
                self._worker.start()
        self._wakeup.set()

    def _on_ws_post(self, post: Post) -> None:
        with self._buf_lock:
            if self._buffering:
                self._buffer.append(post)
                return
        self.on_post(post)

    def _catchup_worker(self) -> None:
        """Nachholen auf ``hello``; nach einem Fehlschlag Wiederholung mit Backoff.

        Bei der Wiederholung (ohne neues ``hello``) wird nicht gepuffert: Die
        betroffenen Cursor sind eingefroren, Live-Posts laufen normal weiter.
        """
        first, most = self.catchup_backoff
        delay = first
        retry = False
        while True:
            woke = self._wakeup.wait(delay if retry else None)
            if self._stopping.is_set():
                return
            self._wakeup.clear()
            if retry and not woke:
                log.info("Nachholen: neuer Versuch")
                with self._buf_lock:
                    self._catchup_again = True
            if self._catchup_pass():
                retry, delay = False, first
                self.catchup_ok.set()
            else:
                if retry:
                    delay = min(delay * 2, most)
                retry = True
                self.catchup_ok.clear()
                log.warning("Nachholen unvollständig – neuer Versuch in %.1f s", delay)

    def _catchup_pass(self) -> bool:
        """Nachholen, dann Puffer abarbeiten; ``True`` = vollständig nachgeholt."""
        ok = True
        drained = 0
        while True:
            with self._buf_lock:
                if self._stopping.is_set():
                    self._buffering = False
                    self._buffer.clear()
                    return ok
                if self._catchup_again:
                    self._catchup_again = False
                    post = None
                elif self._buffer:
                    post = self._buffer.popleft()
                else:
                    self._buffering = False  # ab jetzt wieder direkt
                    if drained:
                        log.info("Nachholen: %d gepufferte Live-Post(s) abgearbeitet", drained)
                    self.catchup_done.set()
                    return ok
                waiting = len(self._buffer)
            if post is None:
                if waiting:
                    log.info("Nachholen startet, %d Live-Post(s) gepuffert", waiting)
                try:
                    ok = self.catch_up()
                except Exception:  # noqa: BLE001
                    log.exception("Nachholen fehlgeschlagen")
                    ok = False
            else:
                drained += 1
                self._guarded(self.on_post, post)

    def catch_up(self) -> bool:
        """Holt verpasste Posts aller Kanäle nach; ``True`` = alle Kanäle geschafft.

        Blockiert (REST); läuft im Nachhol-Thread, nie im WebSocket-Thread.
        Kanäle, bei denen etwas schiefgeht, bleiben eingefroren (ihr Cursor
        wandert nicht über die Lücke) und werden beim nächsten Versuch geholt.
        """
        now = self._clock()
        try:
            channels = self.mm.my_channels()
        except Exception as e:  # noqa: BLE001
            log.warning("Nachholen: Kanalliste nicht ladbar (%s) – alle Cursor eingefroren", e)
            self.cursors.freeze_all()
            return False
        bot_id = self.mm.me.get("id", "")
        if not self.cursors.initialized:
            self.cursors.set_all([c["id"] for c in channels], now)
            log.info("Erster Start: Cursor für %d Kanäle auf jetzt – nichts nachgeholt", len(channels))
            return True
        max_age = int(self.cfg.catchup_max_age * 1000)
        oldest = now - max_age
        types = {c["id"]: c.get("type", "") for c in channels}
        # zuerst alle Cursor festhalten, dann erst das globale Einfrieren lösen
        cursors = {c["id"]: self.cursors.get(c["id"]) for c in channels}
        for cid, cursor in cursors.items():
            if cursor is not None:
                self.cursors.freeze(cid, cursor)
        self.cursors.unfreeze_all()
        fetched: dict[str, list[dict]] = {}
        starts: dict[str, int] = {}
        ok = True
        skipped = 0
        for ch in channels:
            if self._stopping.is_set():
                return False
            cid, cursor = ch["id"], cursors[ch["id"]]
            last = ch.get("last_post_at")
            if cursor is not None and isinstance(last, int) and last <= max(cursor, oldest):
                skipped += 1  # nichts Neues seit dem Cursor
                self.cursors.unfreeze(cid)
                continue
            try:
                if cursor is None:  # neu beigetreten (bzw. neue DM)
                    posts = self.mm.get_posts_after(cid, oldest)
                    start = start_for_new(ch, posts, bot_id, oldest)
                    posts = [p for p in posts if p.get("create_at", 0) > start]
                    log.info("Kanal %s ohne Cursor: nachholen ab %d (Beitritt bzw. Höchstalter)",
                             cid, start)
                else:
                    start, too_old = fetch_after(cursor, now, max_age)
                    if too_old:
                        log.warning(
                            "Kanal %s: Cursor %.1f h alt – Posts älter als %.1f h werden übersprungen",
                            cid, (now - cursor) / 3.6e6, max_age / 3.6e6,
                        )
                    posts = self.mm.get_posts_after(cid, start)
            except Exception as e:  # noqa: BLE001 – andere Kanäle trotzdem nachholen
                log.warning("Kanal %s nicht nachholbar (%s) – Cursor eingefroren", cid, e)
                ok = False
                continue
            fetched[cid] = posts
            starts[cid] = start
        everything = [p for posts in fetched.values() for p in posts]
        done = answered_in(everything, bot_id)
        passed = 0
        ordered = order_missed(everything)
        for raw in ordered:
            if self._stopping.is_set():
                return False
            cid = raw.get("channel_id", "")
            post = post_from_dict(raw, types.get(cid, ""), "", bot_id)
            if post is not None and not post.from_bot and self._unseen(post.id) \
                    and not self.cursors.is_answered(post.id) and self.addressed(post):
                if post.id in done:
                    log.info("Post %s ist schon beantwortet – übersprungen", post.id)
                    self.cursors.mark_answered(post.id)
                else:
                    post.sender_name = self._user(post.user_id).get("username", "")
                    self._mark_late(post.id)
                    self.on_post(post)  # hält den Post offen, bis er beantwortet ist
                    passed += 1
            self.cursors.advance(cid, int(raw.get("create_at") or 0))
        for cid in fetched:
            self.cursors.mark_known(cid, starts[cid])
            self.cursors.unfreeze(cid)
        self._close_partials(everything, bot_id)
        log.info(
            "Nachgeholt: %d Kanäle ohne Neues, %d verpasste Post(s) geprüft, %d an Captain "
            "gerichtet%s", skipped, len(ordered), passed, "" if ok else " – unvollständig",
        )
        return ok

    def _close_partials(self, posts: list[dict], bot_id: str) -> None:
        """Zwischenstände (``▌``) aus der Zeit vor dem Start als abgebrochen markieren."""
        for p in stale_partials(posts, bot_id, self._started_ms):
            text = p["message"].rstrip().removesuffix(CURSOR.strip()).rstrip()
            text = f"{text}\n\n{INTERRUPTED_NOTE}" if text else INTERRUPTED_NOTE
            try:
                self.mm.update_post(p["id"], text)
            except Exception as e:  # noqa: BLE001 – nur Kosmetik
                log.debug("Zwischenstand %s nicht korrigierbar: %s", p.get("id"), e)

    def wait_quiet(self, timeout: float) -> None:
        """Wartet (höchstens ``timeout`` s), bis keine Antwort mehr läuft."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            with self._mutex:
                if not self._active:
                    return
            time.sleep(0.05)

    def _unseen(self, post_id: str) -> bool:
        with self._mutex:
            return post_id not in self._seen

    def _mark_late(self, post_id: str) -> None:
        with self._mutex:
            self._late[post_id] = None
            while len(self._late) > LATE_MAX:
                self._late.popitem(last=False)

    def _take_late(self, posts: list[Post]) -> bool:
        """Ist einer der Posts nachgeholt? (Markierung wird verbraucht)"""
        with self._mutex:
            late = [p.id for p in posts if p.id in self._late]
            for pid in late:
                del self._late[pid]
        return bool(late)

    def _hold(self, post: Post) -> None:
        """Adressierter Post angenommen: Cursor bleibt davor, bis :meth:`_done`."""
        self.cursors.hold(post.channel_id, post.id, post.create_at)

    def _done(self, post: Post, *, answered: bool = True) -> None:
        """Post erledigt. Beim Beenden nicht: dann holt der nächste Start ihn nach."""
        if self._stopping.is_set():
            return
        self.cursors.release(post.channel_id, post.id, answered=answered)

    # -- Eingang (WebSocket-Thread: nur filtern und übergeben) ---------------

    def on_post(self, post: Post) -> None:
        try:
            self._on_post(post)
        finally:
            # erst nach dem Festhalten (hold) – sonst könnte der Cursor kurz
            # über einen angenommenen, noch unbeantworteten Post gespeichert werden
            self.cursors.advance(post.channel_id, post.create_at)

    def _on_post(self, post: Post) -> None:
        if post.from_bot:
            return
        if self.cursors.is_answered(post.id) or not self._first_time(post.id):
            log.debug("Post %s doppelt zugestellt – ignoriert", post.id)
            return
        if not self.cfg.is_allowed(post.sender_name):
            log.info("Post von @%s ignoriert (nicht in ALLOWED_USERS)", post.sender_name)
            return
        if not post.message.strip() and not post.file_ids:
            return
        root = post.root_id if post.channel_type != "D" else ""
        if root:
            with self._mutex:
                known = self._known_thread(root)
                # Beteiligung unbekannt (oder schon ein Post in der Warteschlange
                # fürs Nachschlagen → Reihenfolge wahren): im Lookup-Worker entscheiden
                later = self._pending.get(root, 0) > 0 or (
                    known is None and not should_answer(post, **self._me_kw())
                )
                if later:
                    self._pending[root] = self._pending.get(root, 0) + 1
            if later:
                self._hold(post)  # evtl. adressiert – erst der Lookup entscheidet
                self._lookup.submit(self._guarded, self._route_later, post)
                return
            self._route(post, in_thread=bool(known))
        else:
            self._route(post)

    def _route_later(self, post: Post) -> None:
        routed = False
        try:
            routed = self._route(post, in_thread=self._in_thread(post.root_id))
        finally:
            if not routed:
                self._done(post, answered=False)
            with self._mutex:
                left = self._pending.get(post.root_id, 1) - 1
                if left > 0:
                    self._pending[post.root_id] = left
                else:
                    self._pending.pop(post.root_id, None)

    def _route(self, post: Post, *, in_thread: bool = False) -> bool:
        """Adressierten Post an Befehl oder Warteschlange übergeben (``True``)."""
        if not should_answer(post, **self._me_kw(), in_thread=in_thread):
            return False
        self._hold(post)
        key = conversation_key(post)
        root = reply_root(post)
        if root:
            self._mark_thread(root)  # Folgeposts im Thread brauchen keine Erwähnung
        cmd = parse_command(self._text(post))
        top = post.channel_type != "D" and not post.root_id
        if cmd and (cmd[0] not in QUEUED_COMMANDS or top):
            self._side.submit(self._guarded, self._command_now, key, post, *cmd)
            return True
        self._enqueue(key, post)
        return True

    def _command_now(self, key: str, post: Post, name: str, arg: str) -> None:
        try:
            self._command(key, post, name, arg)
        finally:
            self._done(post)

    @property
    def _name(self) -> str:
        return self.mm.me.get("username") or ""

    def _text(self, post: Post) -> str:
        """Nachricht ohne ``@captain`` (für Befehle und Prompt)."""
        return strip_mention(post.message, self._name, display_name(self.mm.me))

    def _me_kw(self) -> dict:
        me = self.mm.me
        return dict(bot_id=me.get("id", ""), bot_username=me.get("username", ""))

    def addressed(self, post: Post) -> bool:
        """:func:`captain.routing.should_answer` inkl. Thread-Beteiligung.

        Blockiert ggf. für ein REST-Nachschlagen – nicht im WebSocket-Thread
        aufrufen (``on_post`` verlagert das in den Lookup-Worker).
        """
        kw = self._me_kw()
        if should_answer(post, **kw):
            return True
        if not post.root_id or post.channel_type == "D":
            return False
        return should_answer(post, **kw, in_thread=self._in_thread(post.root_id))

    def _known_thread(self, root_id: str) -> bool | None:
        """Beteiligung laut Cache (``None`` = unbekannt). Aufrufer hält ``_mutex``."""
        if root_id in self._threads:
            self._threads.move_to_end(root_id)
            return True
        expires = self._no_thread.get(root_id)
        if expires is not None:
            if expires > time.monotonic():
                return False
            del self._no_thread[root_id]
        return None

    def _mark_thread(self, root_id: str) -> None:
        """Captain schreibt (gleich) im Thread: positiv merken, Negativ-Eintrag verwerfen."""
        with self._mutex:
            self._no_thread.pop(root_id, None)
            self._threads[root_id] = None
            self._threads.move_to_end(root_id)
            while len(self._threads) > THREADS_MAX:
                self._threads.popitem(last=False)

    def _in_thread(self, root_id: str) -> bool:
        """Hat Captain im Thread ``root_id`` schon geschrieben? (Cache, sonst REST)"""
        with self._mutex:
            known = self._known_thread(root_id)
        if known is not None:
            return known
        try:
            thread = self.mm.get_thread(root_id)
        except Exception as e:  # noqa: BLE001
            log.warning("Thread %s nicht ladbar: %s", root_id, e)
            return False
        me = self.mm.me.get("id")
        if any(p.get("user_id") == me for p in thread):
            self._mark_thread(root_id)
            return True
        with self._mutex:
            if root_id not in self._threads:  # inzwischen selbst geantwortet?
                self._no_thread[root_id] = time.monotonic() + NO_THREAD_TTL
                self._no_thread.move_to_end(root_id)
                while len(self._no_thread) > NO_THREAD_MAX:
                    self._no_thread.popitem(last=False)
        return False

    def _first_time(self, post_id: str) -> bool:
        with self._mutex:
            if post_id in self._seen:
                return False
            self._seen[post_id] = None
            while len(self._seen) > SEEN_MAX:
                self._seen.popitem(last=False)
            return True

    def _enqueue(self, key: str, post: Post) -> None:
        with self._mutex:
            self._lanes.setdefault(key, deque()).append(post)
            self._channels[key] = post.channel_id
            busy = key in self._active
            if not busy:
                self._active.add(key)
                self._pool.submit(self._drain, key)
            else:
                self._queued.add(post.id)
        if busy:
            log.info("%s: Antwort läuft, Post %s wartet", key, post.id)
            self._side.submit(self._guarded, self.mm.add_reaction, post.id, QUEUED_EMOJI)

    @staticmethod
    def _guarded(fn, *args) -> None:
        try:
            fn(*args)
        except Exception:  # noqa: BLE001
            log.exception("Fehler in %s", getattr(fn, "__name__", fn))

    # -- Abarbeitung pro Session ---------------------------------------------

    def _drain(self, key: str) -> None:
        with channel_lock(key):
            while True:
                with self._mutex:
                    lane = self._lanes.get(key)
                    items = list(lane) if lane else []
                    if lane:
                        lane.clear()
                    if not items:
                        self._active.discard(key)
                        self._lanes.pop(key, None)
                        self._channels.pop(key, None)
                        return
                for batch in self._batches(items):
                    if self._stopping.is_set():  # Rest bleibt offen → nächster Start
                        with self._mutex:
                            self._active.discard(key)
                        return
                    try:
                        self._unmark_queued(batch)
                        cmd = parse_command(self._text(batch[0]))
                        if cmd and cmd[0] in QUEUED_COMMANDS:
                            self._command(key, batch[0], *cmd)
                        else:
                            self._answer(key, batch)
                    except Exception as e:  # noqa: BLE001 – Spur darf nicht sterben
                        log.exception("%s: Verarbeitung fehlgeschlagen", key)
                        self._reply(batch[-1], f"⚠️ Interner Fehler: {e}", late=False,
                                    answers=[p.id for p in batch])
                    finally:
                        # Bündel: alle erst nach der fertigen Gesamtantwort
                        for p in batch:
                            self._done(p)

    def _batches(self, items: list[Post]) -> list[list[Post]]:
        """Aufeinanderfolgende Nachrichten bündeln; ``!neu`` steht allein."""
        batches: list[list[Post]] = []
        current: list[Post] = []
        for post in items:
            if parse_command(self._text(post)):
                if current:
                    batches.append(current)
                    current = []
                batches.append([post])
            else:
                current.append(post)
        if current:
            batches.append(current)
        return batches

    def _unmark_queued(self, posts: list[Post]) -> None:
        with self._mutex:
            marked = [p.id for p in posts if p.id in self._queued]
            self._queued.difference_update(marked)
        for pid in marked:
            self._side.submit(self._guarded, self.mm.remove_reaction, pid, QUEUED_EMOJI)

    def _session_dir(self, sid: str) -> str:
        """Verzeichnis der opencode-Session ``sid``: ``<SESSIONS_DIR>/<sid>``."""
        return f"{self.cfg.sessions_dir.rstrip('/')}/{sid}"

    def _owned(self, directory: str | None) -> bool:
        """Liegt ``directory`` direkt unterhalb von SESSIONS_DIR?"""
        if not directory:
            return False
        path = os.path.normpath(directory)
        return os.path.dirname(path) == os.path.normpath(self.cfg.sessions_dir)

    def _remove_dir(self, directory: str | None) -> None:
        """Löscht ein Session-Verzeichnis – nur direkt unterhalb von SESSIONS_DIR."""
        if self._owned(directory):
            shutil.rmtree(directory, ignore_errors=True)

    def _session(self, key: str, *, fresh: bool = False) -> tuple[str, str, bool]:
        """(opencode-Session, Verzeichnis, neu angelegt?) für ``key``.

        Jede opencode-Session hat ein eigenes Verzeichnis ``<SESSIONS_DIR>/<id>``,
        das der Bot **vor** dem Anlegen der Session erzeugt. Eine neue Session
        (``fresh`` bzw. alte ohne gültiges Verzeichnis) löscht das alte.
        """
        entry = self.store.get(key) or {}
        sid, directory = entry.get("opencode_session_id"), entry.get("directory")
        if sid and not fresh and self._owned(directory):
            os.makedirs(directory, exist_ok=True)  # vor dem Prompt, sonst HTTP 500
            return sid, directory, False
        self._remove_dir(directory)
        sid = new_session_id()
        directory = self._session_dir(sid)
        os.makedirs(directory, exist_ok=True)
        created = self.oc.create_session(
            directory, title=f"Mattermost {key}",
            permissions=session_permissions(directory, self.cfg.webfetch_allow), session_id=sid,
        )
        if created != sid:
            log.warning("%s: opencode vergab %s statt %s", key, created, sid)
            sid = created
        if not self._set_instructions(key, sid, directory):
            with self._mutex:
                self._no_instructions.add(sid)  # → Vorspann im ersten Prompt
        self.store.set(key, sid, directory)
        log.info("%s: neue opencode-Session %s in %s", key, sid, directory)
        return sid, directory, True

    def _set_instructions(self, key: str, sid: str, directory: str) -> bool:
        try:
            self.oc.set_instructions(sid, session_instructions(self.cfg.system_prompt, directory, self.cfg.webfetch_allow))
            return True
        except OpencodeError as e:  # Permissions greifen trotzdem
            log.warning("%s: Systemanweisungen für %s nicht gesetzt: %s", key, sid, e)
            return False

    def _preface(self, key: str, sid: str, directory: str, new: bool) -> str:
        """Ersatz, falls die (experimentelle) Instructions-API scheiterte.

        Neue Session: Persona + Arbeitsumgebung als markierter Vorspann im
        ersten Prompt (Kompaktierung kann ihn verlieren). Beim nächsten Prompt
        einmal erneut über die API versuchen, danach nicht mehr.
        """
        with self._mutex:
            if sid not in self._no_instructions:
                return ""
            if not new:
                self._no_instructions.discard(sid)
        if not new and self._set_instructions(key, sid, directory):
            log.info("%s: Systemanweisungen für %s nachgetragen", key, sid)
            return ""
        body = "\n\n".join(session_instructions(self.cfg.system_prompt, directory, self.cfg.webfetch_allow).values())
        return f"[Systemhinweise für diese Unterhaltung]\n{body}\n[Ende der Systemhinweise]\n\n"

    def _user(self, user_id: str) -> dict:
        try:
            return self.mm.get_user(user_id)
        except Exception as e:  # noqa: BLE001
            log.warning("Nutzer %s nicht ladbar: %s", user_id, e)
            return {}

    def _build_prompt(
        self, posts: list[Post], directory: str
    ) -> tuple[str, list[str], list[str]]:
        """Text, Anhangpfade und Hinweise zu übergangenen Anhängen für ein Bündel.

        Anhänge über ``MAX_ATTACHMENT_MB`` werden nicht geladen; das Modell
        erfährt es im Prompt, der Nutzer über einen Hinweis unter der Antwort.
        """
        group = posts[0].channel_type != "D"
        parts: list[str] = []
        files: list[str] = []
        skipped: list[str] = []
        limit = self.cfg.max_attachment_bytes
        for post in posts:
            text = self._text(post)
            names: list[str] = []
            for fid in post.file_ids:
                try:
                    path = self.mm.download_file(fid, directory, max_bytes=limit)
                except AttachmentTooLarge as e:
                    note = f"„{e.name}“ nicht geladen: größer als {_mb(e.limit)} MB"
                    log.info("Anhang %s: %s", fid, note)
                    skipped.append(note)
                    text += (f"\n(Anhang „{e.name}“ wurde nicht geladen, weil er größer als "
                             f"{_mb(e.limit)} MB ist – sein Inhalt ist dir unbekannt.)")
                    continue
                except Exception as e:  # noqa: BLE001
                    log.warning("Anhang %s nicht ladbar: %s", fid, e)
                    text += f"\n(Anhang {fid} konnte nicht geladen werden)"
                    continue
                files.append(path.replace("\\", "/"))  # Container-Pfad (POSIX)
                names.append(os.path.basename(path))
            if names:
                text += f"\n(Anhänge im Arbeitsverzeichnis: {', '.join(names)})"
            text = text.strip()
            if group:
                user = self._user(post.user_id)
                name = display_name(user) if user else post.sender_name or "?"
                parts.append(f"{name}: {text}")
            else:
                parts.append(text)
        return ("\n" if group else "\n\n").join(parts), files, skipped

    def _thread_context(self, root_id: str, exclude: set[str]) -> str:
        """Bisheriger Thread (ohne ``exclude``) als Kontext für eine neue Thread-Session."""
        try:
            thread = self.mm.get_thread(root_id)
        except Exception as e:  # noqa: BLE001
            log.warning("Thread %s nicht ladbar: %s", root_id, e)
            return ""
        me = self.mm.me.get("id")
        lines = []
        for post in thread[-THREAD_CONTEXT_POSTS:]:
            message = (post.get("message") or "").strip()
            if not message or post.get("id") in exclude or post.get("type"):
                continue
            if len(message) > THREAD_CONTEXT_CHARS:
                message = message[:THREAD_CONTEXT_CHARS] + " […]"
            if post.get("user_id") == me:
                name = "Captain (du)"
            else:
                name = display_name(self._user(post.get("user_id", ""))) or "?"
            lines.append(f"{name}: {message}")
        if not lines:
            return ""
        body = "\n".join(lines)
        return f"[Bisheriger Verlauf dieses Threads]\n{body}\n[Ende des Verlaufs]\n\n"

    def _channel_context(self, post: Post, exclude: set[str]) -> str:
        """Kanal-Posts seit Captains letzter Beteiligung als Vorgeschichte."""
        try:
            posts = self.mm.get_channel_posts(post.channel_id)
        except Exception as e:  # noqa: BLE001
            log.warning("Kanal %s nicht ladbar: %s", post.channel_id, e)
            return ""
        before = post.create_at or max((p.get("create_at", 0) for p in posts), default=0) + 1
        history = channel_history(
            posts, bot_id=self.mm.me.get("id", ""), before=before, exclude=exclude,
            is_bot=lambda uid: bool(self._user(uid).get("is_bot")),
            name_of=lambda uid: display_name(self._user(uid)),
            max_posts=self.cfg.history_max_posts, max_chars=self.cfg.history_max_chars,
        )
        if not history:
            return ""
        body = "\n".join(f"{p['name']}: {p['message']}" for p in history)
        return (
            "[Bisherige Unterhaltung im Kanal seit deiner letzten Antwort hier – nur Vorgeschichte]\n"
            f"{body}\n[Ende der Vorgeschichte]\n\n"
        )

    def _settings(self, key: str, post: Post) -> dict:
        """Modell/Variante: Session vor Kanal (``ch:<id>``) vor Standard.

        ``source``: ``"hier"``, ``"kanal"`` oder ``"standard"``.
        """
        entry = self.store.get(key) or {}
        if entry.get("model"):
            return {"model": entry["model"], "variant": entry.get("variant"), "source": "hier"}
        channel = f"ch:{post.channel_id}"
        if post.channel_type != "D" and key != channel:
            entry = self.store.get(channel) or {}
            if entry.get("model"):
                return {"model": entry["model"], "variant": entry.get("variant"), "source": "kanal"}
        return {"model": self.cfg.opencode_model, "variant": self.cfg.opencode_variant,
                "source": "standard"}

    @staticmethod
    def _describe(settings: dict) -> str:
        text = f"`{settings['model'] or 'opencode-Standard'}`"
        if settings["variant"]:
            text += f", Variante `{settings['variant']}`"
        return text + {"hier": "", "kanal": " (Kanal)", "standard": " (Standard)"}[settings["source"]]

    def _answer(self, key: str, posts: list[Post]) -> None:
        batch_ids = [p.id for p in posts]
        # Bots (auch ohne from_bot-Prop) nie beantworten → keine Bot-Schleifen
        posts = [p for p in posts if not self._user(p.user_id).get("is_bot")]
        if not posts:
            return
        first = posts[0]
        root = reply_root(first)
        settings = self._settings(key, first)
        model, variant = settings["model"], settings["variant"]

        def with_context(text: str, new: bool) -> str:
            if not new:
                return text
            ids = {p.id for p in posts}
            if first.root_id:  # Erwähnung in bestehendem Thread
                return self._thread_context(first.root_id, ids) + text
            if first.channel_type != "D":  # Top-Level-Erwähnung → neuer Thread
                return self._channel_context(first, ids) + text
            return text

        streamer = PostStreamer(self.mm, first.channel_id, root_id=root)
        live = _Live(self.mm, streamer, first.channel_id, root)
        if self._take_late(posts):
            live.prefix = f"{LATE_NOTE}\n\n"
        started = time.monotonic()
        live.start()
        final: str | None = None
        note = ""
        skipped: list[str] = []
        try:
            kwargs = dict(
                model=model, variant=variant,
                on_delta=live.on_delta, on_status=live.on_status,
                idle_timeout=self.prompt_timeout,
            )

            def send(fresh: bool) -> str:
                # Anhänge landen im Verzeichnis der (ggf. neuen) Session.
                sid, directory, new = self._session(key, fresh=fresh)
                self._running[key] = sid
                text, files, skipped[:] = self._build_prompt(posts, directory)
                log.info("%s: Prompt mit %d Nachricht(en), %d Anhang/Anhängen", key, len(posts), len(files))
                prompt = self._preface(key, sid, directory, new) + with_context(text, new)
                return self.oc.prompt(
                    sid, prompt, directory=directory, files=files or None, **kwargs
                )

            try:
                final = send(False)
            except SessionGone:
                log.warning("%s: Session %s verschwunden – neue Session, neuer Versuch", key, self._running.get(key))
                live.reset()
                final = send(True)
        except Interrupted:
            note = "_(abgebrochen)_"
        except OpencodeError as e:
            log.warning("%s: opencode-Fehler: %s", key, e)
            note = f"⚠️ opencode-Fehler: {e}"
        except Exception as e:  # noqa: BLE001 – Netzwerk, Mattermost, …
            log.exception("%s: Antwort fehlgeschlagen", key)
            note = f"⚠️ Fehler: {e}"
        finally:
            self._running.pop(key, None)
            live.stop()
        if self._stopping.is_set():
            # Neustart: Zwischenstand als abgebrochen kennzeichnen (zählt nicht
            # als Antwort), der nächste Start beantwortet den Post neu
            partial = live.text.strip()
            try:
                streamer.finish(f"{partial}\n\n{INTERRUPTED_NOTE}" if partial else INTERRUPTED_NOTE)
            except Exception as e:  # noqa: BLE001 – Mattermost evtl. schon zu
                log.info("%s: abgebrochene Antwort nicht markierbar: %s", key, e)
            log.info("%s: Antwort durch Beenden unterbrochen – wird nach dem Start nachgeholt", key)
            return
        if final is None:
            partial = live.text.strip()
            final = f"{partial}\n\n{note}" if partial else note
            if not partial:
                live.prefix = ""  # kein Verspätungshinweis vor einer reinen Fehlernotiz
        if skipped:  # Hinweis sicher in der Antwort, unabhängig vom Modell
            final = f"{final}\n\n" + "\n".join(f"_(Anhang {s})_" for s in skipped)
        streamer.finish(live.prefix + final, props=answers_props(batch_ids))
        log.info("%s: Antwort fertig nach %.1f s (%d Zeichen)", key, time.monotonic() - started, len(final))

    # -- Befehle -------------------------------------------------------------

    def _reply(
        self, post: Post, message: str, *, late: bool = True, answers: list[str] | None = None
    ) -> None:
        """Antwort auf ``post``; ``props.captain_answers`` = ``answers`` (Standard: der Post)."""
        if self._take_late([post]) and late:
            message = f"{LATE_NOTE}\n\n{message}"
        try:
            self.mm.create_post(
                post.channel_id, message, root_id=reply_root(post),
                props=answers_props(answers if answers is not None else [post.id]),
            )
        except Exception:  # noqa: BLE001
            log.exception("Antwort an %s nicht zustellbar", post.channel_id)

    def _command(self, key: str, post: Post, name: str, arg: str) -> None:
        if name == "hilfe":
            self._reply(post, HELP.format(name=self._name or "captain"))
        elif name == "neu":
            if post.channel_type != "D" and not post.root_id:
                self._reply(post, (
                    "Hier beginnt jede Erwähnung ohnehin eine neue Unterhaltung in einem "
                    "eigenen Thread. `!neu` in einem Thread setzt diesen Thread zurück."))
                return
            old = (self.store.get(key) or {}).get("directory")
            self.store.reset(key)
            self._remove_dir(old)
            self._reply(post, "🆕 Neue Unterhaltung – der bisherige Verlauf ist vergessen.")
        elif name == "stopp":
            if post.channel_type != "D" and not post.root_id:
                self._stop_channel(post)
            else:
                self._stop_key(key, post)
        elif name == "modell":
            self._model_command(settings_key(post), post, arg)

    def _stop_keys(self, keys: list[str], post: Post) -> tuple[int, int]:
        """Bricht laufende Antworten der Keys ab, verwirft Wartende: (abgebrochen, verworfen)."""
        dropped: list[Post] = []
        with self._mutex:
            for key in keys:
                lane = self._lanes.get(key)
                if lane:
                    dropped += [p for p in lane if p.id != post.id]
                    lane.clear()
            self._queued.difference_update(p.id for p in dropped)
        for p in dropped:
            self._done(p)  # bewusst verworfen – nicht nachholen
            self._side.submit(self._guarded, self.mm.remove_reaction, p.id, QUEUED_EMOJI)
        aborted = 0
        for key in keys:
            sid = self._running.get(key)
            if sid:
                self.oc.abort(sid)
                aborted += 1
        return aborted, len(dropped)

    @staticmethod
    def _stop_message(aborted: int, dropped: int, many: bool = False) -> str:
        if not aborted:
            msg = "Gerade läuft keine Antwort."
        elif many:
            msg = f"⏹️ {aborted} laufende Antwort(en) im Kanal abgebrochen."
        else:
            msg = "⏹️ Abgebrochen."
        if dropped:
            msg += f" {dropped} wartende Nachricht(en) verworfen."
        return msg

    def _stop_key(self, key: str, post: Post) -> None:
        self._reply(post, self._stop_message(*self._stop_keys([key], post)))

    def _stop_channel(self, post: Post) -> None:
        """``@captain !stopp`` oben im Kanal: alle Unterhaltungen dieses Kanals."""
        with self._mutex:
            keys = [k for k, ch in self._channels.items() if ch == post.channel_id]
        self._reply(post, self._stop_message(*self._stop_keys(keys, post), many=True))

    def _model_command(self, key: str, post: Post, arg: str) -> None:
        if not arg:
            models = self.oc.list_models()
            lines = [f"**Modell hier:** {self._describe(self._settings(key, post))}", ""]
            for m in models:
                variants = f" – Varianten: {', '.join(m['variants'])}" if m["variants"] else ""
                lines.append(f"- `{m['id']}`{variants}")
            if not models:
                lines.append("_(opencode meldet keine Modelle)_")
            lines.append("\nSetzen: `!modell <id> [variante]`, zurück: `!modell standard`")
            self._reply(post, "\n".join(lines))
            return
        words = arg.split()
        if words[0].lower() in ("standard", "default", "reset"):
            self.store.set_setting(key, model=None, variant=None)
            self._reply(post, f"Modell zurückgesetzt – jetzt gilt {self._describe(self._settings(key, post))}.")
            return
        model, variant = words[0], (words[1] if len(words) > 1 else None)
        known = {m["id"]: m for m in self.oc.list_models()}
        if known and model not in known:
            self._reply(post, f"Unbekanntes Modell `{model}`. Liste: `!modell`")
            return
        if variant and known and variant not in known[model]["variants"]:
            self._reply(post, f"Unbekannte Variante `{variant}` für `{model}`.")
            return
        self.store.set_setting(key, model=model, variant=variant)
        suffix = f", Variante `{variant}`" if variant else ""
        where = "diesen Kanal (neue Threads)" if key.startswith("ch:") else "diesen Chat"
        self._reply(post, f"✅ Modell für {where}: `{model}`{suffix}")
