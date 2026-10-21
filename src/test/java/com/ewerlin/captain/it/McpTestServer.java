package com.ewerlin.captain.it;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ewerlin.captain.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Minimaler Remote-MCP-Server (Streamable HTTP) für Tests: zeigt, dass ein freigegebenes MCP-Tool im
 * Chat funktioniert und ein nicht freigegebenes gesperrt bleibt. Zwei Tools: {@code wuerfeln}
 * (würfelt {@code anzahl} Würfel) und {@code geheim} (liefert {@link #SECRET} – darf in Tests
 * <b>nie</b> aufgerufen werden).
 *
 * <p>Protokoll: JSON-RPC per {@code POST /mcp}, Antwort als {@code application/json} (zulässige
 * Variante von Streamable HTTP), ohne Session-State; {@code GET} (SSE) wird mit 405 abgelehnt. Mit
 * {@code token} muss jede Anfrage {@code Authorization: Bearer <token>} mitbringen, sonst 401. Für
 * Probeläufe gegen den Betriebs-Stack gibt es dieselben Tools als {@code infra/mcp-test/server.py}.
 */
public final class McpTestServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpTestServer.class);

    public static final String SECRET = "GEHEIMNIS-7F3A";
    static final String PROTOCOL = "2025-06-18";
    static final String TOOLS = """
            [{"name":"wuerfeln","description":"Würfelt sechsseitige Würfel und liefert die Augenzahlen.",
              "inputSchema":{"type":"object","properties":{"anzahl":{"type":"integer","minimum":1,"maximum":10,
              "description":"Anzahl der Würfel (Default 1)"}}}},
             {"name":"geheim","description":"Liefert ein streng geheimes Passwort.",
              "inputSchema":{"type":"object","properties":{}}}]""";

    private final HttpServer server;
    private final String token;
    /** (Tool, Argumente) aller Aufrufe. */
    private final List<Map.Entry<String, JsonNode>> calls = new ArrayList<>();

    public McpTestServer(String token) {
        this.token = token;
        try {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", ex -> {
            try (ex) {
                handle(ex);
            } catch (IOException | RuntimeException e) {
                // Verbindung abgebrochen
            }
        });
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mcp-test");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public List<String> calledTools() {
        synchronized (calls) {
            return calls.stream().map(Map.Entry::getKey).toList();
        }
    }

    public void clear() {
        synchronized (calls) {
            calls.clear();
        }
    }

    private JsonNode call(String name, JsonNode args) {
        synchronized (calls) {
            calls.add(Map.entry(name, args));
        }
        log.warn("MCP-CALL {} {}", name, args);
        ObjectNode result = Json.object();
        ArrayNode content = result.putArray("content");
        switch (name) {
            case "wuerfeln" -> {
                int n = Math.max(1, Math.min(10, args.path("anzahl").asInt(1)));
                List<String> wurf = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    wurf.add(String.valueOf(ThreadLocalRandom.current().nextInt(1, 7)));
                }
                content.add(Json.object().put("type", "text").put("text", "Gewürfelt (Testserver): " + String.join(", ", wurf)));
                result.put("isError", false);
            }
            case "geheim" -> {
                content.add(Json.object().put("type", "text").put("text", SECRET));
                result.put("isError", false);
            }
            default -> {
                content.add(Json.object().put("type", "text").put("text", "Unbekanntes Tool: " + name));
                result.put("isError", true);
            }
        }
        return result;
    }

    /** Eine JSON-RPC-Nachricht → Antwort ({@code null} für Notifications). */
    private JsonNode rpc(JsonNode msg) {
        String method = Json.str(msg, "method");
        if (!msg.has("id")) {
            return null;
        }
        JsonNode params = msg.path("params");
        JsonNode result;
        switch (method) {
            case "initialize" -> {
                ObjectNode r = Json.object().put("protocolVersion",
                        Json.truthy(params, "protocolVersion") ? Json.str(params, "protocolVersion") : PROTOCOL);
                r.set("capabilities", Json.parse("{\"tools\":{\"listChanged\":false}}"));
                r.set("serverInfo", Json.parse("{\"name\":\"captain-mcp-test\",\"version\":\"1.0.0\"}"));
                result = r;
            }
            case "ping" -> result = Json.object();
            case "tools/list" -> result = Json.object().set("tools", Json.parse(TOOLS));
            case "tools/call" -> result = call(Json.str(params, "name"),
                    params.path("arguments").isObject() ? params.get("arguments") : Json.object());
            default -> {
                ObjectNode err = Json.object().put("jsonrpc", "2.0");
                err.set("id", msg.get("id"));
                err.set("error", Json.object().put("code", -32601).put("message", "Methode nicht unterstützt: " + method));
                return err;
            }
        }
        ObjectNode out = Json.object().put("jsonrpc", "2.0");
        out.set("id", msg.get("id"));
        out.set("result", result);
        return out;
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if (method.equals("GET") && ex.getRequestURI().getPath().replaceAll("/$", "").matches("|/health")) {
            send(ex, 200, Json.parse("{\"status\":\"ok\"}"));
            return;
        }
        if (!method.equals("POST")) {
            send(ex, 405, null);
            return;
        }
        if (token != null && !("Bearer " + token).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            send(ex, 401, Json.parse("{\"error\":\"unauthorized\"}"));
            return;
        }
        JsonNode payload;
        try {
            payload = Json.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        } catch (JacksonException e) {
            send(ex, 400, Json.parse("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}"));
            return;
        }
        if (payload.isArray()) {
            ArrayNode out = Json.MAPPER.createArrayNode();
            payload.forEach(m -> {
                JsonNode r = rpc(m);
                if (r != null) {
                    out.add(r);
                }
            });
            send(ex, out.isEmpty() ? 202 : 200, out.isEmpty() ? null : out);
            return;
        }
        JsonNode out = rpc(payload);
        send(ex, out == null ? 202 : 200, out);
    }

    private static void send(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] data = body == null ? new byte[0] : Json.write(body).getBytes(StandardCharsets.UTF_8);
        if (body != null) {
            ex.getResponseHeaders().set("Content-Type", "application/json");
        }
        ex.sendResponseHeaders(status, data.length == 0 ? -1 : data.length);
        if (data.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(data);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
