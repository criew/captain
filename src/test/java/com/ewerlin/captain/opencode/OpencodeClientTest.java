package com.ewerlin.captain.opencode;

import static com.ewerlin.captain.FakeHttpServer.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ewerlin.captain.FakeHttpServer;
import com.ewerlin.captain.TestData;
import com.ewerlin.captain.util.Clock;
import com.ewerlin.captain.util.Json;
import com.ewerlin.captain.util.Signal;
import com.sun.net.httpserver.HttpExchange;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Unit-Tests für {@link OpencodeClient} mit aufgezeichneten SSE-Beispielen.
 *
 * <p>Die Dateien unter {@code src/test/resources/data/} stammen aus echten Läufen gegen
 * {@code @opencode/cli@2.0.20} mit {@code ollama/gemma4:e4b} (nur Zeilen der betroffenen Session,
 * Reasoning-Deltas gekürzt). Ein Fake-Server spielt sie ab, sobald der Prompt eintrifft.
 */
class OpencodeClientTest {

    static final String CONNECTED = "data: {\"id\":\"evt_0\",\"type\":\"server.connected\",\"data\":{}}";
    static final String END = "\u0000ende"; // beendet die SSE-Verbindung

    static List<JsonNode> events(List<String> lines) {
        return lines.stream().map(OpencodeClient::parseSseLine).filter(e -> e != null).toList();
    }

    /** (sessionID, inboxID) aus einer Aufzeichnung. */
    static String[] idsOf(List<String> lines) {
        for (JsonNode e : events(lines)) {
            if (Json.str(e, "type").equals("session.inbox.delivered")) {
                return new String[] {Json.str(e.get("data"), "sessionID"), Json.str(e.get("data"), "inboxID")};
            }
        }
        throw new AssertionError("Aufzeichnung ohne inbox.delivered");
    }

    static String ev(String type, String sid, Object... data) {
        ObjectNode d = Json.object().put("sessionID", sid);
        for (int i = 0; i < data.length; i += 2) {
            d.set((String) data[i], Json.tree(data[i + 1]));
        }
        ObjectNode e = Json.object().put("type", type);
        e.set("data", d);
        return "data: " + Json.write(e);
    }

    static String finalText(JsonNode messages, String inboxId) {
        List<JsonNode> newer = new ArrayList<>();
        for (JsonNode m : Json.list(messages.get("data"))) {
            if (Json.str(m, "id").equals(inboxId)) {
                break;
            }
            newer.add(m);
        }
        Collections.reverse(newer);
        List<String> texts = new ArrayList<>();
        for (JsonNode m : newer) {
            if (Json.str(m, "type").equals("assistant")) {
                for (JsonNode p : Json.list(m.get("content"))) {
                    if (Json.str(p, "type").equals("text")) {
                        texts.add(Json.str(p, "text"));
                    }
                }
            }
        }
        return OpencodeClient.joinBlocks(texts);
    }

    // ---------------------------------------------------------------------------
    // Fake-Server
    // ---------------------------------------------------------------------------

    static final String MODELS = """
            [{"id":"gemma4:e4b","modelID":"gemma4:e4b","providerID":"ollama","name":"gemma4:e4b","variants":[],"enabled":true},
             {"id":"space-bunny-free","modelID":"space-bunny-free","providerID":"opencode","name":"Space Bunny Free",
              "variants":[{"id":"low"},{"id":"high"}],"enabled":true},
             {"id":"off","modelID":"off","providerID":"x","name":"Off","enabled":false}]""";

    /** Minimaler opencode-v2-Server: REST-Routen + steuerbarer SSE-Stream. */
    static final class FakeOpencodeServer {
        final BlockingQueue<String> sse = new LinkedBlockingQueue<>(); // Zeilen; END beendet die Verbindung
        final AtomicInteger connections = new AtomicInteger();
        final Signal closed = new Signal();
        final Map<String, List<String>> scripts = new ConcurrentHashMap<>(); // sessionID → Zeilen nach Prompt
        final Map<String, String> inbox = new ConcurrentHashMap<>(); // sessionID → inboxID der Antwort
        final Map<String, JsonNode> messages = new ConcurrentHashMap<>(); // sessionID → /message-Antwort
        final Map<String, ObjectNode> sessions = new ConcurrentHashMap<>();
        volatile int promptStatus = 200;
        volatile JsonNode promptBody;

        void addSession(String sid, List<String> script, String inboxId, JsonNode msgs) {
            sessions.put(sid, Json.object().put("id", sid));
            scripts.put(sid, new ArrayList<>(script));
            inbox.put(sid, inboxId);
            if (msgs != null) {
                messages.put(sid, msgs);
            }
        }

        void addSession(String sid, List<String> script, String inboxId) {
            addSession(sid, script, inboxId, null);
        }

        void stream(HttpExchange ex) throws IOException {
            connections.incrementAndGet();
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                out.write((CONNECTED + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                while (!closed.isSet()) {
                    String line;
                    try {
                        line = sse.poll(50, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (line == null) {
                        continue;
                    }
                    if (line.equals(END)) {
                        return;
                    }
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        }

        void handle(FakeHttpServer.Request req, HttpExchange ex) throws IOException {
            String path = req.path();
            JsonNode body = req.json();
            String[] parts = path.replaceAll("^/|/$", "").split("/"); // api, session, <id>, ...
            if (path.equals("/api/event")) {
                stream(ex);
                return;
            }
            if (path.equals("/api/session") && req.method().equals("POST")) {
                ObjectNode data = body == null ? Json.object() : (ObjectNode) body.deepCopy();
                json(ex, 200, Map.of("data", Json.object().put("id", "ses_new").setAll(data)));
                return;
            }
            if (parts.length > 3 && parts[0].equals("api") && parts[1].equals("experimental") && parts[2].equals("session")
                    && req.method().equals("PUT")) {
                if (!sessions.containsKey(parts[3])) {
                    json(ex, 404, Map.of("_tag", "SessionNotFoundError"));
                } else {
                    FakeHttpServer.send(ex, 204, new byte[0], null, Map.of());
                }
                return;
            }
            if (path.equals("/api/model")) {
                json(ex, 200, "{\"data\":" + MODELS + "}");
                return;
            }
            if (parts.length >= 3 && parts[0].equals("api") && parts[1].equals("session")) {
                String sid = parts[2];
                if (!sessions.containsKey(sid)) {
                    json(ex, 404, Map.of("_tag", "SessionNotFoundError", "sessionID", sid, "message", "x"));
                    return;
                }
                String action = parts.length > 3 ? parts[3] : "";
                switch (action) {
                    case "" -> json(ex, 200, Map.of("data", sessions.get(sid)));
                    case "prompt" -> {
                        promptBody = body;
                        if (promptStatus != 200) {
                            FakeHttpServer.text(ex, promptStatus, "ENOENT: realPath /x");
                            return;
                        }
                        scripts.getOrDefault(sid, List.of()).forEach(sse::add);
                        json(ex, 200, Map.of("data", Map.of("id", inbox.get(sid), "type", "user")));
                    }
                    case "model" -> {
                        sessions.get(sid).set("model", body.get("model"));
                        FakeHttpServer.send(ex, 204, new byte[0], null, Map.of());
                    }
                    case "interrupt" -> json(ex, 200, Map.of("data", Map.of("interrupted", true)));
                    case "message" -> json(ex, 200, Optional.ofNullable(messages.get(sid)).orElse(Json.parse("{\"data\":[]}")));
                    default -> FakeHttpServer.text(ex, 500, "unbekannt: " + req.method() + " " + path);
                }
                return;
            }
            FakeHttpServer.text(ex, 500, "unbekannt: " + req.method() + " " + path);
        }
    }

    FakeOpencodeServer fake;
    FakeHttpServer server;
    OpencodeClient client;

    @BeforeEach
    void setUp() {
        fake = new FakeOpencodeServer();
        server = new FakeHttpServer(fake::handle);
        client = new OpencodeClient(server.url(), "pw", 30, 60, 0.3);
        client.events.backoffMin = 0.01;
        client.events.backoffMax = 0.05;
    }

    @AfterEach
    void tearDown() {
        fake.closed.set();
        client.close();
        server.close();
    }

    List<JsonNode> calls(String method, String path) {
        return server.calls(method, path).stream().map(FakeHttpServer.Request::json).toList();
    }

    // ---------------------------------------------------------------------------
    // Hilfsfunktionen
    // ---------------------------------------------------------------------------

    @Test
    void parseSseLine() {
        assertThat(OpencodeClient.parseSseLine(": heartbeat")).isNull();
        assertThat(OpencodeClient.parseSseLine("")).isNull();
        assertThat(OpencodeClient.parseSseLine("data: kein json")).isNull();
        assertThat(OpencodeClient.parseSseLine("data: {\"type\":\"x\",\"data\":{}}")).isEqualTo(Json.parse("{\"type\":\"x\",\"data\":{}}"));
    }

    @Test
    void splitModel() {
        assertThat(OpencodeClient.splitModel("ollama/gemma4:e4b", null))
                .isEqualTo(Json.parse("{\"providerID\":\"ollama\",\"id\":\"gemma4:e4b\"}"));
        assertThat(OpencodeClient.splitModel("openrouter/anthropic/claude", "high"))
                .isEqualTo(Json.parse("{\"providerID\":\"openrouter\",\"id\":\"anthropic/claude\",\"variant\":\"high\"}"));
        assertThatThrownBy(() -> OpencodeClient.splitModel("gemma4", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolStatus() {
        assertThat(OpencodeClient.toolStatus("shell")).startsWith("⚙️");
        assertThat(OpencodeClient.toolStatus("Bash")).startsWith("⚙️");
        assertThat(OpencodeClient.toolStatus("write")).startsWith("✏️");
        assertThat(OpencodeClient.toolStatus("websearch")).startsWith("🔍");
        assertThat(OpencodeClient.toolStatus("irgendwas")).isEqualTo("🔧 Ich arbeite…");
    }

    @Test
    void joinBlocks() {
        assertThat(OpencodeClient.joinBlocks(List.of("Ich sehe nach.", "", " Ergebnis: hallo ")))
                .isEqualTo("Ich sehe nach.\n\nErgebnis: hallo");
    }

    // ---------------------------------------------------------------------------
    // REST
    // ---------------------------------------------------------------------------

    @Test
    void createSession() {
        assertThat(client.createSession("/tmp/captain/x", "DM x", null, null)).isEqualTo("ses_new");
        assertThat(calls("POST", "/api/session"))
                .containsExactly(Json.parse("{\"location\":{\"directory\":\"/tmp/captain/x\"},\"title\":\"DM x\"}"));
    }

    @Test
    void createSessionWithIdAndPermissions() {
        List<Rule> rules = SessionPermissions.of("/tmp/captain/ses_abc/");
        assertThat(client.createSession("/tmp/captain/ses_abc", null, rules, "ses_abc")).isEqualTo("ses_abc");
        JsonNode body = calls("POST", "/api/session").getFirst();
        ObjectNode expected = Json.object();
        expected.set("location", Json.object().put("directory", "/tmp/captain/ses_abc"));
        expected.set("permissions", Json.tree(rules));
        expected.put("id", "ses_abc");
        assertThat(body).isEqualTo(expected);
    }

    @Test
    void sessionPermissionsOnlyFilesInDirectory() {
        List<Rule> rules = SessionPermissions.of("/tmp/captain/ses_abc");
        assertThat(rules.stream().filter(r -> r.effect().equals("allow")).map(Rule::action).distinct())
                .containsExactlyInAnyOrder("read", "edit", "glob", "grep");
        // letzte passende Regel gewinnt: absolute Pfade nur im eigenen Verzeichnis
        assertThat(effect(rules, "read", "notiz.txt")).isEqualTo("allow");
        assertThat(effect(rules, "edit", "sub/notiz.txt")).isEqualTo("allow");
        assertThat(effect(rules, "read", "/etc/passwd")).isEqualTo("deny");
        assertThat(effect(rules, "read", "/tmp/captain/ses_other/x")).isEqualTo("deny");
        assertThat(effect(rules, "edit", "/tmp/captain/ses_abc/x")).isEqualTo("allow");
        assertThat(effect(rules, "external_directory", "/tmp/*")).isEqualTo("deny");
        // zum Schluss verboten – überstimmt Freigaben aus der Admin-Config
        for (String action : List.of("shell", "webfetch", "websearch", "subagent", "skill", "question",
                "execute", "opencode_list_mcp_resources", "opencode_read_mcp_resource")) {
            assertThat(effect(rules, action, "*")).as(action).isEqualTo("deny");
        }
        assertThat(effect(rules, "testmcp_wuerfeln", "*")).isNull(); // MCP-Freigaben bleiben der Admin-Config
        // Projekt-Config/Plugins/Anweisungen weder schreiben noch lesen
        for (String path : List.of(".opencode/opencode.jsonc", ".opencode/plugins/x.ts", ".opencode", "opencode.json",
                "opencode.jsonc", "AGENTS.md", "CLAUDE.md", ".claude/settings.json", ".agents/a.md",
                "sub/AGENTS.md", "/tmp/captain/ses_abc/.opencode/opencode.json", "/tmp/captain/ses_abc/AGENTS.md")) {
            assertThat(effect(rules, "edit", path)).as(path).isEqualTo("deny");
            assertThat(effect(rules, "read", path)).as(path).isEqualTo("deny");
        }
        assertThat(effect(rules, "edit", "agents.txt")).isEqualTo("allow");
        assertThat(effect(rules, "edit", "notizen/opencode-idee.md")).isEqualTo("allow");
    }

    /** {@code fnmatch.fnmatchcase}: {@code *} beliebig (auch {@code /}), letzte passende Regel gewinnt. */
    static String effect(List<Rule> rules, String action, String resource) {
        String hit = null;
        for (Rule r : rules) {
            if (fnmatch(action, r.action()) && fnmatch(resource, r.resource())) {
                hit = r.effect();
            }
        }
        return hit;
    }

    static boolean fnmatch(String value, String pattern) {
        StringBuilder rx = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            rx.append(switch (c) {
                case '*' -> ".*";
                case '?' -> ".";
                default -> java.util.regex.Pattern.quote(String.valueOf(c));
            });
        }
        return value.matches("(?s)" + rx);
    }

    @Test
    void setInstructions() {
        fake.addSession("ses_a", List.of(), "i");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("captain-persona", "P");
        entries.put("captain-umgebung", "U");
        client.setInstructions("ses_a", entries);
        String path = "/api/experimental/session/ses_a/instructions/entries/";
        assertThat(calls("PUT", path + "captain-persona")).containsExactly(Json.parse("{\"value\":\"P\"}"));
        assertThat(calls("PUT", path + "captain-umgebung")).containsExactly(Json.parse("{\"value\":\"U\"}"));
    }

    @Test
    void sessionExistsAndAbort() {
        fake.addSession("ses_a", List.of(), "i");
        assertThat(client.sessionExists("ses_a")).isTrue();
        assertThat(client.sessionExists("ses_weg")).isFalse();
        client.abort("ses_weg"); // unbekannt → kein Fehler
        client.abort("ses_a");
        assertThat(calls("POST", "/api/session/ses_a/interrupt")).hasSize(1).containsOnlyNulls();
    }

    @Test
    void listModels() {
        assertThat(client.listModels()).containsExactly(
                new ModelInfo("ollama/gemma4:e4b", "gemma4:e4b", List.of()),
                new ModelInfo("opencode/space-bunny-free", "Space Bunny Free", List.of("low", "high")));
    }

    @Test
    void basicAuth() {
        try (OpencodeClient c = new OpencodeClient(server.url(), "geheim")) {
            c.sessionExists("ses_x");
        }
        assertThat(server.requests().getLast().header("Authorization"))
                .isEqualTo("Basic " + Base64.getEncoder().encodeToString("opencode:geheim".getBytes(StandardCharsets.UTF_8)));
    }

    // ---------------------------------------------------------------------------
    // Prompt mit aufgezeichneten Streams
    // ---------------------------------------------------------------------------

    @Test
    void promptTextToolText() {
        List<String> lines = TestData.lines("text_tool_text.sse");
        String[] ids = idsOf(lines);
        fake.addSession(ids[0], lines, ids[1]);
        List<String> deltas = Collections.synchronizedList(new ArrayList<>());
        List<String> status = Collections.synchronizedList(new ArrayList<>());
        String result = client.prompt(ids[0], "egal", PromptOptions.in("/tmp").onDelta(deltas::add).onStatus(status::add));
        String expected = finalText(TestData.json("text_tool_text.messages.json"), ids[1]);
        assertThat(result).isEqualTo(expected);
        assertThat(deltas.size()).isGreaterThan(2);
        assertThat(deltas.getLast()).isEqualTo(expected);
        for (int i = 1; i < deltas.size(); i++) {
            assertThat(deltas.get(i).startsWith(deltas.get(i - 1)) || deltas.get(i).length() >= deltas.get(i - 1).length())
                    .isTrue(); // wächst nur
        }
        assertThat(status).isNotEmpty();
        assertThat(status.getFirst()).startsWith("⚙️");
        // Denkspur landet nicht im Text
        for (JsonNode r : events(lines)) {
            if (Json.str(r, "type").equals("session.reasoning.ended") && Json.truthy(r.get("data"), "text")) {
                assertThat(result).doesNotContain(Json.str(r.get("data"), "text"));
            }
        }
    }

    @Test
    void promptSimple() {
        List<String> lines = TestData.lines("simple.sse");
        String[] ids = idsOf(lines);
        fake.addSession(ids[0], lines, ids[1]);
        assertThat(client.prompt(ids[0], "x", PromptOptions.in("/tmp")))
                .isEqualTo(finalText(TestData.json("simple.messages.json"), ids[1]));
    }

    @Test
    void promptInterrupted() {
        List<String> lines = TestData.lines("interrupted.sse");
        String[] ids = idsOf(lines);
        fake.addSession(ids[0], lines, ids[1]);
        assertThatThrownBy(() -> client.prompt(ids[0], "x", PromptOptions.in("/tmp")))
                .isInstanceOf(PromptInterruptedException.class);
    }

    @Test
    void multipleBlocksAreJoined() {
        String sid = "ses_multi";
        fake.addSession(sid, List.of(
                ev("session.inbox.delivered", sid, "inboxID", "msg_in"),
                ev("session.execution.started", sid),
                ev("session.text.delta", sid, "assistantMessageID", "msg_a1", "ordinal", 0, "delta", "Ich sehe "),
                ev("session.text.delta", sid, "assistantMessageID", "msg_a1", "ordinal", 0, "delta", "nach."),
                ev("session.text.ended", sid, "assistantMessageID", "msg_a1", "ordinal", 0, "text", "Ich sehe nach."),
                ev("session.tool.input.started", sid, "assistantMessageID", "msg_a1", "id", "c1", "name", "shell"),
                ev("session.tool.success", sid, "assistantMessageID", "msg_a1", "id", "c1"),
                ev("session.text.delta", sid, "assistantMessageID", "msg_a2", "ordinal", 0, "delta", "Ausgabe: "),
                ev("session.text.delta", sid, "assistantMessageID", "msg_a2", "ordinal", 0, "delta", "hallo"),
                ev("session.execution.succeeded", sid)), "msg_in");
        List<String> deltas = Collections.synchronizedList(new ArrayList<>());
        List<String> status = Collections.synchronizedList(new ArrayList<>());
        String result = client.prompt(sid, "x", PromptOptions.in("/tmp").onDelta(deltas::add).onStatus(status::add));
        assertThat(result).isEqualTo("Ich sehe nach.\n\nAusgabe: hallo");
        assertThat(deltas).containsExactly("Ich sehe", "Ich sehe nach.", "Ich sehe nach.\n\nAusgabe:",
                "Ich sehe nach.\n\nAusgabe: hallo");
        assertThat(status).containsExactly("⚙️ Ich führe Befehle aus…");
    }

    @Test
    void foreignSessionsAndOldExecutionIgnored() {
        String sid = "ses_mine";
        fake.addSession(sid, List.of(
                // Ende einer älteren Ausführung derselben Session vor unserer Zustellung
                ev("session.text.delta", sid, "assistantMessageID", "msg_old", "ordinal", 0, "delta", "alt"),
                ev("session.execution.succeeded", sid),
                ev("session.text.delta", "ses_other", "assistantMessageID", "m", "ordinal", 0, "delta", "fremd"),
                ev("session.inbox.delivered", sid, "inboxID", "msg_in"),
                ev("session.execution.succeeded", "ses_other"),
                ev("session.text.delta", sid, "assistantMessageID", "msg_new", "ordinal", 0, "delta", "neu"),
                ev("session.execution.succeeded", sid)), "msg_in");
        assertThat(client.prompt(sid, "x", PromptOptions.in("/tmp"))).isEqualTo("neu");
    }

    @Test
    void failedExecutionRaises() {
        String sid = "ses_fail";
        fake.addSession(sid, List.of(
                ev("session.inbox.delivered", sid, "inboxID", "msg_in"),
                ev("session.error", sid, "error", Map.of("message", "Modell nicht erreichbar")),
                ev("session.execution.failed", sid)), "msg_in");
        assertThatThrownBy(() -> client.prompt(sid, "x", PromptOptions.in("/tmp")))
                .isInstanceOf(OpencodeException.class).hasMessageContaining("Modell nicht erreichbar");
    }

    @Test
    void promptUnknownSession() {
        assertThatThrownBy(() -> client.prompt("ses_weg", "x", PromptOptions.in("/tmp"))).isInstanceOf(SessionGoneException.class);
    }

    @Test
    void promptMissingDirectory() {
        fake.addSession("ses_a", List.of(), "i");
        fake.promptStatus = 500;
        assertThatThrownBy(() -> client.prompt("ses_a", "x", PromptOptions.in("/tmp/captain/fehlt")))
                .isInstanceOf(OpencodeException.class).hasMessageContaining("Arbeitsverzeichnis");
    }

    @Test
    void promptSetsModelOnlyWhenChanged() {
        String sid = "ses_m";
        List<String> done = List.of(ev("session.inbox.delivered", sid, "inboxID", "msg_in"), ev("session.execution.succeeded", sid));
        fake.addSession(sid, done, "msg_in");
        client.prompt(sid, "x", PromptOptions.in("/tmp").model("opencode/space-bunny-free", "high"));
        assertThat(calls("POST", "/api/session/" + sid + "/model")).containsExactly(
                Json.parse("{\"model\":{\"providerID\":\"opencode\",\"id\":\"space-bunny-free\",\"variant\":\"high\"}}"));
        client.prompt(sid, "x", PromptOptions.in("/tmp").model("opencode/space-bunny-free", "high"));
        assertThat(calls("POST", "/api/session/" + sid + "/model")).hasSize(1);
    }

    @Test
    void promptFiles() {
        String sid = "ses_f";
        fake.addSession(sid, List.of(ev("session.inbox.delivered", sid, "inboxID", "i"), ev("session.execution.succeeded", sid)), "i");
        client.prompt(sid, "x", PromptOptions.in("/tmp/captain/ses_a").files(List.of("bild.png", "/tmp/a.txt")));
        assertThat(fake.promptBody.get("files")).isEqualTo(Json.parse(
                "[{\"uri\":\"file:///tmp/captain/ses_a/bild.png\",\"name\":\"bild.png\"},"
                        + "{\"uri\":\"file:///tmp/a.txt\",\"name\":\"a.txt\"}]"));
    }

    // ---------------------------------------------------------------------------
    // Robustheit
    // ---------------------------------------------------------------------------

    /** Stream reißt mitten in der Antwort → Reconnect → Text aus /message. */
    @Test
    void streamBreakFallsBackToMessages() {
        List<String> lines = TestData.lines("text_tool_text.sse");
        String[] ids = idsOf(lines);
        int firstDelta = IntStream.range(0, lines.size()).filter(i -> lines.get(i).contains("session.text.delta")).findFirst().orElseThrow();
        List<String> script = new ArrayList<>(lines.subList(0, firstDelta + 1)); // nur bis zum ersten Text-Delta …
        script.add(END); // … dann Verbindungsabbruch
        fake.addSession(ids[0], script, ids[1], TestData.json("text_tool_text.messages.json"));
        String result = client.prompt(ids[0], "x", PromptOptions.in("/tmp").idleTimeout(10));
        assertThat(result).isEqualTo(finalText(TestData.json("text_tool_text.messages.json"), ids[1]));
        assertThat(fake.connections.get()).isGreaterThanOrEqualTo(2);
    }

    /** Keine Events (z. B. Stream hängt) → periodischer REST-Abgleich. */
    @Test
    void silentStreamReconcilesViaRest() {
        JsonNode msgs = TestData.json("simple.messages.json");
        String[] ids = idsOf(TestData.lines("simple.sse"));
        fake.addSession(ids[0], List.of(), ids[1], msgs);
        double t0 = Clock.monotonic();
        assertThat(client.prompt(ids[0], "x", PromptOptions.in("/tmp").idleTimeout(10))).isEqualTo(finalText(msgs, ids[1]));
        assertThat(Clock.monotonic() - t0).isLessThan(5);
    }

    @Test
    void idleTimeoutAborts() {
        String sid = "ses_idle";
        fake.addSession(sid, List.of(ev("session.inbox.delivered", sid, "inboxID", "msg_in")), "msg_in",
                Json.parse("{\"data\":[{\"id\":\"msg_in\",\"type\":\"user\",\"text\":\"x\"}]}"));
        assertThatThrownBy(() -> client.prompt(sid, "x", PromptOptions.in("/tmp").idleTimeout(0.5)))
                .isInstanceOf(OpencodeException.class).hasMessageContaining("Keine Rückmeldung");
        assertThat(calls("POST", "/api/session/" + sid + "/interrupt")).hasSize(1);
    }

    /** Zwei Sessions gleichzeitig über einen Stream; Events verschränkt. */
    @Test
    void parallelPrompts() throws InterruptedException {
        String a = "ses_a";
        String b = "ses_b";
        fake.addSession(a, List.of(), "in_a");
        fake.addSession(b, List.of(), "in_b");
        Map<String, String> results = new ConcurrentHashMap<>();
        Map<String, List<String>> deltas = Map.of(a, Collections.synchronizedList(new ArrayList<>()),
                b, Collections.synchronizedList(new ArrayList<>()));
        List<Thread> threads = new ArrayList<>();
        for (String sid : List.of(a, b)) {
            threads.add(new Thread(() -> results.put(sid,
                    client.prompt(sid, "x", PromptOptions.in("/tmp").onDelta(deltas.get(sid)::add).idleTimeout(10)))));
        }
        threads.forEach(Thread::start);
        // warten, bis beide Prompts abgeschickt sind
        double deadline = Clock.monotonic() + 5;
        while (server.requests().stream().filter(r -> r.path().endsWith("/prompt")).count() < 2) {
            assertThat(Clock.monotonic()).isLessThan(deadline);
            Thread.sleep(10);
        }
        for (String line : List.of(
                ev("session.inbox.delivered", a, "inboxID", "in_a"),
                ev("session.inbox.delivered", b, "inboxID", "in_b"),
                ev("session.text.delta", a, "assistantMessageID", "ma", "ordinal", 0, "delta", "A1"),
                ev("session.text.delta", b, "assistantMessageID", "mb", "ordinal", 0, "delta", "B1"),
                ev("session.text.delta", a, "assistantMessageID", "ma", "ordinal", 0, "delta", "A2"),
                ev("session.execution.succeeded", b),
                ev("session.text.delta", a, "assistantMessageID", "ma", "ordinal", 0, "delta", "A3"),
                ev("session.execution.succeeded", a))) {
            fake.sse.add(line);
        }
        for (Thread t : threads) {
            t.join(10_000);
        }
        assertThat(results).isEqualTo(Map.of(a, "A1A2A3", b, "B1"));
        assertThat(deltas.get(a)).containsExactly("A1", "A1A2", "A1A2A3");
        assertThat(fake.connections.get()).isEqualTo(1); // ein gemeinsamer Stream
    }

    @Test
    void callbackErrorsDoNotBreakPrompt() {
        String sid = "ses_cb";
        fake.addSession(sid, List.of(
                ev("session.inbox.delivered", sid, "inboxID", "i"),
                ev("session.text.delta", sid, "assistantMessageID", "m", "ordinal", 0, "delta", "ok"),
                ev("session.execution.succeeded", sid)), "i");
        assertThat(client.prompt(sid, "x", PromptOptions.in("/tmp").onDelta(t -> {
            throw new IllegalStateException("kaputt");
        }))).isEqualTo("ok");
    }

    /** inbox.delivered verpasst → Abgleich findet die User-Nachricht → Ende-Event zählt. */
    @Test
    void restMarksDeliveredWhenEventMissed() throws InterruptedException {
        String sid = "ses_missed";
        // Nachricht zugestellt, Ausführung läuft noch (kein idle in der Historie)
        fake.addSession(sid, List.of(), "msg_in", Json.parse("{\"data\":[{\"id\":\"msg_in\",\"type\":\"user\",\"text\":\"x\"}]}"));
        Map<String, String> result = new HashMap<>();
        List<String> deltas = Collections.synchronizedList(new ArrayList<>());
        Thread t = new Thread(() -> result.put("text",
                client.prompt(sid, "x", PromptOptions.in("/tmp").onDelta(deltas::add).idleTimeout(5))));
        t.start();
        double deadline = Clock.monotonic() + 5;
        while (server.calls("GET", "/api/session/" + sid + "/message").isEmpty()) {
            assertThat(Clock.monotonic()).isLessThan(deadline);
            Thread.sleep(10);
        }
        // Historie hat nie ein idle – ohne Fix liefe der Prompt in den Timeout.
        fake.messages.put(sid, Json.parse("{\"data\":[{\"id\":\"msg_a\",\"type\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]},{\"id\":\"msg_in\",\"type\":\"user\",\"text\":\"x\"}]}"));
        fake.sse.add(ev("session.text.delta", sid, "assistantMessageID", "msg_a", "ordinal", 0, "delta", "hi"));
        fake.sse.add(ev("session.execution.succeeded", sid));
        t.join(5000);
        assertThat(t.isAlive()).isFalse();
        assertThat(result.get("text")).isEqualTo("hi");
        assertThat(deltas).containsExactly("hi");
    }

    /** Folge-Ausführungen nach dem ersten idle gehören nicht zur Antwort. */
    @Test
    void stateAfterStopsAtFirstIdle() {
        String sid = "ses_two";
        fake.addSession(sid, List.of(), "msg_in", Json.parse("""
                {"data":[
                  {"id":"i2","type":"idle","outcome":"succeeded"},
                  {"id":"a2","type":"assistant","content":[{"type":"text","text":"zweite"}]},
                  {"id":"u2","type":"user","text":"y"},
                  {"id":"i1","type":"idle","outcome":"succeeded"},
                  {"id":"a1","type":"assistant","content":[{"type":"text","text":"erste"}]},
                  {"id":"msg_in","type":"user","text":"x"}]}"""));
        assertThat(client.stateAfter(sid, "msg_in")).isEqualTo(new OpencodeClient.State(true, "succeeded", "erste", ""));
        assertThat(client.stateAfter(sid, "u2")).isEqualTo(new OpencodeClient.State(true, "succeeded", "zweite", ""));
        assertThat(client.stateAfter(sid, "fehlt")).isEqualTo(new OpencodeClient.State(false, null, "", ""));
        assertThat(client.prompt(sid, "x", PromptOptions.in("/tmp").idleTimeout(5))).isEqualTo("erste");
    }

    // ---------------------------------------------------------------------------
    // MCP-Verbindung pro Location (ensureMcp)
    // ---------------------------------------------------------------------------

    /** Server, dessen {@code GET /api/mcp} nacheinander {@code answers} liefert (letzte bleibt). */
    record Mcp(FakeHttpServer server, OpencodeClient client, List<String> calls) implements AutoCloseable {
        @Override
        public void close() {
            client.close();
            server.close();
        }
    }

    static Mcp mcpClient(List<Object> answers) {
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        FakeHttpServer srv = new FakeHttpServer((req, ex) -> {
            assertThat(req.path()).isEqualTo("/api/mcp");
            calls.add(req.query().get("location[directory]"));
            Object data = answers.get(Math.min(calls.size(), answers.size()) - 1);
            if (data instanceof Integer status) {
                FakeHttpServer.text(ex, status, "kaputt");
            } else {
                json(ex, 200, Map.of("location", Map.of(), "data", data));
            }
        });
        return new Mcp(srv, new OpencodeClient(srv.url(), "pw"), calls);
    }

    static Map<String, Object> srv(String status, String name) {
        return Map.of("name", name, "status", Map.of("status", status));
    }

    static Map<String, Object> srv(String status) {
        return srv(status, "testmcp");
    }

    @Test
    void ensureMcpWaitsForPendingServers() {
        try (Mcp m = mcpClient(List.of(List.of(), List.of(srv("pending")), List.of(srv("connected"))))) {
            m.client().sleeper = s -> { };
            assertThat(m.client().ensureMcp("/tmp/captain/ses_a")).containsExactly(Json.tree(srv("connected")));
            assertThat(m.calls()).containsExactly("/tmp/captain/ses_a", "/tmp/captain/ses_a", "/tmp/captain/ses_a");
        }
    }

    @Test
    void ensureMcpWithoutServersWaitsOnlyOnce() {
        try (Mcp m = mcpClient(List.of(List.of()))) {
            double t0 = Clock.monotonic();
            assertThat(m.client().ensureMcp("/tmp/captain/ses_a", 10, 0.3, 0.5)).isEmpty();
            assertThat(Clock.monotonic() - t0).isGreaterThanOrEqualTo(0.3); // frische Location: kurz warten
            int n = m.calls().size();
            t0 = Clock.monotonic();
            assertThat(m.client().ensureMcp("/tmp/captain/ses_b", 10, 0.3, 0.5)).isEmpty();
            assertThat(Clock.monotonic() - t0).isLessThan(0.2); // keine Server konfiguriert → nicht mehr warten
            assertThat(m.calls()).hasSize(n + 1);
        }
    }

    @Test
    void ensureMcpConnectedNeedsNoWait() {
        try (Mcp m = mcpClient(List.of(List.of(srv("connected"), srv("failed", "kaputt"))))) {
            double t0 = Clock.monotonic();
            assertThat(m.client().ensureMcp("/tmp/captain/ses_a", 10, 3, 0.3)).hasSize(2);
            // neues Verzeichnis: kurz warten, bis opencode die Tools registriert hat
            double elapsed = Clock.monotonic() - t0;
            assertThat(elapsed).isBetween(0.3, 0.6);
            assertThat(m.calls()).hasSize(1);
            t0 = Clock.monotonic();
            assertThat(m.client().ensureMcp("/tmp/captain/ses_a", 10, 3, 0.3)).hasSize(2);
            assertThat(Clock.monotonic() - t0).isLessThan(0.2); // bekannt: sofort
            assertThat(m.calls()).hasSize(2);
        }
    }

    @Test
    void ensureMcpTimeoutAndErrors() {
        try (Mcp m = mcpClient(List.of(List.of(srv("pending"))))) {
            double t0 = Clock.monotonic();
            assertThat(m.client().ensureMcp("/tmp/captain/ses_a", 0.3, 3, 0)).containsExactly(Json.tree(srv("pending")));
            assertThat(Clock.monotonic() - t0).isLessThan(1.5);
        }
        try (Mcp m = mcpClient(List.of(500))) {
            assertThat(m.client().ensureMcp("/tmp/captain/ses_a")).isEmpty(); // Fehler → kein Abbruch des Prompts
        }
    }

    @Test
    void ensureMcpRelearnsAfterOpencodeRestart() {
        try (Mcp m = mcpClient(List.of(List.of()))) {
            OpencodeClient c = m.client();
            c.ensureMcp("/tmp/captain/ses_a", 10, 0.2, 0.5); // lernt: keine Server
            c.events.connects.set(1);
            double t0 = Clock.monotonic();
            c.ensureMcp("/tmp/captain/ses_b", 10, 0.2, 0.5);
            assertThat(Clock.monotonic() - t0).isLessThan(0.15); // erste Verbindung: kein Neustart
            c.events.connects.set(2); // Event-Stream neu verbunden → opencode neu gestartet
            t0 = Clock.monotonic();
            c.ensureMcp("/tmp/captain/ses_c", 10, 0.2, 0.5);
            assertThat(Clock.monotonic() - t0).isGreaterThanOrEqualTo(0.2); // neu lernen
            t0 = Clock.monotonic();
            c.ensureMcp("/tmp/captain/ses_d", 10, 0.2, 0.5);
            assertThat(Clock.monotonic() - t0).isLessThan(0.15);
        }
    }

    /** Folgeseiten nur mit {@code cursor} – opencode lehnt cursor + order mit 400 ab. */
    @Test
    void messagesSincePagesWithCursorOnly() {
        List<Map<String, String>> seen = Collections.synchronizedList(new ArrayList<>());
        List<Map<String, String>> page1 = IntStream.range(0, 50).mapToObj(i -> Map.of("id", "msg_new" + i, "type", "assistant")).toList();
        List<Map<String, String>> page2 = List.of(Map.of("id", "msg_older", "type", "assistant"), Map.of("id", "msg_user", "type", "user"));
        try (FakeHttpServer srv = new FakeHttpServer((req, ex) -> {
            seen.add(req.query());
            if (req.query().containsKey("cursor") && req.query().containsKey("order")) {
                json(ex, 400, Map.of("_tag", "InvalidCursorError"));
            } else if (req.query().containsKey("cursor")) {
                json(ex, 200, Map.of("data", page2, "cursor", Map.of()));
            } else {
                json(ex, 200, Map.of("data", page1, "cursor", Map.of("next", "c2")));
            }
        }); OpencodeClient c = new OpencodeClient(srv.url(), "pw")) {
            List<JsonNode> msgs = c.messagesSince("ses_x", "msg_user", 50, 10);
            List<String> expected = new ArrayList<>(List.of("msg_older"));
            IntStream.iterate(49, i -> i >= 0, i -> i - 1).forEach(i -> expected.add("msg_new" + i));
            assertThat(msgs.stream().map(m -> Json.str(m, "id")).toList()).isEqualTo(expected);
            assertThat(seen.get(0)).isEqualTo(Map.of("limit", "50", "order", "desc"));
            assertThat(seen.get(1)).isEqualTo(Map.of("limit", "50", "cursor", "c2"));
        }
    }
}
