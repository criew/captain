package com.ewerlin.captain.mattermost;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.ewerlin.captain.util.Clock;
import com.ewerlin.captain.util.Http;
import com.ewerlin.captain.util.Json;
import com.ewerlin.captain.util.Signal;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Dünner Client für die Mattermost-API v4: REST (Springs {@code RestClient}) und WebSocket-Events
 * (JDK-{@link WebSocket}).
 *
 * <p>Posts anlegen/ändern, Tippen anzeigen, Dateien, Nutzer (gecacht) und {@link #listen} für
 * {@code posted}-Events inkl. Reconnect.
 */
public class MattermostClient implements MattermostApi, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MattermostClient.class);

    private final String url;
    private final String token;
    private final Http http;
    final int maxRetries;
    /** Zum Warten zwischen Wiederholungen; in Tests austauschbar. */
    DoubleConsumer sleeper = Clock::sleep;

    private volatile JsonNode me;
    private final Map<String, JsonNode> users = new ConcurrentHashMap<>();

    // WebSocket-Zustand
    final String wsUrl;
    double pingInterval = 30.0;
    double pingTimeout = 30.0;
    double idleTimeout = 150.0; // ohne jedes Event -> Reconnect
    volatile double backoffInitial = 1.0;
    volatile double backoffMax = 60.0;
    /** „hello“ empfangen bzw. fortgesetzt. */
    public final Signal connected = new Signal();
    volatile String connectionId;
    volatile Long lastSeq;
    final Signal stop = new Signal();
    volatile WsConnection ws;

    public MattermostClient(String url, String token) {
        this(url, token, 30.0, 5);
    }

    public MattermostClient(String url, String token, double timeout, int maxRetries) {
        this.url = url.replaceAll("/+$", "");
        this.token = token;
        this.maxRetries = maxRetries;
        this.http = new Http(Duration.ofMillis((long) (timeout * 1000)));
        this.wsUrl = this.url.replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://") + "/api/v4/websocket";
    }

    // ------------------------------------------------------------------ REST

    private void auth(HttpHeaders headers) {
        headers.set("Authorization", "Bearer " + token);
    }

    private URI uri(String path, Map<String, String> query) {
        return Http.uri(url + "/api/v4", path, query);
    }

    /** REST-Aufruf mit Wiederholung bei HTTP 429; Fehler → {@link MattermostException}. */
    Http.Response request(String method, String path, Map<String, String> query, Object body, MediaType type) {
        Http.Response resp = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            Object payload = body instanceof JsonNode node ? Json.write(node).getBytes(StandardCharsets.UTF_8) : body;
            try {
                resp = http.stream(method, uri(path, query), this::auth, payload, type,
                        (status, headers, in) -> new Http.Response(status, headers,
                                in == null ? new byte[0] : in.readAllBytes()));
            } catch (IOException e) {
                throw new MattermostException(method + " " + path + ": " + e, e);
            }
            if (resp.status() != 429 || attempt == maxRetries) {
                break;
            }
            double wait = retryAfter(resp.headers(), attempt);
            log.warn("Mattermost 429 auf {} {}, warte {} s", method, path, String.format("%.1f", wait));
            sleeper.accept(wait);
        }
        if (resp.isError()) {
            String text = resp.text();
            throw new MattermostException(method + " " + path + " -> " + resp.status() + ": "
                    + text.substring(0, Math.min(300, text.length())));
        }
        return resp;
    }

    private JsonNode json(String method, String path, Map<String, String> query, JsonNode body) {
        Http.Response resp = request(method, path, query, body, body == null ? null : MediaType.APPLICATION_JSON);
        try {
            return resp.body().length == 0 ? Json.object() : Json.parse(resp.text());
        } catch (JacksonException e) {
            throw new MattermostException(method + " " + path + ": keine JSON-Antwort", e);
        }
    }

    @Override
    public JsonNode me() {
        JsonNode cached = me;
        if (cached == null) {
            synchronized (this) {
                if (me == null) {
                    me = json("GET", "/users/me", null, null);
                }
                cached = me;
            }
        }
        return cached;
    }

    @Override
    public String createPost(String channelId, String message, String rootId, List<String> fileIds,
            Map<String, Object> props) {
        ObjectNode body = Json.object().put("channel_id", channelId).put("message", message);
        if (rootId != null && !rootId.isEmpty()) {
            body.put("root_id", rootId);
        }
        if (fileIds != null && !fileIds.isEmpty()) {
            body.set("file_ids", Json.tree(fileIds));
        }
        if (props != null && !props.isEmpty()) {
            body.set("props", Json.tree(props));
        }
        return Json.str(json("POST", "/posts", null, body), "id");
    }

    public String createPost(String channelId, String message) {
        return createPost(channelId, message, null, null, null);
    }

    @Override
    public void updatePost(String postId, String message, Map<String, Object> props) {
        ObjectNode body = Json.object().put("message", message);
        if (props != null) {
            Map<String, Object> merged = new LinkedHashMap<>(ownProps());
            merged.putAll(props);
            body.set("props", Json.tree(merged));
        }
        json("PUT", "/posts/" + postId + "/patch", null, body);
    }

    private Map<String, Object> ownProps() {
        // Der Server setzt from_bot beim Anlegen, ein Patch mit props löscht es sonst
        return me().path("is_bot").asBoolean(false) ? Map.of("from_bot", "true") : Map.of();
    }

    private static List<JsonNode> sortedPosts(JsonNode data) {
        List<JsonNode> posts = Json.values(data.get("posts"));
        posts.sort(Comparator.comparingLong(p -> Json.lng(p, "create_at")));
        return posts;
    }

    @Override
    public List<JsonNode> getThread(String rootId) {
        return sortedPosts(json("GET", "/posts/" + rootId + "/thread", null, null));
    }

    @Override
    public List<JsonNode> getChannelPosts(String channelId) {
        return getChannelPosts(channelId, 200);
    }

    public List<JsonNode> getChannelPosts(String channelId, int perPage) {
        return sortedPosts(json("GET", "/channels/" + channelId + "/posts", page(0, perPage), null));
    }

    private static Map<String, String> page(int page, int perPage) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("page", String.valueOf(page));
        q.put("per_page", String.valueOf(perPage));
        return q;
    }

    @Override
    public List<JsonNode> myChannels() {
        return Json.list(json("GET", "/users/me/channels", null, null));
    }

    @Override
    public List<JsonNode> getPostsAfter(String channelId, long after) {
        return getPostsAfter(channelId, after, 200);
    }

    /**
     * Posts mit {@code create_at > after} (inkl. Thread-Antworten), älteste zuerst.
     *
     * <p>Blättert rückwärts ({@code page}/{@code per_page}, neueste zuerst), bis ein Post am Stichtag
     * oder davor auftaucht. Bewusst nicht {@code ?since=}: das liefert auch alte, nur bearbeitete
     * Posts und blättert nicht. Kommen beim Blättern neue Posts hinzu, verschieben sich die Seiten –
     * das gibt höchstens Doppelte (hier per ID entfernt), keine Lücken.
     */
    public List<JsonNode> getPostsAfter(String channelId, long after, int perPage) {
        Map<String, JsonNode> found = new LinkedHashMap<>();
        int page = 0;
        while (true) {
            JsonNode data = json("GET", "/channels/" + channelId + "/posts", page(page, perPage), null);
            List<JsonNode> order = Json.list(data.get("order"));
            JsonNode posts = data.path("posts");
            boolean reached = false;
            // nur order zählt: posts enthält zusätzlich Roots von Antworten
            for (JsonNode pid : order) {
                JsonNode p = posts.get(pid.asString());
                if (p == null) {
                    continue;
                }
                if (Json.lng(p, "create_at") <= after) {
                    reached = true;
                    continue;
                }
                found.put(pid.asString(), p);
            }
            if (reached || order.size() < perPage) {
                break;
            }
            page++;
        }
        List<JsonNode> out = new ArrayList<>(found.values());
        out.sort(Comparator.<JsonNode>comparingLong(p -> Json.lng(p, "create_at")).thenComparing(p -> Json.str(p, "id")));
        return out;
    }

    @Override
    public void addReaction(String postId, String emoji) {
        json("POST", "/reactions", null, Json.object()
                .put("user_id", Json.str(me(), "id")).put("post_id", postId).put("emoji_name", emoji));
    }

    @Override
    public void removeReaction(String postId, String emoji) {
        json("DELETE", "/users/" + Json.str(me(), "id") + "/posts/" + postId + "/reactions/" + emoji, null, null);
    }

    @Override
    public void typing(String channelId, String parentId) {
        json("POST", "/users/" + Json.str(me(), "id") + "/typing", null,
                Json.object().put("channel_id", channelId).put("parent_id", parentId == null ? "" : parentId));
    }

    @Override
    public JsonNode getUser(String userId) {
        JsonNode cached = users.get(userId);
        if (cached != null) {
            return cached;
        }
        JsonNode user = json("GET", "/users/" + userId, null, null);
        users.put(userId, user);
        return user;
    }

    /**
     * Lädt eine Datei nach {@code destDir} und gibt den lokalen Pfad zurück.
     *
     * <p>Mit {@code maxBytes} wird ein größerer Anhang gar nicht erst geladen (Größe laut
     * {@code /files/<id>/info}) bzw. beim Überschreiten während des Ladens verworfen →
     * {@link AttachmentTooLargeException}.
     */
    @Override
    public String downloadFile(String fileId, String destDir, Long maxBytes) {
        JsonNode info = json("GET", "/files/" + fileId + "/info", null, null);
        String name = safeName(Json.truthy(info, "name") ? Json.str(info, "name") : fileId);
        long size = Json.lng(info, "size");
        if (maxBytes != null && size > maxBytes) {
            throw new AttachmentTooLargeException(name, size, maxBytes);
        }
        Path dir = Path.of(destDir);
        Path path;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new MattermostException("Verzeichnis " + destDir + " nicht anlegbar: " + e, e);
        }
        path = dir.resolve(name);
        if (Files.exists(path)) {
            String[] parts = splitext(name);
            path = dir.resolve(parts[0] + "_" + fileId.substring(0, Math.min(8, fileId.length())) + parts[1]);
        }
        Path target = path;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            int current = attempt;
            Object result;
            try {
                result = http.stream("GET", uri("/files/" + fileId, null), this::auth, null, null, (status, headers, in) -> {
                    if (status == 429 && current < maxRetries) {
                        return retryAfter(headers, current);
                    }
                    if (status >= 400) {
                        String text = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        throw new MattermostException("GET /files/" + fileId + " -> " + status + ": "
                                + text.substring(0, Math.min(300, text.length())));
                    }
                    long written;
                    try {
                        written = copy(in, target, maxBytes);
                    } catch (IOException | RuntimeException e) { // abgebrochener Download: keine Teildatei liegen lassen
                        Files.deleteIfExists(target);
                        throw e;
                    }
                    if (maxBytes != null && written > maxBytes) {
                        Files.deleteIfExists(target);
                        throw new AttachmentTooLargeException(name, written, maxBytes);
                    }
                    return target.toString();
                });
            } catch (IOException e) {
                throw new MattermostException("GET /files/" + fileId + ": " + e, e);
            }
            if (result instanceof String done) {
                return done;
            }
            sleeper.accept((Double) result);
        }
        throw new AssertionError("unerreichbar");
    }

    /** Kopiert höchstens {@code maxBytes} (+ einen Block) und liefert die gelesene Menge. */
    private static long copy(InputStream in, Path target, Long maxBytes) throws IOException {
        long written = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            if (in == null) {
                return 0;
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                written += n;
                if (maxBytes != null && written > maxBytes) {
                    break;
                }
                out.write(buf, 0, n);
            }
        }
        return written;
    }

    /** Lädt eine Datei hoch und gibt die File-ID zurück (für {@code createPost}). */
    public String uploadFile(String channelId, String path) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("channel_id", channelId);
        form.add("files", new FileSystemResource(path));
        Http.Response resp = request("POST", "/files", null, form, MediaType.MULTIPART_FORM_DATA);
        return Json.str(Json.list(Json.parse(resp.text()).get("file_infos")).getFirst(), "id");
    }

    static double retryAfter(HttpHeaders headers, int attempt) {
        for (String header : List.of("Retry-After", "X-RateLimit-Reset")) {
            String value = headers.getFirst(header);
            if (value != null && !value.isEmpty()) {
                try {
                    return Math.min(Math.max(Double.parseDouble(value.strip()), 0.5), 60.0);
                } catch (NumberFormatException e) {
                    // nächste Kopfzeile
                }
            }
        }
        return Math.min(Math.pow(2, attempt), 30.0);
    }

    static String safeName(String name) {
        String base = name.replace("\\", "/");
        base = base.substring(base.lastIndexOf('/') + 1).strip();
        for (char ch : "<>:\"|?*\0".toCharArray()) {
            base = base.replace(ch, '_');
        }
        return base.isEmpty() || base.equals(".") || base.equals("..") ? "datei" : base;
    }

    /** {@code os.path.splitext}: führende Punkte gehören zum Namen. */
    static String[] splitext(String name) {
        int start = 0;
        while (start < name.length() && name.charAt(start) == '.') {
            start++;
        }
        int dot = name.lastIndexOf('.');
        if (dot < start) {
            return new String[] {name, ""};
        }
        return new String[] {name.substring(0, dot), name.substring(dot)};
    }

    // ------------------------------------------------------------- WebSocket

    /** Eine WebSocket-Verbindung: Textnachrichten empfangen, schließen. */
    interface WsConnection {
        /** Nächste Textnachricht; {@link TimeoutException} nach {@code timeout} s Stille. */
        String receive(double timeout) throws IOException, TimeoutException;

        void close();
    }

    String wsConnectUrl() {
        if (connectionId != null && lastSeq != null) {
            return wsUrl + "?connection_id=" + Http.encode(connectionId) + "&sequence_number=" + (lastSeq + 1);
        }
        return wsUrl;
    }

    /** Baut die Verbindung auf (in Tests überschreibbar). */
    WsConnection connect(String target) throws Exception {
        return JdkWsConnection.open(http.client(), URI.create(target), "Bearer " + token, pingInterval, pingTimeout);
    }

    /** Wartet auf {@link #close()}; {@code true} = beendet (in Tests überschreibbar). */
    boolean waitStop(double seconds) {
        return stop.await(seconds);
    }

    /**
     * Blockiert und ruft {@code onPost} für jeden neuen Post anderer Nutzer.
     *
     * <p>Verbindungsabbrüche werden mit exponentiellem Backoff überbrückt; beim Reconnect werden
     * {@code connection_id} und {@code sequence_number} mitgegeben, damit der Server verpasste Events
     * nachliefert. Gelingt das nicht (neue Verbindung mit neuer {@code connection_id}, auch beim
     * ersten Verbinden), wird {@code onHello} aufgerufen – im WebSocket-Thread, bevor das nächste
     * Event zugestellt wird. Endet erst nach {@link #close()}.
     */
    @Override
    public void listen(Consumer<Post> onPost, Runnable onHello) {
        String botId = Json.str(me(), "id");
        stop.clear();
        double delay = backoffInitial;
        while (!stop.isSet()) {
            try {
                String target = wsConnectUrl();
                boolean resuming = target.contains("connection_id=");
                WsConnection conn = connect(target);
                ws = conn;
                try {
                    if (stop.isSet()) {
                        break;
                    }
                    if (resuming) { // Server setzt fort, ohne erneutes „hello“
                        connected.set();
                    }
                    while (!stop.isSet()) {
                        JsonNode event = nextEvent(conn);
                        if (event == null) {
                            continue;
                        }
                        delay = backoffInitial; // Verbindung steht
                        if (event.path("_new_connection").asBoolean(false)) {
                            if (onHello != null) {
                                try {
                                    onHello.run();
                                } catch (RuntimeException e) {
                                    log.error("on_hello-Handler fehlgeschlagen", e);
                                }
                            }
                            continue;
                        }
                        Post post = Post.parsePosted(event, botId);
                        if (post == null) {
                            continue;
                        }
                        try {
                            onPost.accept(post);
                        } catch (RuntimeException e) {
                            log.error("on_post-Handler fehlgeschlagen", e);
                        }
                    }
                } finally {
                    conn.close();
                }
            } catch (TimeoutException e) {
                // Stille – normaler Reconnect, kein Alarm.
                if (stop.isSet()) {
                    break;
                }
                log.info("WebSocket {} s still, verbinde neu in {} s", Math.round(idleTimeout), String.format("%.1f", delay));
            } catch (IOException e) {
                if (stop.isSet()) {
                    break;
                }
                log.warn("WebSocket getrennt ({}), neuer Versuch in {} s", e.getMessage(), String.format("%.1f", delay));
            } catch (Exception e) { // z. B. Fehler beim Handshake
                if (stop.isSet()) {
                    break;
                }
                log.warn("WebSocket-Fehler ({}), neuer Versuch in {} s", e, String.format("%.1f", delay));
            } finally {
                ws = null;
                connected.clear();
            }
            if (waitStop(delay)) {
                break;
            }
            delay = Math.min(delay * 2, backoffMax);
        }
    }

    /** Liest das nächste Event; {@code null} für Nicht-JSON. Merkt Sequenz und Verbindungs-ID. */
    private JsonNode nextEvent(WsConnection conn) throws IOException, TimeoutException {
        String raw = conn.receive(idleTimeout);
        JsonNode event;
        try {
            event = Json.parse(raw);
        } catch (JacksonException e) {
            log.debug("Nicht-JSON vom WebSocket ignoriert: {}", raw.length() > 200 ? raw.substring(0, 200) : raw);
            return null;
        }
        if (!event.isObject()) {
            return null;
        }
        if (event.has("seq") && event.has("event")) {
            lastSeq = event.get("seq").asLong();
        }
        if ("hello".equals(Json.str(event, "event"))) {
            String newId = Json.str(event.path("data"), "connection_id");
            if (!newId.isEmpty() && !newId.equals(connectionId)) {
                if (connectionId != null) {
                    log.info("Neue WebSocket-Verbindung, Events evtl. verpasst");
                }
                connectionId = newId;
                event = ((ObjectNode) event.deepCopy()).put("_new_connection", true); // ohne Replay
            }
            connected.set();
        }
        return event;
    }

    /** Beendet {@link #listen}. */
    @Override
    public void close() {
        stop.set();
        WsConnection conn = ws;
        if (conn != null) {
            try {
                conn.close();
            } catch (RuntimeException e) {
                // nur Aufräumen
            }
        }
    }

    /** Für Tests: aktuelle Verbindung schließen (Abbruch simulieren). */
    public void dropConnection() {
        WsConnection conn = ws;
        if (conn != null) {
            conn.close();
        }
    }

    public String connectionId() {
        return connectionId;
    }

    /** WebSocket über {@link java.net.http.WebSocket} mit Ping/Pong-Überwachung. */
    static final class JdkWsConnection implements WsConnection, WebSocket.Listener {
        private static final String CLOSED = "\u0000closed";

        private final BlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();
        private final double pingInterval;
        private final double pingTimeout;
        private WebSocket socket;
        private volatile double lastPing = Clock.monotonic();
        private volatile boolean pingOutstanding;

        private JdkWsConnection(double pingInterval, double pingTimeout) {
            this.pingInterval = pingInterval;
            this.pingTimeout = pingTimeout;
        }

        static JdkWsConnection open(HttpClient client, URI uri, String authorization, double pingInterval,
                double pingTimeout) throws Exception {
            JdkWsConnection conn = new JdkWsConnection(pingInterval, pingTimeout);
            try {
                conn.socket = client.newWebSocketBuilder()
                        .header("Authorization", authorization)
                        .connectTimeout(Duration.ofSeconds(30))
                        .buildAsync(uri, conn)
                        .get(30, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof WebSocketHandshakeException h) {
                    throw new IOException("Handshake abgelehnt: HTTP " + h.getResponse().statusCode(), h);
                }
                throw e.getCause() instanceof Exception cause ? cause : e;
            }
            return conn;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                inbox.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            pingOutstanding = false;
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            inbox.add(new IOException("geschlossen (" + statusCode + (reason.isEmpty() ? "" : " " + reason) + ")"));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            inbox.add(error instanceof IOException io ? io : new IOException(error.toString(), error));
        }

        @Override
        public String receive(double timeout) throws IOException, TimeoutException {
            double deadline = Clock.monotonic() + timeout;
            while (true) {
                double now = Clock.monotonic();
                if (pingOutstanding && now - lastPing > pingTimeout) {
                    throw new IOException("keepalive ping timeout");
                }
                if (!pingOutstanding && now - lastPing >= pingInterval) {
                    lastPing = now;
                    pingOutstanding = true;
                    socket.sendPing(ByteBuffer.allocate(0));
                }
                if (now >= deadline) {
                    throw new TimeoutException();
                }
                Object item;
                try {
                    item = inbox.poll((long) (Math.min(1.0, deadline - now) * 1000) + 1, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("unterbrochen", e);
                }
                if (item instanceof String text) {
                    if (text.equals(CLOSED)) {
                        throw new IOException("geschlossen");
                    }
                    return text;
                }
                if (item instanceof IOException e) {
                    throw e;
                }
            }
        }

        @Override
        public void close() {
            inbox.add(CLOSED);
            WebSocket s = socket;
            if (s != null) {
                s.abort();
            }
        }
    }
}
