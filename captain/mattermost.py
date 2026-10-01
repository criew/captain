"""Mattermost-Client: REST (httpx) und WebSocket-Events (websockets).

Bewusst ohne ``mattermostdriver`` (archiviert). Enthält

* ``MattermostClient`` – Posts anlegen/ändern, Tippen anzeigen, Dateien,
  Nutzer (gecacht) und ``listen`` für ``posted``-Events inkl. Reconnect,
* ``PostStreamer`` – eine wachsende Antwort gedrosselt in *einem* Post.
"""

from __future__ import annotations

import json
import logging
import os
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from urllib.parse import urlencode

import httpx
from websockets.exceptions import ConnectionClosed
from websockets.sync.client import ClientConnection, connect

log = logging.getLogger(__name__)

MAX_POST_LEN = 16383  # harte Grenze des Servers
SPLIT_LEN = 16000  # darüber verteilt PostStreamer auf weitere Posts


@dataclass
class Post:
    id: str
    channel_id: str
    channel_type: str  # D/G/O/P
    root_id: str
    user_id: str
    sender_name: str  # Username ohne führendes „@“
    message: str
    file_ids: list[str] = field(default_factory=list)
    from_bot: bool = False  # Post eines Bots oder Webhooks (props)
    create_at: int = 0  # ms seit Epoche


def parse_posted(event: dict, bot_user_id: str | None = None) -> Post | None:
    """Wandelt ein WebSocket-Event in einen Post um.

    Liefert ``None`` für alles außer ``posted``, für eigene Posts des Bots und
    für System-Posts (``type`` != "").
    """
    if event.get("event") != "posted":
        return None
    data = event.get("data") or {}
    raw = data.get("post")
    try:
        post = json.loads(raw) if isinstance(raw, str) else raw
    except json.JSONDecodeError:
        log.warning("posted-Event mit ungültigem JSON: %.200s", raw)
        return None
    if not isinstance(post, dict):
        return None
    return post_from_dict(
        post, data.get("channel_type", ""), data.get("sender_name") or "", bot_user_id
    )


def post_from_dict(
    post: dict, channel_type: str, sender_name: str = "", bot_user_id: str | None = None
) -> Post | None:
    """Post-Objekt der REST-API bzw. aus einem Event → :class:`Post`.

    ``None`` für System-Posts (``type`` != ""), gelöschte Posts und eigene
    Posts des Bots.
    """
    if post.get("type") or post.get("delete_at"):
        return None
    if bot_user_id and post.get("user_id") == bot_user_id:
        return None
    props = post.get("props") or {}
    from_bot = isinstance(props, dict) and any(
        str(props.get(k, "")).lower() == "true" for k in ("from_bot", "from_webhook")
    )
    return Post(
        id=post.get("id", ""),
        channel_id=post.get("channel_id", ""),
        channel_type=channel_type,
        root_id=post.get("root_id") or "",
        user_id=post.get("user_id", ""),
        sender_name=sender_name.lstrip("@"),
        message=post.get("message") or "",
        file_ids=list(post.get("file_ids") or []),
        from_bot=from_bot,
        create_at=int(post.get("create_at") or 0),
    )


class MattermostError(Exception):
    """REST-Aufruf fehlgeschlagen."""


class AttachmentTooLarge(MattermostError):
    """Anhang überschreitet die Größengrenze und wurde nicht (vollständig) geladen."""

    def __init__(self, name: str, size: int, limit: int):
        super().__init__(f"Anhang {name!r} ist {size} Bytes groß (Grenze {limit} Bytes)")
        self.name, self.size, self.limit = name, size, limit


class MattermostClient:
    """Dünner Client für die Mattermost-API v4."""

    def __init__(
        self,
        url: str,
        token: str,
        *,
        transport: httpx.BaseTransport | None = None,
        timeout: float = 30.0,
        max_retries: int = 5,
    ):
        self.url = url.rstrip("/")
        self.token = token
        self.max_retries = max_retries
        self._http = httpx.Client(
            base_url=f"{self.url}/api/v4",
            headers={"Authorization": f"Bearer {token}"},
            timeout=timeout,
            transport=transport,
        )
        self._me: dict | None = None
        self._users: dict[str, dict] = {}
        self._users_lock = threading.Lock()

        # WebSocket-Zustand
        self.ws_url = (
            self.url.replace("https://", "wss://", 1).replace("http://", "ws://", 1)
            + "/api/v4/websocket"
        )
        self.ping_interval = 30.0
        self.ping_timeout = 30.0
        self.idle_timeout = 150.0  # ohne jedes Event/Ping-Antwort -> Reconnect
        self.backoff_initial = 1.0
        self.backoff_max = 60.0
        self.connected = threading.Event()  # „hello“ empfangen bzw. fortgesetzt
        self.connection_id: str | None = None
        self.last_seq: int | None = None
        self._stop = threading.Event()
        self._ws: ClientConnection | None = None

    # ------------------------------------------------------------------ REST

    def _request(self, method: str, path: str, **kwargs) -> httpx.Response:
        for attempt in range(self.max_retries + 1):
            resp = self._http.request(method, path, **kwargs)
            if resp.status_code != 429 or attempt == self.max_retries:
                break
            wait = _retry_after(resp, attempt)
            log.warning("Mattermost 429 auf %s %s, warte %.1f s", method, path, wait)
            _rewind_files(kwargs.get("files"))
            time.sleep(wait)
        if resp.is_error:
            raise MattermostError(
                f"{method} {path} -> {resp.status_code}: {resp.text[:300]}"
            )
        return resp

    @property
    def me(self) -> dict:
        """Der Bot-User (einmalig geladen)."""
        if self._me is None:
            self._me = self._request("GET", "/users/me").json()
        return self._me

    def create_post(
        self,
        channel_id: str,
        message: str,
        root_id: str | None = None,
        file_ids: list[str] | None = None,
        props: dict | None = None,
    ) -> str:
        body: dict = {"channel_id": channel_id, "message": message}
        if root_id:
            body["root_id"] = root_id
        if file_ids:
            body["file_ids"] = list(file_ids)
        if props:
            body["props"] = dict(props)
        return self._request("POST", "/posts", json=body).json()["id"]

    def update_post(self, post_id: str, message: str, props: dict | None = None) -> None:
        """Text ändern; ``props`` ersetzt die Props komplett (``from_bot`` bleibt)."""
        body: dict = {"message": message}
        if props is not None:
            body["props"] = {**self._own_props(), **props}
        self._request("PUT", f"/posts/{post_id}/patch", json=body)

    def _own_props(self) -> dict:
        # Der Server setzt ``from_bot`` beim Anlegen, ein Patch mit ``props`` löscht es sonst
        return {"from_bot": "true"} if self.me.get("is_bot") else {}

    def get_thread(self, root_id: str) -> list[dict]:
        """Alle Posts eines Threads (inkl. Root), älteste zuerst."""
        data = self._request("GET", f"/posts/{root_id}/thread").json()
        posts = (data.get("posts") or {}).values()
        return sorted(posts, key=lambda p: p.get("create_at", 0))

    def get_channel_posts(self, channel_id: str, per_page: int = 200) -> list[dict]:
        """Die letzten ``per_page`` Posts eines Kanals (inkl. Thread-Antworten), älteste zuerst."""
        data = self._request(
            "GET", f"/channels/{channel_id}/posts", params={"page": 0, "per_page": per_page}
        ).json()
        posts = (data.get("posts") or {}).values()
        return sorted(posts, key=lambda p: p.get("create_at", 0))

    def my_channels(self) -> list[dict]:
        """Alle Kanäle des Bots über alle Teams, inkl. DMs und Gruppen-DMs."""
        return self._request("GET", "/users/me/channels").json()

    def get_posts_after(self, channel_id: str, after: int, per_page: int = 200) -> list[dict]:
        """Posts mit ``create_at > after`` (inkl. Thread-Antworten), älteste zuerst.

        Blättert rückwärts (``page``/``per_page``, neueste zuerst), bis ein Post
        am Stichtag oder davor auftaucht. Bewusst nicht ``?since=``: das liefert
        auch alte, nur bearbeitete Posts und blättert nicht. Kommen beim
        Blättern neue Posts hinzu, verschieben sich die Seiten – das gibt
        höchstens Doppelte (hier per ID entfernt), keine Lücken.
        """
        found: dict[str, dict] = {}
        page = 0
        while True:
            data = self._request(
                "GET", f"/channels/{channel_id}/posts",
                params={"page": page, "per_page": per_page},
            ).json()
            order = data.get("order") or []
            posts = data.get("posts") or {}
            reached = False
            # nur ``order`` zählt: ``posts`` enthält zusätzlich Roots von Antworten
            for pid in order:
                p = posts.get(pid)
                if p is None:
                    continue
                if p.get("create_at", 0) <= after:
                    reached = True
                    continue
                found[pid] = p
            if reached or len(order) < per_page:
                break
            page += 1
        return sorted(found.values(), key=lambda p: (p.get("create_at", 0), p.get("id", "")))

    def add_reaction(self, post_id: str, emoji: str) -> None:
        body = {"user_id": self.me["id"], "post_id": post_id, "emoji_name": emoji}
        self._request("POST", "/reactions", json=body)

    def remove_reaction(self, post_id: str, emoji: str) -> None:
        self._request("DELETE", f"/users/{self.me['id']}/posts/{post_id}/reactions/{emoji}")

    def typing(self, channel_id: str, parent_id: str | None = None) -> None:
        body = {"channel_id": channel_id, "parent_id": parent_id or ""}
        self._request("POST", f"/users/{self.me['id']}/typing", json=body)

    def get_user(self, user_id: str) -> dict:
        with self._users_lock:
            cached = self._users.get(user_id)
        if cached is not None:
            return cached
        user = self._request("GET", f"/users/{user_id}").json()
        with self._users_lock:
            self._users[user_id] = user
        return user

    def download_file(self, file_id: str, dest_dir: str, max_bytes: int | None = None) -> str:
        """Lädt eine Datei nach ``dest_dir`` und gibt den lokalen Pfad zurück.

        Mit ``max_bytes`` wird ein größerer Anhang gar nicht erst geladen
        (Größe laut ``/files/<id>/info``) bzw. beim Überschreiten während des
        Ladens verworfen → :class:`AttachmentTooLarge`.
        """
        info = self._request("GET", f"/files/{file_id}/info").json()
        name = _safe_name(info.get("name") or file_id)
        size = int(info.get("size") or 0)
        if max_bytes is not None and size > max_bytes:
            raise AttachmentTooLarge(name, size, max_bytes)
        os.makedirs(dest_dir, exist_ok=True)
        path = os.path.join(dest_dir, name)
        if os.path.exists(path):
            stem, ext = os.path.splitext(name)
            path = os.path.join(dest_dir, f"{stem}_{file_id[:8]}{ext}")
        for attempt in range(self.max_retries + 1):
            with self._http.stream("GET", f"/files/{file_id}") as resp:
                if resp.status_code == 429 and attempt < self.max_retries:
                    time.sleep(_retry_after(resp, attempt))
                    continue
                if resp.is_error:
                    resp.read()
                    raise MattermostError(
                        f"GET /files/{file_id} -> {resp.status_code}: {resp.text[:300]}"
                    )
                written = 0
                with open(path, "wb") as f:
                    for chunk in resp.iter_bytes():
                        written += len(chunk)
                        if max_bytes is not None and written > max_bytes:
                            break
                        f.write(chunk)
                if max_bytes is not None and written > max_bytes:
                    os.remove(path)
                    raise AttachmentTooLarge(name, written, max_bytes)
                return path
        raise AssertionError("unerreichbar")

    def upload_file(self, channel_id: str, path: str) -> str:
        """Lädt eine Datei hoch und gibt die File-ID zurück (für ``create_post``)."""
        with open(path, "rb") as f:
            resp = self._request(
                "POST",
                "/files",
                data={"channel_id": channel_id},
                files={"files": (os.path.basename(path), f)},
            )
        return resp.json()["file_infos"][0]["id"]

    # ------------------------------------------------------------- WebSocket

    def _ws_connect_url(self) -> str:
        if self.connection_id and self.last_seq is not None:
            query = urlencode(
                {"connection_id": self.connection_id, "sequence_number": self.last_seq + 1}
            )
            return f"{self.ws_url}?{query}"
        return self.ws_url

    def listen(
        self,
        on_post: Callable[[Post], None],
        on_hello: Callable[[], None] | None = None,
    ) -> None:
        """Blockiert und ruft ``on_post`` für jeden neuen Post anderer Nutzer.

        Verbindungsabbrüche werden mit exponentiellem Backoff überbrückt; beim
        Reconnect werden ``connection_id`` und ``sequence_number`` mitgegeben,
        damit der Server verpasste Events nachliefert. Gelingt das nicht (neue
        Verbindung mit neuer ``connection_id``, auch beim ersten Verbinden),
        wird ``on_hello`` aufgerufen – im WebSocket-Thread, bevor das nächste
        Event zugestellt wird. Endet erst nach ``close()``.
        """
        bot_id = self.me["id"]
        self._stop.clear()
        delay = self.backoff_initial
        while not self._stop.is_set():
            try:
                url = self._ws_connect_url()
                resuming = "connection_id=" in url
                with connect(
                    url,
                    additional_headers={"Authorization": f"Bearer {self.token}"},
                    ping_interval=self.ping_interval,
                    ping_timeout=self.ping_timeout,
                    open_timeout=30,
                    max_size=None,
                ) as ws:
                    self._ws = ws
                    if self._stop.is_set():
                        break
                    if resuming:  # Server setzt fort, ohne erneutes „hello“
                        self.connected.set()
                    for event in self._events(ws):
                        delay = self.backoff_initial  # Verbindung steht
                        if event.get("_new_connection") and on_hello is not None:
                            try:
                                on_hello()
                            except Exception:
                                log.exception("on_hello-Handler fehlgeschlagen")
                            continue
                        post = parse_posted(event, bot_id)
                        if post is None:
                            continue
                        try:
                            on_post(post)
                        except Exception:
                            log.exception("on_post-Handler fehlgeschlagen")
            except TimeoutError:
                # Stille (auch keine Ping-Antwort) – normaler Reconnect, kein Alarm.
                if self._stop.is_set():
                    break
                log.info("WebSocket %.0f s still, verbinde neu in %.1f s", self.idle_timeout, delay)
            except (OSError, ConnectionClosed) as e:
                if self._stop.is_set():
                    break
                log.warning("WebSocket getrennt (%s), neuer Versuch in %.1f s", e, delay)
            except Exception as e:  # z. B. InvalidStatus beim Handshake
                if self._stop.is_set():
                    break
                log.warning("WebSocket-Fehler (%r), neuer Versuch in %.1f s", e, delay)
            finally:
                self._ws = None
                self.connected.clear()
            if self._stop.wait(delay):
                break
            delay = min(delay * 2, self.backoff_max)

    def _events(self, ws: ClientConnection):
        while not self._stop.is_set():
            raw = ws.recv(timeout=self.idle_timeout)
            try:
                event = json.loads(raw)
            except (TypeError, json.JSONDecodeError):
                log.debug("Nicht-JSON vom WebSocket ignoriert: %.200r", raw)
                continue
            if not isinstance(event, dict):
                continue
            if "seq" in event and "event" in event:
                self.last_seq = event["seq"]
            if event.get("event") == "hello":
                new_id = (event.get("data") or {}).get("connection_id")
                if new_id and new_id != self.connection_id:
                    if self.connection_id:
                        log.info("Neue WebSocket-Verbindung, Events evtl. verpasst")
                    self.connection_id = new_id
                    event = {**event, "_new_connection": True}  # ohne Replay
                self.connected.set()
            yield event

    def close(self) -> None:
        """Beendet ``listen`` und schließt die HTTP-Verbindungen."""
        self._stop.set()
        ws = self._ws
        if ws is not None:
            try:
                ws.close()
            except Exception:
                pass

    def __enter__(self) -> MattermostClient:
        return self

    def __exit__(self, *exc) -> None:
        self.close()
        self._http.close()


class PostStreamer:
    """Schreibt eine wachsende Antwort gedrosselt in einen Post.

    Jede Bearbeitung erzeugt in Mattermost eine Versionszeile und ein
    ``post_edited``-Event, daher höchstens eine Änderung pro ``min_interval``.
    Texte über ``SPLIT_LEN`` Zeichen werden auf Folge-Posts verteilt.
    """

    def __init__(
        self,
        client: MattermostClient,
        channel_id: str,
        root_id: str | None = None,
        min_interval: float = 1.5,
    ):
        self.client = client
        self.channel_id = channel_id
        self.root_id = root_id
        self.min_interval = min_interval
        self.post_ids: list[str] = []
        self._sent: list[str] = []  # zuletzt gesendeter Inhalt je Post
        self._last_send = 0.0
        self._has_text = False
        self._finished = False
        self._lock = threading.Lock()

    def status(self, text: str) -> None:
        """Zeigt einen Status, solange noch kein Antworttext da ist."""
        with self._lock:
            if self._finished or self._has_text or not text:
                return
            self._render(text, force=False)

    @property
    def finished(self) -> bool:
        return self._finished

    def update(self, full_text: str) -> None:
        """Neuer Gesamtstand; legt den Post beim ersten Aufruf an, sonst gedrosselt.

        Nach ``finish`` wirkungslos (verspätete Deltas überschreiben nichts).
        """
        with self._lock:
            if self._finished or not full_text.strip():
                return
            first = not self._has_text
            self._has_text = True
            self._render(full_text, force=first)

    def finish(self, full_text: str, props: dict | None = None) -> None:
        """Endstand schreiben (ungedrosselt), ggf. auf mehrere Posts verteilt.

        ``props`` landen am **letzten** Teil, und zwar erst, wenn alle Teile
        geschrieben sind – ein Abbruch mittendrin hinterlässt sie also nicht.
        """
        with self._lock:
            if not full_text.strip():
                full_text = "_(keine Antwort)_"
            self._has_text = True
            self._finished = True  # spätere update()/status() ignorieren
            self._render(full_text, force=True)
            if props:
                last = len(self.post_ids) - 1
                self.client.update_post(self.post_ids[last], self._sent[last], props=props)

    def _render(self, text: str, force: bool) -> None:
        now = time.monotonic()
        if not force and self.post_ids and now - self._last_send < self.min_interval:
            return
        for i, chunk in enumerate(split_message(text)):
            if i < len(self.post_ids):
                if self._sent[i] != chunk:
                    self.client.update_post(self.post_ids[i], chunk)
                    self._sent[i] = chunk
            else:
                self.post_ids.append(
                    self.client.create_post(self.channel_id, chunk, root_id=self.root_id)
                )
                self._sent.append(chunk)
        self._last_send = time.monotonic()


def split_message(text: str, limit: int = SPLIT_LEN) -> list[str]:
    """Teilt Text in Stücke ≤ ``limit``, bevorzugt an Zeilenumbrüchen.

    Die Grenzen hängen nur vom jeweiligen Präfix ab, sodass wachsender Text
    bereits gesendete Stücke nicht mehr verschiebt.
    """
    chunks: list[str] = []
    while len(text) > limit:
        cut = text.rfind("\n", 0, limit + 1)
        if cut <= limit // 2:
            cut = limit
            chunks.append(text[:cut])
            text = text[cut:]
        else:
            chunks.append(text[:cut])
            text = text[cut + 1 :]
    if text or not chunks:
        chunks.append(text)
    return chunks


def _retry_after(resp: httpx.Response, attempt: int) -> float:
    for header in ("Retry-After", "X-RateLimit-Reset"):
        value = resp.headers.get(header)
        if value:
            try:
                return min(max(float(value), 0.5), 60.0)
            except ValueError:
                pass
    return min(2.0**attempt, 30.0)


def _rewind_files(files) -> None:
    if not files:
        return
    for value in files.values():
        fh = value[1] if isinstance(value, tuple) else value
        if hasattr(fh, "seek"):
            fh.seek(0)


def _safe_name(name: str) -> str:
    name = os.path.basename(name.replace("\\", "/")).strip()
    for ch in '<>:"|?*\x00':
        name = name.replace(ch, "_")
    return name if name not in ("", ".", "..") else "datei"
