package com.ewerlin.captain.mattermost;

import static com.ewerlin.captain.FakeHttpServer.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ewerlin.captain.FakeHttpServer;
import com.ewerlin.captain.util.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class MattermostClientTest {

    static final String BOT = "{\"id\":\"bot1\",\"username\":\"captain\"}";

    /** Minimaler Mattermost-REST-Server. */
    static final class FakeMattermostServer {
        final Map<String, String> posts = Collections.synchronizedMap(new LinkedHashMap<>());
        final List<String[]> patches = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger fail429 = new AtomicInteger();
        final AtomicInteger usersCalls = new AtomicInteger();

        void handle(FakeHttpServer.Request req, com.sun.net.httpserver.HttpExchange ex) throws IOException {
            String path = req.path().replaceFirst("^/api/v4", "");
            if (fail429.get() > 0) {
                fail429.decrementAndGet();
                FakeHttpServer.send(ex, 429, "{}".getBytes(), "application/json", Map.of("X-RateLimit-Reset", "0"));
                return;
            }
            if (path.equals("/users/me")) {
                json(ex, 200, BOT);
            } else if (path.startsWith("/users/") && path.endsWith("/typing")) {
                json(ex, 200, Map.of("status", "OK"));
            } else if (path.startsWith("/users/")) {
                usersCalls.incrementAndGet();
                json(ex, 200, Map.of("id", path.split("/")[2], "username", "alice"));
            } else if (path.equals("/posts") && req.method().equals("POST")) {
                JsonNode body = req.json();
                String pid = "p" + (posts.size() + 1);
                posts.put(pid, body.get("message").asString());
                ObjectNode out = ((ObjectNode) body.deepCopy()).put("id", pid);
                json(ex, 201, out);
            } else if (path.startsWith("/posts/") && path.endsWith("/patch")) {
                String pid = path.split("/")[2];
                String msg = req.json().get("message").asString();
                posts.put(pid, msg);
                patches.add(new String[] {pid, msg});
                json(ex, 200, Map.of("id", pid, "message", msg));
            } else if (path.equals("/files/f1/info")) {
                json(ex, 200, Map.of("id", "f1", "name", "../bild.png"));
            } else if (path.equals("/files/f1")) {
                FakeHttpServer.send(ex, 200, "PNGDATA".getBytes(), "application/octet-stream", Map.of());
            } else if (path.equals("/files/big/info")) {
                json(ex, 200, Map.of("id", "big", "name", "gross.pdf", "size", 5000));
            } else if (path.equals("/files/liar/info")) { // Größe fehlt in den Metadaten
                json(ex, 200, Map.of("id", "liar", "name", "x.bin"));
            } else if (path.equals("/files/liar")) {
                FakeHttpServer.send(ex, 200, "x".repeat(5000).getBytes(), "application/octet-stream", Map.of());
            } else if (path.equals("/files") && req.method().equals("POST")) {
                json(ex, 201, Map.of("file_infos", List.of(Map.of("id", "up1"))));
            } else {
                json(ex, 404, Map.of("message", "not found"));
            }
        }
    }

    @TempDir
    Path tmp;
    FakeMattermostServer fake;
    FakeHttpServer server;
    MattermostClient client;

    @BeforeEach
    void setUp() {
        fake = new FakeMattermostServer();
        server = new FakeHttpServer(fake::handle);
        client = new MattermostClient(server.url() + "/", "tok");
        client.sleeper = s -> { };
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    static JsonNode body(FakeHttpServer.Request req) {
        return req.json();
    }

    FakeHttpServer.Request last() {
        return server.requests().getLast();
    }

    // ---------------------------------------------------------------- parse

    static ObjectNode event(Map<String, Object> post, String channelType, String sender, String name) {
        ObjectNode data = Json.object().put("post", Json.write(post)).put("channel_type", channelType).put("sender_name", sender);
        ObjectNode e = Json.object().put("event", name).put("seq", 3);
        e.set("data", data);
        return e;
    }

    static ObjectNode event(Map<String, Object> post, String channelType) {
        return event(post, channelType, "@alice", "posted");
    }

    static Map<String, Object> post(Object... overrides) {
        Map<String, Object> p = new LinkedHashMap<>(Map.of("id", "p1", "channel_id", "c1", "root_id", "",
                "user_id", "u1", "message", "hallo", "type", "", "file_ids", List.of("f1")));
        for (int i = 0; i < overrides.length; i += 2) {
            p.put((String) overrides[i], overrides[i + 1]);
        }
        return p;
    }

    @Test
    void parsePosted() {
        assertThat(Post.parsePosted(event(post(), "G"), "bot1"))
                .isEqualTo(new Post("p1", "c1", "G", "", "u1", "alice", "hallo", List.of("f1"), false, 0));
    }

    @Test
    void parsePostedFilters() {
        assertThat(Post.parsePosted(event(post(), "D", "@alice", "post_edited"), "bot1")).isNull();
        assertThat(Post.parsePosted(event(post("user_id", "bot1"), "D"), "bot1")).isNull();
        assertThat(Post.parsePosted(event(post("type", "system_join_channel"), "D"), "bot1")).isNull();
        assertThat(Post.parsePosted(Json.parse("{\"event\":\"posted\",\"data\":{\"post\":\"{kaputt\"}}"), null)).isNull();
    }

    @Test
    void parsePostedCreateAt() {
        assertThat(Post.parsePosted(event(post("create_at", 1700), "O"), "bot1").createAt()).isEqualTo(1700);
    }

    @Test
    void parsePostedDefaults() {
        Post p = Post.parsePosted(event(Map.of("id", "p", "channel_id", "c", "user_id", "u"), "D"), "bot1");
        assertThat(p.rootId()).isEmpty();
        assertThat(p.fileIds()).isEmpty();
        assertThat(p.message()).isEmpty();
    }

    @Test
    void parsePostedMarksBotsAndWebhooks() {
        assertThat(Post.parsePosted(event(post(), "D"), "bot1").fromBot()).isFalse();
        for (Map<String, Object> props : List.<Map<String, Object>>of(Map.of("from_bot", "true"),
                Map.of("from_webhook", "true"), Map.of("from_bot", true))) {
            assertThat(Post.parsePosted(event(post("props", props), "D"), "bot1").fromBot()).isTrue();
        }
        assertThat(Post.parsePosted(event(post("props", Map.of("from_bot", "false")), "D"), "bot1").fromBot()).isFalse();
    }

    @Test
    void postFromJsonFiltersDeletedAndSystem() {
        ObjectNode base = Json.object().put("id", "p").put("channel_id", "c").put("user_id", "u").put("message", "m")
                .put("create_at", 5);
        assertThat(Post.fromJson(base, "O", "@bob", null)).isEqualTo(new Post("p", "c", "O", "", "u", "bob", "m", List.of(), false, 5));
        assertThat(Post.fromJson(base.deepCopy().put("delete_at", 9), "O", "", null)).isNull();
        assertThat(Post.fromJson(base.deepCopy().put("type", "system_join_channel"), "O", "", null)).isNull();
        assertThat(Post.fromJson(base, "O", "", "u")).isNull();
    }

    // ---------------------------------------------------------------- REST

    @Test
    void authHeaderAndMeCached() {
        assertThat(client.me()).isEqualTo(Json.parse(BOT));
        assertThat(client.me()).isEqualTo(Json.parse(BOT));
        assertThat(server.requests()).hasSize(1);
        assertThat(server.requests().getFirst().header("Authorization")).isEqualTo("Bearer tok");
        assertThat(server.requests().getFirst().path()).isEqualTo("/api/v4/users/me");
    }

    @Test
    void createPost() {
        assertThat(client.createPost("c1", "hi")).isEqualTo("p1");
        assertThat(body(last())).isEqualTo(Json.tree(Map.of("channel_id", "c1", "message", "hi")));
        client.createPost("c1", "re", "r1", List.of("f1"), null);
        assertThat(body(last())).isEqualTo(Json.tree(Map.of("channel_id", "c1", "message", "re", "root_id", "r1",
                "file_ids", List.of("f1"))));
    }

    @Test
    void updatePost() {
        client.updatePost("p9", "neu", null);
        FakeHttpServer.Request req = last();
        assertThat(req.method()).isEqualTo("PUT");
        assertThat(req.path()).isEqualTo("/api/v4/posts/p9/patch");
        assertThat(body(req)).isEqualTo(Json.tree(Map.of("message", "neu")));
    }

    @Test
    void typing() {
        client.typing("c1", "r1");
        FakeHttpServer.Request req = last();
        assertThat(req.path()).isEqualTo("/api/v4/users/bot1/typing");
        assertThat(body(req)).isEqualTo(Json.tree(Map.of("channel_id", "c1", "parent_id", "r1")));
    }

    @Test
    void getUserCached() {
        assertThat(client.getUser("u1").get("username").asString()).isEqualTo("alice");
        client.getUser("u1");
        assertThat(fake.usersCalls.get()).isEqualTo(1);
    }

    @Test
    void downloadFile() throws IOException {
        String path = client.downloadFile("f1", tmp.resolve("dl").toString(), null);
        assertThat(path).isEqualTo(tmp.resolve("dl").resolve("bild.png").toString());
        assertThat(Files.readString(Path.of(path))).isEqualTo("PNGDATA");
        String second = client.downloadFile("f1", tmp.resolve("dl").toString(), null);
        assertThat(second).endsWith("bild_f1.png");
    }

    @Test
    void downloadFileWithinLimit() throws IOException {
        String path = client.downloadFile("f1", tmp.resolve("dl").toString(), 7L);
        assertThat(Files.readString(Path.of(path))).isEqualTo("PNGDATA");
    }

    @Test
    void downloadFileTooLargeByInfo() {
        assertThatThrownBy(() -> client.downloadFile("big", tmp.resolve("dl").toString(), 4096L))
                .isInstanceOfSatisfying(AttachmentTooLargeException.class, e -> {
                    assertThat(e.name()).isEqualTo("gross.pdf");
                    assertThat(e.size()).isEqualTo(5000);
                    assertThat(e.limit()).isEqualTo(4096);
                });
        assertThat(server.requests()).noneMatch(r -> r.path().equals("/api/v4/files/big")); // nicht geladen
        assertThat(tmp.resolve("dl")).doesNotExist();
    }

    @Test
    void downloadFileTooLargeWhileStreaming() throws IOException {
        assertThatThrownBy(() -> client.downloadFile("liar", tmp.resolve("dl").toString(), 4096L))
                .isInstanceOf(AttachmentTooLargeException.class);
        try (Stream<Path> files = Files.list(tmp.resolve("dl"))) {
            assertThat(files).isEmpty(); // Teildatei entfernt
        }
    }

    @Test
    void uploadFile() throws IOException {
        Path f = tmp.resolve("a.txt");
        Files.writeString(f, "x");
        assertThat(client.uploadFile("c1", f.toString())).isEqualTo("up1");
        FakeHttpServer.Request req = last();
        assertThat(req.path()).isEqualTo("/api/v4/files");
        assertThat(req.text()).contains("name=\"channel_id\"", "filename=\"a.txt\"");
    }

    @Test
    void retry429() {
        fake.fail429.set(2);
        assertThat(client.createPost("c1", "hi")).isEqualTo("p1");
        assertThat(server.requests()).hasSize(3);
    }

    @Test
    void gives429Up() {
        fake.fail429.set(100);
        assertThatThrownBy(() -> client.createPost("c1", "hi")).isInstanceOf(MattermostException.class);
        assertThat(server.requests()).hasSize(client.maxRetries + 1);
    }

    @Test
    void httpError() {
        assertThatThrownBy(() -> client.downloadFile("nope", "unbenutzt", null))
                .isInstanceOf(MattermostException.class).hasMessageContaining("404");
    }

    @Test
    void safeNameAndSplitext() {
        assertThat(MattermostClient.safeName("a\\b/c:d?.txt")).isEqualTo("c_d_.txt");
        assertThat(MattermostClient.safeName("..")).isEqualTo("datei");
        assertThat(MattermostClient.splitext("bild.png")).containsExactly("bild", ".png");
        assertThat(MattermostClient.splitext(".bashrc")).containsExactly(".bashrc", "");
        assertThat(MattermostClient.splitext("..x.y")).containsExactly("..x", ".y");
    }

    // ---------------------------------------------------------------- split

    @Test
    void splitMessage() {
        assertThat(PostStreamer.splitMessage("abc", 10)).containsExactly("abc");
        assertThat(PostStreamer.splitMessage("", 10)).containsExactly("");
        assertThat(PostStreamer.splitMessage("a".repeat(25), 10)).containsExactly("a".repeat(10), "a".repeat(10), "a".repeat(5));
        assertThat(PostStreamer.splitMessage("aaaaaaa\nbbbbbbb", 10)).containsExactly("aaaaaaa", "bbbbbbb");
        assertThat(PostStreamer.splitMessage("a".repeat(10) + "\n", 10)).containsExactly("a".repeat(10));
        // Surrogatpaare bleiben ganz
        assertThat(PostStreamer.splitMessage("aaaaaaaaa😀b", 10)).containsExactly("aaaaaaaaa", "😀b");
    }

    @Test
    void splitStableWhenGrowing() {
        String text = "zeile\n".repeat(5000);
        List<String> first = PostStreamer.splitMessage(text);
        List<String> second = PostStreamer.splitMessage(text + "noch mehr");
        assertThat(second.subList(0, first.size() - 1)).isEqualTo(first.subList(0, first.size() - 1));
        assertThat(second).allMatch(c -> c.length() <= PostStreamer.SPLIT_LEN);
    }

    // ---------------------------------------------------------------- streamer

    @Test
    void streamerThrottles() {
        double[] clock = {100.0};
        PostStreamer s = new PostStreamer(client, "c1", "r1", 1.5, () -> clock[0]);
        s.status("🔍 Recherchiere…");
        assertThat(fake.posts).isEqualTo(Map.of("p1", "🔍 Recherchiere…"));
        assertThat(body(last()).get("root_id").asString()).isEqualTo("r1");
        s.update("Hal"); // erster Text ersetzt den Status sofort
        assertThat(fake.posts.get("p1")).isEqualTo("Hal");
        s.status("ignoriert");
        clock[0] += 0.5;
        s.update("Hallo"); // gedrosselt
        assertThat(fake.posts.get("p1")).isEqualTo("Hal");
        clock[0] += 1.5;
        s.update("Hallo Welt");
        assertThat(fake.posts.get("p1")).isEqualTo("Hallo Welt");
        s.finish("Hallo Welt!");
        assertThat(fake.posts).isEqualTo(Map.of("p1", "Hallo Welt!"));
        assertThat(s.postIds()).containsExactly("p1");
        assertThat(fake.patches).hasSize(3);
    }

    @Test
    void streamerUpdateCreatesPost() {
        PostStreamer s = new PostStreamer(client, "c1", null);
        s.update("");
        assertThat(fake.posts).isEmpty();
        s.update("a");
        assertThat(fake.posts).isEqualTo(Map.of("p1", "a"));
        s.finish("a"); // unverändert -> kein Patch
        assertThat(fake.patches).isEmpty();
    }

    @Test
    void streamerFinishSplits() {
        PostStreamer s = new PostStreamer(client, "c1", null);
        s.update("x".repeat(10));
        s.finish("x".repeat(16000) + "y".repeat(100));
        assertThat(s.postIds()).containsExactly("p1", "p2");
        assertThat(fake.posts).isEqualTo(Map.of("p1", "x".repeat(16000), "p2", "y".repeat(100)));
    }

    @Test
    void streamerFinishEmpty() {
        PostStreamer s = new PostStreamer(client, "c1", null);
        s.status("…");
        s.finish("");
        assertThat(fake.posts.get("p1")).isEqualTo("_(keine Antwort)_");
    }

    @Test
    void streamerIgnoresUpdateAfterFinish() {
        PostStreamer s = new PostStreamer(client, "c1", null, 0);
        s.update("Teil");
        s.finish("Fertig");
        s.update("verspätetes Delta");
        s.status("Status");
        assertThat(fake.posts).isEqualTo(Map.of("p1", "Fertig"));
        assertThat(s.finished()).isTrue();
    }

    @Test
    void finishPropsOnLastPartAndFromBotKept() {
        List<Object[]> bodies = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger n = new AtomicInteger();
        try (FakeHttpServer srv = new FakeHttpServer((req, ex) -> {
            String path = req.path().replaceFirst("^/api/v4", "");
            if (path.equals("/users/me")) {
                json(ex, 200, Map.of("id", "bot1", "username", "captain", "is_bot", true));
                return;
            }
            bodies.add(new Object[] {req.method(), path, req.json()});
            if (path.equals("/posts")) {
                json(ex, 201, Map.of("id", "p" + n.incrementAndGet()));
            } else {
                json(ex, 200, Map.of());
            }
        })) {
            MattermostClient c = new MattermostClient(srv.url(), "tok");
            PostStreamer s = new PostStreamer(c, "c1", null, 0);
            s.update("Teil ▌");
            s.finish("x".repeat(16000) + "\n" + "y".repeat(10), Map.of("captain_answers", List.of("a")));
            List<Object[]> withProps = bodies.stream().filter(b -> ((JsonNode) b[2]).has("props")).toList();
            assertThat(withProps).hasSize(1);
            assertThat(withProps.getFirst()[0]).isEqualTo("PUT");
            assertThat(withProps.getFirst()[1]).isEqualTo("/posts/p2/patch");
            assertThat(withProps.getFirst()[2]).isEqualTo(Json.tree(Map.of("message", "y".repeat(10),
                    "props", Map.of("from_bot", "true", "captain_answers", List.of("a")))));
            assertThat(bodies.getLast()).isSameAs(withProps.getFirst()); // erst nach allen Teilen
        }
    }

    // ---------------------------------------------------------------- Nachholen

    @Test
    void myChannels() {
        String channels = "[{\"id\":\"c1\",\"type\":\"D\"},{\"id\":\"c2\",\"type\":\"O\"}]";
        try (FakeHttpServer srv = new FakeHttpServer((req, ex) -> {
            assertThat(req.path()).isEqualTo("/api/v4/users/me/channels");
            json(ex, 200, channels);
        })) {
            assertThat(new MattermostClient(srv.url(), "tok").myChannels()).isEqualTo(Json.list(Json.parse(channels)));
        }
    }

    @Test
    void getPostsAfterPagesUntilCursor() {
        // 7 Posts, create_at 10..70; Seite = 3 Posts, neueste zuerst; Posts-Map mit Extra-Root
        Map<String, Map<String, Object>> posts = new LinkedHashMap<>();
        IntStream.rangeClosed(1, 7).forEach(i -> posts.put("p" + i, Map.of("id", "p" + i, "create_at", i * 10, "message", "" + i)));
        List<String> newestFirst = IntStream.rangeClosed(1, 7).mapToObj(i -> "p" + (8 - i)).toList();
        List<Integer> pages = Collections.synchronizedList(new ArrayList<>());
        try (FakeHttpServer srv = new FakeHttpServer((req, ex) -> {
            int page = Integer.parseInt(req.query().get("page"));
            int perPage = Integer.parseInt(req.query().get("per_page"));
            pages.add(page);
            List<String> order = newestFirst.subList(Math.min(page * perPage, 7), Math.min((page + 1) * perPage, 7));
            Map<String, Object> body = new LinkedHashMap<>();
            order.forEach(p -> body.put(p, posts.get(p)));
            body.put("root", Map.of("id", "root", "create_at", 999)); // nicht in order → ignorieren
            json(ex, 200, Map.of("order", order, "posts", body));
        })) {
            MattermostClient c = new MattermostClient(srv.url(), "tok");
            assertThat(c.getPostsAfter("c1", 25, 3).stream().map(p -> p.get("id").asString()).toList())
                    .containsExactly("p3", "p4", "p5", "p6", "p7");
            assertThat(pages).containsExactly(0, 1); // Seite 1 enthält p4..p2 → Stichtag erreicht
            pages.clear();
            assertThat(c.getPostsAfter("c1", 0, 3).stream().map(p -> p.get("id").asString()).toList())
                    .containsExactly("p1", "p2", "p3", "p4", "p5", "p6", "p7");
            assertThat(pages).containsExactly(0, 1, 2); // letzte Seite kürzer → Ende
        }
    }

    // ---------------------------------------------------------------- websocket

    /** WebSocket-Server, der pro Verbindung eine Liste von Events schickt und dann schließt. */
    static final class WsServer extends WebSocketServer {
        final List<List<JsonNode>> scripts;
        final List<String> paths = Collections.synchronizedList(new ArrayList<>());
        final List<String> auth = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch started = new CountDownLatch(1);

        WsServer(List<List<JsonNode>> scripts) {
            super(new InetSocketAddress("127.0.0.1", 0));
            this.scripts = Collections.synchronizedList(new ArrayList<>(scripts));
            setReuseAddr(true);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            paths.add(handshake.getResourceDescriptor());
            auth.add(handshake.getFieldValue("Authorization"));
            if (scripts.isEmpty()) {
                return; // offen halten, bis der Client schließt
            }
            for (JsonNode e : scripts.removeFirst()) {
                conn.send(Json.write(e));
            }
            conn.close(); // danach Verbindung schließen -> Client muss reconnecten
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
        }

        @Override
        public void onStart() {
            started.countDown();
        }

        String url() throws InterruptedException {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            return "http://127.0.0.1:" + getPort();
        }
    }

    /** Client, dessen REST-Teil der Fake-Server bedient, der WebSocket aber {@code ws}. */
    MattermostClient wsClient(WsServer ws) throws InterruptedException {
        String fakeWs = ws.url().replace("http://", "ws://") + "/api/v4/websocket";
        MattermostClient c = new MattermostClient(server.url(), "tok") {
            @Override
            WsConnection connect(String target) throws Exception {
                return super.connect(target.replace(wsUrl, fakeWs));
            }
        };
        c.backoffInitial = 0.05;
        return c;
    }

    static ObjectNode seq(ObjectNode event, int seq) {
        return event.put("seq", seq);
    }

    static JsonNode hello(String id, int seq) {
        return Json.parse("{\"event\":\"hello\",\"data\":{\"connection_id\":\"" + id + "\"},\"seq\":" + seq + "}");
    }

    @Test
    void listenReconnect() throws Exception {
        List<JsonNode> events1 = List.of(
                hello("conn1", 0),
                seq(event(post(), "D"), 1),
                seq(event(post("id", "own", "user_id", "bot1"), "D"), 2),
                seq(event(post("id", "sys", "type", "system_add_to_channel"), "D"), 3),
                Json.parse("{\"event\":\"typing\",\"data\":{},\"seq\":4}"));
        List<JsonNode> events2 = List.of(seq(event(post("id", "p2"), "P"), 5));
        WsServer wss = new WsServer(List.of(events1, events2));
        wss.start();
        MattermostClient c = wsClient(wss);
        List<Post> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() -> c.listen(p -> {
            received.add(p);
            if (received.size() == 2) {
                done.countDown();
            }
            throw new IllegalStateException("Handlerfehler darf listen nicht beenden");
        }, null));
        t.setDaemon(true);
        t.start();
        try {
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            c.close();
            t.join(5000);
            wss.stop();
        }
        assertThat(t.isAlive()).isFalse();
        assertThat(received.stream().map(Post::id).toList()).containsExactly("p1", "p2");
        assertThat(received.stream().map(Post::channelType).toList()).containsExactly("D", "P");
        assertThat(wss.paths.get(0)).isEqualTo("/api/v4/websocket");
        assertThat(wss.paths.get(1)).isEqualTo("/api/v4/websocket?connection_id=conn1&sequence_number=5");
        assertThat(wss.auth.getFirst()).isEqualTo("Bearer tok");
    }

    @Test
    void listenOnHelloOnlyForNewConnections() throws Exception {
        List<List<JsonNode>> scripts = List.of(
                List.of(hello("conn1", 0), seq(event(post(), "D"), 1)),
                // Replay gelungen: gleiche connection_id → kein Nachholen
                List.of(hello("conn1", 2), seq(event(post("id", "p2"), "D"), 3)),
                // Replay nicht möglich: neue connection_id → Nachholen
                List.of(hello("conn2", 0), seq(event(post("id", "p3"), "D"), 1)));
        WsServer wss = new WsServer(scripts);
        wss.start();
        MattermostClient c = wsClient(wss);
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() -> c.listen(p -> {
            log.add(p.id());
            if (p.id().equals("p3")) {
                done.countDown();
            }
        }, () -> log.add("hello")));
        t.setDaemon(true);
        t.start();
        try {
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            c.close();
            t.join(5000);
            wss.stop();
        }
        assertThat(log).containsExactly("hello", "p1", "p2", "hello", "p3");
    }

    @Test
    void listenBackoffGrows() {
        List<Double> delays = new ArrayList<>();
        MattermostClient c = new MattermostClient(server.url(), "tok") {
            @Override
            WsConnection connect(String target) throws IOException {
                throw new IOException("weg");
            }

            @Override
            boolean waitStop(double seconds) {
                delays.add(seconds);
                return delays.size() == 5;
            }
        };
        c.backoffInitial = 1.0;
        c.backoffMax = 4.0;
        c.listen(p -> { }, null);
        assertThat(delays).containsExactly(1.0, 2.0, 4.0, 4.0, 4.0);
    }

    @Test
    void wsConnectUrl() {
        assertThat(client.wsConnectUrl()).isEqualTo(client.wsUrl);
        assertThat(client.wsUrl).startsWith("ws://127.0.0.1:").endsWith("/api/v4/websocket");
        assertThat(new MattermostClient("https://chat.example.org/", "t").wsUrl)
                .isEqualTo("wss://chat.example.org/api/v4/websocket");
    }
}
