package com.ewerlin.captain.it;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ewerlin.captain.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deterministisches Fake-LLM mit OpenAI-kompatibler Chat-API (nur für Tests).
 *
 * <p>opencode spricht es über den generischen Provider ({@code /v1/chat/completions}, gestreamt
 * oder nicht). Jede Anfrage wird mit den angebotenen Tool-Namen in {@link #requests} festgehalten –
 * so lässt sich prüfen, welche Tools opencode dem Modell überhaupt zeigt.
 *
 * <p>Steuerung über die letzte Nutzernachricht:
 * <ul>
 * <li>{@code TOOL:<name> <json>} → das Modell ruft {@code <name>} mit {@code <json>} auf (auch wenn
 * opencode das Tool gar nicht anbietet),</li>
 * <li>folgt auf einen Tool-Aufruf das Ergebnis, antwortet es mit {@code ERGEBNIS: <Inhalt>},</li>
 * <li>sonst {@code OK}.</li>
 * </ul>
 */
public final class FakeLlm implements AutoCloseable {

    private static final Pattern COMMAND = Pattern.compile("TOOL:(\\S+)(?:\\s+(\\{.*\\}))?", Pattern.DOTALL);

    /** Antwort: Text oder Tool-Aufruf. */
    public record Answer(String text, String tool, JsonNode args) {
    }

    /** Eine Anfrage an das Modell. */
    public record Call(List<String> tools, Answer answer, String auth, JsonNode body) {
    }

    private final HttpServer server;
    private final List<Call> requests = new ArrayList<>();

    public FakeLlm() {
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
            Thread t = new Thread(r, "fake-llm");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public List<Call> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public int size() {
        synchronized (requests) {
            return requests.size();
        }
    }

    /** Angebotene Tools der ersten Anfrage mit Tools ab Index {@code from}. */
    public List<String> offeredSince(int from) {
        List<Call> all = requests();
        for (Call c : all.subList(Math.min(from, all.size()), all.size())) {
            if (!c.tools().isEmpty()) {
                return c.tools();
            }
        }
        return List.of();
    }

    static String text(JsonNode content) {
        if (content == null) {
            return "";
        }
        if (content.isString()) {
            return content.asString();
        }
        if (content.isArray()) {
            List<String> parts = new ArrayList<>();
            content.forEach(p -> parts.add(p.isObject() ? Json.str(p, "text") : ""));
            return String.join("\n", parts);
        }
        return "";
    }

    static Answer decide(JsonNode body) {
        List<JsonNode> messages = Json.list(body.get("messages"));
        if (Json.list(body.get("tools")).isEmpty()) { // z. B. Titel-Generierung
            return new Answer("Testtitel", null, null);
        }
        JsonNode last = messages.isEmpty() ? Json.object() : messages.getLast();
        if (Json.str(last, "role").equals("tool")) {
            return new Answer("ERGEBNIS: " + text(last.get("content")), null, null);
        }
        JsonNode user = Json.object();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (Json.str(messages.get(i), "role").equals("user")) {
                user = messages.get(i);
                break;
            }
        }
        Matcher m = COMMAND.matcher(text(user.get("content")));
        if (m.find()) {
            return new Answer(null, m.group(1), Json.parse(m.group(2) == null ? "{}" : m.group(2)));
        }
        return new Answer("OK", null, null);
    }

    private void handle(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("GET")) {
            if (ex.getRequestURI().getPath().endsWith("/models")) {
                json(ex, 200, Json.parse("{\"object\":\"list\",\"data\":[{\"id\":\"fake\",\"object\":\"model\",\"created\":0,\"owned_by\":\"test\"}]}"));
            } else {
                json(ex, 404, Json.parse("{\"error\":\"not found\"}"));
            }
            return;
        }
        byte[] raw = ex.getRequestBody().readAllBytes();
        JsonNode body = raw.length == 0 ? Json.object() : Json.parse(new String(raw, StandardCharsets.UTF_8));
        List<String> tools = Json.list(body.get("tools")).stream().map(t -> Json.str(t.path("function"), "name")).toList();
        Answer answer = decide(body);
        synchronized (requests) {
            requests.add(new Call(tools, answer, ex.getRequestHeaders().getFirst("Authorization"), body));
        }
        if (body.path("stream").asBoolean(false)) {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                for (JsonNode chunk : chunks(body, answer)) {
                    out.write(("data: " + Json.write(chunk) + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            return;
        }
        json(ex, 200, completion(body, answer));
    }

    private static void json(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] data = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
    }

    private static ObjectNode base(JsonNode body) {
        return Json.object().put("id", "chatcmpl-" + UUID.randomUUID().toString().substring(0, 12))
                .put("created", System.currentTimeMillis() / 1000)
                .put("model", body.path("model").asString("fake"));
    }

    private static JsonNode usage() {
        return Json.tree(Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15));
    }

    private static ObjectNode toolCall(Answer answer, boolean indexed) {
        ObjectNode call = Json.object();
        if (indexed) {
            call.put("index", 0);
        }
        call.put("id", "call_" + UUID.randomUUID().toString().substring(0, 8)).put("type", "function");
        call.set("function", Json.object().put("name", answer.tool()).put("arguments", Json.write(answer.args())));
        return call;
    }

    private static JsonNode completion(JsonNode body, Answer answer) {
        ObjectNode msg = Json.object().put("role", "assistant");
        String finish;
        if (answer.tool() != null) {
            msg.putNull("content");
            msg.putArray("tool_calls").add(toolCall(answer, false));
            finish = "tool_calls";
        } else {
            msg.put("content", answer.text());
            finish = "stop";
        }
        ObjectNode out = base(body).put("object", "chat.completion");
        ObjectNode choice = Json.object().put("index", 0).put("finish_reason", finish);
        choice.set("message", msg);
        out.putArray("choices").add(choice);
        out.set("usage", usage());
        return out;
    }

    private static List<JsonNode> chunks(JsonNode body, Answer answer) {
        ObjectNode first = base(body).put("object", "chat.completion.chunk");
        ObjectNode delta = Json.object().put("role", "assistant");
        String finish;
        if (answer.tool() != null) {
            delta.putArray("tool_calls").add(toolCall(answer, true));
            finish = "tool_calls";
        } else {
            delta.put("content", answer.text());
            finish = "stop";
        }
        ObjectNode c1 = Json.object().put("index", 0).putNull("finish_reason");
        c1.set("delta", delta);
        first.putArray("choices").add(c1);
        ObjectNode last = base(body).put("object", "chat.completion.chunk");
        ObjectNode c2 = Json.object().put("index", 0).put("finish_reason", finish);
        c2.set("delta", Json.object());
        ArrayNode choices = last.putArray("choices");
        choices.add(c2);
        last.set("usage", usage());
        return List.of(first, last);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
