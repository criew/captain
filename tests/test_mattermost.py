import json
import threading

import httpx
import pytest
from websockets.sync.server import serve

from captain import mattermost
from captain.mattermost import (
    AttachmentTooLarge,
    MattermostClient,
    MattermostError,
    Post,
    PostStreamer,
    parse_posted,
    split_message,
)

BOT = {"id": "bot1", "username": "captain"}


class FakeServer:
    """Minimaler Mattermost-REST-Server für httpx.MockTransport."""

    def __init__(self):
        self.requests: list[httpx.Request] = []
        self.posts: dict[str, str] = {}
        self.patches: list[tuple[str, str]] = []
        self.fail_429 = 0
        self.users_calls = 0

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        path = request.url.path.removeprefix("/api/v4")
        if self.fail_429:
            self.fail_429 -= 1
            return httpx.Response(429, headers={"X-RateLimit-Reset": "0"}, json={})
        if path == "/users/me":
            return httpx.Response(200, json=BOT)
        if path.startswith("/users/") and path.endswith("/typing"):
            return httpx.Response(200, json={"status": "OK"})
        if path.startswith("/users/"):
            self.users_calls += 1
            return httpx.Response(200, json={"id": path.split("/")[2], "username": "alice"})
        if path == "/posts" and request.method == "POST":
            body = json.loads(request.content)
            pid = f"p{len(self.posts) + 1}"
            self.posts[pid] = body["message"]
            return httpx.Response(201, json={"id": pid, **body})
        if path.startswith("/posts/") and path.endswith("/patch"):
            pid = path.split("/")[2]
            msg = json.loads(request.content)["message"]
            self.posts[pid] = msg
            self.patches.append((pid, msg))
            return httpx.Response(200, json={"id": pid, "message": msg})
        if path == "/files/f1/info":
            return httpx.Response(200, json={"id": "f1", "name": "../bild.png"})
        if path == "/files/f1":
            return httpx.Response(200, content=b"PNGDATA")
        if path == "/files/big/info":
            return httpx.Response(200, json={"id": "big", "name": "gross.pdf", "size": 5000})
        if path == "/files/liar/info":  # Größe fehlt in den Metadaten
            return httpx.Response(200, json={"id": "liar", "name": "x.bin"})
        if path == "/files/liar":
            return httpx.Response(200, content=b"x" * 5000)
        if path == "/files" and request.method == "POST":
            return httpx.Response(201, json={"file_infos": [{"id": "up1"}]})
        return httpx.Response(404, json={"message": "not found"})


@pytest.fixture
def server():
    return FakeServer()


@pytest.fixture
def client(server, monkeypatch):
    monkeypatch.setattr(mattermost.time, "sleep", lambda s: None)
    return MattermostClient("http://mm.test/", "tok", transport=httpx.MockTransport(server))


def body(req):
    return json.loads(req.content)


# ---------------------------------------------------------------- parse


def event(post: dict, channel_type="D", sender="@alice", name="posted"):
    return {
        "event": name,
        "data": {"post": json.dumps(post), "channel_type": channel_type, "sender_name": sender},
        "seq": 3,
    }


POST = {
    "id": "p1",
    "channel_id": "c1",
    "root_id": "",
    "user_id": "u1",
    "message": "hallo",
    "type": "",
    "file_ids": ["f1"],
}


def test_parse_posted():
    post = parse_posted(event(POST, "G"), "bot1")
    assert post == Post("p1", "c1", "G", "", "u1", "alice", "hallo", ["f1"])


def test_parse_posted_filters():
    assert parse_posted(event(POST, name="post_edited"), "bot1") is None
    assert parse_posted(event({**POST, "user_id": "bot1"}), "bot1") is None
    assert parse_posted(event({**POST, "type": "system_join_channel"}), "bot1") is None
    assert parse_posted({"event": "posted", "data": {"post": "{kaputt"}}) is None


def test_parse_posted_create_at():
    assert parse_posted(event({**POST, "create_at": 1700}, "O"), "bot1").create_at == 1700


def test_parse_posted_defaults():
    post = parse_posted(event({"id": "p", "channel_id": "c", "user_id": "u"}), "bot1")
    assert post.root_id == "" and post.file_ids == [] and post.message == ""


# ---------------------------------------------------------------- REST


def test_auth_header_and_me_cached(client, server):
    assert client.me == BOT
    assert client.me == BOT
    assert len(server.requests) == 1
    assert server.requests[0].headers["Authorization"] == "Bearer tok"
    assert str(server.requests[0].url) == "http://mm.test/api/v4/users/me"


def test_create_post(client, server):
    assert client.create_post("c1", "hi") == "p1"
    assert body(server.requests[-1]) == {"channel_id": "c1", "message": "hi"}
    client.create_post("c1", "re", root_id="r1", file_ids=["f1"])
    assert body(server.requests[-1]) == {
        "channel_id": "c1",
        "message": "re",
        "root_id": "r1",
        "file_ids": ["f1"],
    }


def test_update_post(client, server):
    client.update_post("p9", "neu")
    req = server.requests[-1]
    assert req.method == "PUT" and req.url.path == "/api/v4/posts/p9/patch"
    assert body(req) == {"message": "neu"}


def test_typing(client, server):
    client.typing("c1", "r1")
    req = server.requests[-1]
    assert req.url.path == "/api/v4/users/bot1/typing"
    assert body(req) == {"channel_id": "c1", "parent_id": "r1"}


def test_get_user_cached(client, server):
    assert client.get_user("u1")["username"] == "alice"
    client.get_user("u1")
    assert server.users_calls == 1


def test_download_file(client, tmp_path):
    path = client.download_file("f1", str(tmp_path / "dl"))
    assert path == str(tmp_path / "dl" / "bild.png")
    assert open(path, "rb").read() == b"PNGDATA"
    second = client.download_file("f1", str(tmp_path / "dl"))
    assert second.endswith("bild_f1.png")


def test_download_file_within_limit(client, tmp_path):
    path = client.download_file("f1", str(tmp_path / "dl"), max_bytes=7)
    assert open(path, "rb").read() == b"PNGDATA"


def test_download_file_too_large_by_info(client, server, tmp_path):
    with pytest.raises(AttachmentTooLarge) as e:
        client.download_file("big", str(tmp_path / "dl"), max_bytes=4096)
    assert (e.value.name, e.value.size, e.value.limit) == ("gross.pdf", 5000, 4096)
    assert not any(r.url.path == "/api/v4/files/big" for r in server.requests)  # nicht geladen
    assert not (tmp_path / "dl").exists()


def test_download_file_too_large_while_streaming(client, tmp_path):
    with pytest.raises(AttachmentTooLarge):
        client.download_file("liar", str(tmp_path / "dl"), max_bytes=4096)
    assert list((tmp_path / "dl").iterdir()) == []  # Teildatei entfernt


def test_upload_file(client, server, tmp_path):
    f = tmp_path / "a.txt"
    f.write_text("x")
    assert client.upload_file("c1", str(f)) == "up1"
    req = server.requests[-1]
    assert req.url.path == "/api/v4/files"
    assert b'name="channel_id"' in req.content and b'filename="a.txt"' in req.content


def test_429_retry(client, server):
    server.fail_429 = 2
    assert client.create_post("c1", "hi") == "p1"
    assert len(server.requests) == 3


def test_429_gives_up(client, server):
    server.fail_429 = 100
    with pytest.raises(MattermostError):
        client.create_post("c1", "hi")
    assert len(server.requests) == client.max_retries + 1


def test_http_error(client):
    with pytest.raises(MattermostError, match="404"):
        client.download_file("nope", "unbenutzt")


# ---------------------------------------------------------------- split


def test_split_message():
    assert split_message("abc", 10) == ["abc"]
    assert split_message("", 10) == [""]
    assert split_message("a" * 25, 10) == ["a" * 10, "a" * 10, "a" * 5]
    assert split_message("aaaaaaa\nbbbbbbb", 10) == ["aaaaaaa", "bbbbbbb"]
    assert split_message("a" * 10 + "\n", 10) == ["a" * 10]


def test_split_stable_when_growing():
    text = "zeile\n" * 5000
    first = split_message(text)
    second = split_message(text + "noch mehr")
    assert second[: len(first) - 1] == first[:-1]
    assert all(len(c) <= mattermost.SPLIT_LEN for c in second)


# ---------------------------------------------------------------- streamer


class Clock:
    def __init__(self):
        self.t = 100.0

    def __call__(self):
        return self.t


@pytest.fixture
def clock(monkeypatch):
    c = Clock()
    monkeypatch.setattr(mattermost.time, "monotonic", c)
    return c


def test_streamer_throttles(client, server, clock):
    s = PostStreamer(client, "c1", root_id="r1", min_interval=1.5)
    s.status("🔍 Recherchiere…")
    assert server.posts == {"p1": "🔍 Recherchiere…"}
    assert body(server.requests[-1])["root_id"] == "r1"
    s.update("Hal")  # erster Text ersetzt den Status sofort
    assert server.posts["p1"] == "Hal"
    s.status("ignoriert")
    clock.t += 0.5
    s.update("Hallo")  # gedrosselt
    assert server.posts["p1"] == "Hal"
    clock.t += 1.5
    s.update("Hallo Welt")
    assert server.posts["p1"] == "Hallo Welt"
    s.finish("Hallo Welt!")
    assert server.posts == {"p1": "Hallo Welt!"}
    assert s.post_ids == ["p1"]
    assert len(server.patches) == 3


def test_streamer_update_creates_post(client, server, clock):
    s = PostStreamer(client, "c1")
    s.update("")
    assert server.posts == {}
    s.update("a")
    assert server.posts == {"p1": "a"}
    s.finish("a")  # unverändert -> kein Patch
    assert server.patches == []


def test_streamer_finish_splits(client, server, clock):
    s = PostStreamer(client, "c1")
    s.update("x" * 10)
    s.finish("x" * 16000 + "y" * 100)
    assert s.post_ids == ["p1", "p2"]
    assert server.posts == {"p1": "x" * 16000, "p2": "y" * 100}


def test_streamer_finish_empty(client, server, clock):
    s = PostStreamer(client, "c1")
    s.status("…")
    s.finish("")
    assert server.posts["p1"] == "_(keine Antwort)_"


# ---------------------------------------------------------------- websocket


class WsServer:
    """WebSocket-Server, der pro Verbindung eine Liste von Events schickt."""

    def __init__(self, scripts):
        self.scripts = list(scripts)
        self.paths: list[str] = []
        self.auth: list[str] = []
        self.server = serve(self.handler, "127.0.0.1", 0)
        self.port = self.server.socket.getsockname()[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def handler(self, ws):
        self.paths.append(ws.request.path)
        self.auth.append(ws.request.headers.get("Authorization"))
        events = self.scripts.pop(0) if self.scripts else None
        if events is None:
            ws.recv()  # offen halten, bis der Client schließt
            return
        for e in events:
            ws.send(json.dumps(e))
        # danach Verbindung schließen -> Client muss reconnecten

    def stop(self):
        self.server.shutdown()


def test_listen_reconnect(server):
    ws_events_1 = [
        {"event": "hello", "data": {"connection_id": "conn1"}, "seq": 0},
        event(POST, "D") | {"seq": 1},
        event({**POST, "id": "own", "user_id": "bot1"}) | {"seq": 2},
        event({**POST, "id": "sys", "type": "system_add_to_channel"}) | {"seq": 3},
        {"event": "typing", "data": {}, "seq": 4},
    ]
    ws_events_2 = [event({**POST, "id": "p2"}, "P") | {"seq": 5}]
    wss = WsServer([ws_events_1, ws_events_2])
    client = MattermostClient(
        f"http://127.0.0.1:{wss.port}", "tok", transport=httpx.MockTransport(server)
    )
    client.backoff_initial = 0.05
    received: list[Post] = []
    done = threading.Event()

    def on_post(p):
        received.append(p)
        if len(received) == 2:
            done.set()
        raise RuntimeError("Handlerfehler darf listen nicht beenden")

    t = threading.Thread(target=client.listen, args=(on_post,), daemon=True)
    t.start()
    try:
        assert done.wait(5)
    finally:
        client.close()
        t.join(5)
        wss.stop()
    assert not t.is_alive()
    assert [p.id for p in received] == ["p1", "p2"]
    assert [p.channel_type for p in received] == ["D", "P"]
    assert wss.paths[0] == "/api/v4/websocket"
    assert wss.paths[1] == "/api/v4/websocket?connection_id=conn1&sequence_number=5"
    assert wss.auth[0] == "Bearer tok"


def test_listen_backoff_grows(server, monkeypatch):
    client = MattermostClient("http://127.0.0.1:1", "tok", transport=httpx.MockTransport(server))
    client.backoff_initial = 1.0
    client.backoff_max = 4.0
    delays = []

    def fake_wait(d):
        delays.append(d)
        if len(delays) == 5:
            return True
        return False

    monkeypatch.setattr(client._stop, "wait", fake_wait)
    monkeypatch.setattr(
        mattermost, "connect", lambda *a, **k: (_ for _ in ()).throw(OSError("weg"))
    )
    client.listen(lambda p: None)
    assert delays == [1.0, 2.0, 4.0, 4.0, 4.0]


def test_parse_posted_marks_bots_and_webhooks():
    assert not parse_posted(event(POST), "bot1").from_bot
    for props in ({"from_bot": "true"}, {"from_webhook": "true"}, {"from_bot": True}):
        assert parse_posted(event({**POST, "props": props}), "bot1").from_bot
    assert not parse_posted(event({**POST, "props": {"from_bot": "false"}}), "bot1").from_bot


def test_streamer_ignores_update_after_finish(client, server, clock):
    s = PostStreamer(client, "c1", min_interval=0)
    s.update("Teil")
    s.finish("Fertig")
    s.update("verspätetes Delta")
    s.status("Status")
    assert server.posts == {"p1": "Fertig"}
    assert s.finished


# ---------------------------------------------------------------- Nachholen


def test_my_channels():
    channels = [{"id": "c1", "type": "D"}, {"id": "c2", "type": "O"}]

    def handler(request):
        assert request.url.path == "/api/v4/users/me/channels"
        return httpx.Response(200, json=channels)

    c = MattermostClient("http://mm.test", "tok", transport=httpx.MockTransport(handler))
    assert c.my_channels() == channels


def test_get_posts_after_pages_until_cursor():
    # 7 Posts, create_at 10..70; Seite = 3 Posts, neueste zuerst; Posts-Map mit Extra-Root
    posts = {f"p{i}": {"id": f"p{i}", "create_at": i * 10, "message": str(i)} for i in range(1, 8)}
    newest_first = sorted(posts, key=lambda k: -posts[k]["create_at"])
    pages = []

    def handler(request):
        page = int(request.url.params["page"])
        per_page = int(request.url.params["per_page"])
        pages.append(page)
        order = newest_first[page * per_page:(page + 1) * per_page]
        body = {p: posts[p] for p in order}
        body["root"] = {"id": "root", "create_at": 999}  # nicht in order → ignorieren
        return httpx.Response(200, json={"order": order, "posts": body})

    c = MattermostClient("http://mm.test", "tok", transport=httpx.MockTransport(handler))
    got = c.get_posts_after("c1", 25, per_page=3)
    assert [p["id"] for p in got] == ["p3", "p4", "p5", "p6", "p7"]
    assert pages == [0, 1]  # Seite 1 enthält p4..p2 → Stichtag erreicht
    pages.clear()
    assert [p["id"] for p in c.get_posts_after("c1", 0, per_page=3)] == [f"p{i}" for i in range(1, 8)]
    assert pages == [0, 1, 2]  # letzte Seite kürzer → Ende


def test_post_from_dict_filters_deleted_and_system():
    base = {"id": "p", "channel_id": "c", "user_id": "u", "message": "m", "create_at": 5}
    post = mattermost.post_from_dict(base, "O", "@bob")
    assert post == Post("p", "c", "O", "", "u", "bob", "m", [], False, 5)
    assert mattermost.post_from_dict({**base, "delete_at": 9}, "O") is None
    assert mattermost.post_from_dict({**base, "type": "system_join_channel"}, "O") is None
    assert mattermost.post_from_dict(base, "O", bot_user_id="u") is None


def test_listen_on_hello_only_for_new_connections(server):
    scripts = [
        [{"event": "hello", "data": {"connection_id": "conn1"}, "seq": 0},
         event(POST, "D") | {"seq": 1}],
        # Replay gelungen: gleiche connection_id → kein Nachholen
        [{"event": "hello", "data": {"connection_id": "conn1"}, "seq": 2},
         event({**POST, "id": "p2"}) | {"seq": 3}],
        # Replay nicht möglich: neue connection_id → Nachholen
        [{"event": "hello", "data": {"connection_id": "conn2"}, "seq": 0},
         event({**POST, "id": "p3"}) | {"seq": 1}],
    ]
    wss = WsServer(scripts)
    client = MattermostClient(
        f"http://127.0.0.1:{wss.port}", "tok", transport=httpx.MockTransport(server)
    )
    client.backoff_initial = 0.05
    log: list[str] = []
    done = threading.Event()

    def on_post(p):
        log.append(p.id)
        if p.id == "p3":
            done.set()

    t = threading.Thread(
        target=client.listen, args=(on_post,), kwargs={"on_hello": lambda: log.append("hello")},
        daemon=True,
    )
    t.start()
    try:
        assert done.wait(5)
    finally:
        client.close()
        t.join(5)
        wss.stop()
    assert log == ["hello", "p1", "p2", "hello", "p3"]


def test_finish_props_on_last_part_and_from_bot_kept():
    bodies: list[tuple[str, str, dict]] = []
    n = [0]

    def handler(request):
        path = request.url.path.removeprefix("/api/v4")
        if path == "/users/me":
            return httpx.Response(200, json={"id": "bot1", "username": "captain", "is_bot": True})
        body = json.loads(request.content)
        bodies.append((request.method, path, body))
        if path == "/posts":
            n[0] += 1
            return httpx.Response(201, json={"id": f"p{n[0]}"})
        return httpx.Response(200, json={})

    c = MattermostClient("http://mm.test", "tok", transport=httpx.MockTransport(handler))
    s = PostStreamer(c, "c1", min_interval=0)
    s.update("Teil ▌")
    s.finish("x" * 16000 + "\n" + "y" * 10, props={"captain_answers": ["a"]})
    with_props = [b for b in bodies if "props" in b[2]]
    assert with_props == [("PUT", "/posts/p2/patch", {
        "message": "y" * 10, "props": {"from_bot": "true", "captain_answers": ["a"]}})]
    assert bodies[-1] == with_props[0]  # erst nach allen Teilen
