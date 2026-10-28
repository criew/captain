package com.ewerlin.captain.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ewerlin.captain.opencode.OpencodeException;
import com.ewerlin.captain.opencode.PromptInterruptedException;
import com.ewerlin.captain.util.Clock;
import com.ewerlin.captain.util.Http;
import com.ewerlin.captain.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * REST-Eingabekanal: Nachrichten ohne Mattermost, z. B. von n8n.
 *
 * <pre>
 * POST   /api/conversations/{id}/messages   {"text": "…", "sender": "…", "callback_url": "…", "agent": "…"}
 * DELETE /api/conversations/{id}            Unterhaltung vergessen (wie !neu)
 * GET    /api/health                        ohne Token
 * </pre>
 *
 * Alles außer {@code /api/health} braucht {@code Authorization: Bearer <CAPTAIN_API_TOKEN>}. Die
 * {@code id} wählt der Aufrufer ({@code [A-Za-z0-9._:@-]}, z. B. {@code gitlab:42:mr-7}); gleiche
 * ID = gleiche opencode-Session.
 *
 * <p>Ohne {@code callback_url} wartet die Anfrage auf die Antwort ({@code 200}
 * {@code {"conversation", "session", "answer"}}). Mit {@code callback_url} kommt sofort
 * {@code 202}, die Antwort später per {@code POST} als JSON an die URL (bei Fehler zusätzlich
 * {@code "error"}) – für Antworten, die länger dauern als der Timeout des Aufrufers.
 *
 * <p>{@code agent} (optional) wählt einen opencode-Agenten aus {@code CAPTAIN_REST_AGENTS}; er gilt
 * für die ganze Unterhaltung. Unbekannt → {@code 400}, anderer Agent als bisher → {@code 409}
 * (geprüft vor dem {@code 202}).
 */
public final class ApiServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);

    public static final int PORT = 8090;
    static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@-]{0,199}");
    static final Pattern MESSAGES = Pattern.compile("/api/conversations/([^/]+)/messages");
    static final Pattern CONVERSATION = Pattern.compile("/api/conversations/([^/]+)");
    static final int MAX_BODY = 1024 * 1024;
    static final int MAX_TEXT = 200_000;
    /** So viele Anfragen gleichzeitig (warten + laufen), danach {@code 503}. */
    static final int MAX_PENDING = 64;
    /** Zustellversuche eines Callbacks und Pausen dazwischen (Sekunden). */
    static final double[] CALLBACK_RETRY = {2.0, 10.0, 30.0};

    private final Conversations conversations;
    private final byte[] token;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore pending = new Semaphore(MAX_PENDING);
    private final HttpClient callbacks = Http.newClient(Duration.ofSeconds(10));
    /** In Tests verkürzbar. */
    double[] callbackRetry = CALLBACK_RETRY;

    public ApiServer(Conversations conversations, String token, InetSocketAddress address) throws IOException {
        this.conversations = conversations;
        this.token = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        this.server = HttpServer.create(address, 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
    }

    public void start() {
        server.start();
        log.info("REST-Eingabekanal auf Port {}", port());
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getRawPath();
            String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
            if (path.equals("/api/health")) {
                send(ex, 200, Json.object().put("status", "ok"));
                return;
            }
            if (!authorized(ex)) {
                ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                send(ex, 401, error("Token fehlt oder falsch"));
                return;
            }
            Matcher messages = MESSAGES.matcher(path);
            Matcher conversation = CONVERSATION.matcher(path);
            if (messages.matches()) {
                if (!method.equals("POST")) {
                    send(ex, 405, error("nur POST"));
                    return;
                }
                post(ex, messages.group(1));
            } else if (conversation.matches()) {
                if (!method.equals("DELETE")) {
                    send(ex, 405, error("nur DELETE"));
                    return;
                }
                delete(ex, conversation.group(1));
            } else {
                send(ex, 404, error("unbekannter Pfad"));
            }
        } catch (RuntimeException e) {
            log.error("REST: Fehler bei {} {}", ex.getRequestMethod(), ex.getRequestURI().getRawPath(), e);
        }
    }

    private boolean authorized(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        return header != null && MessageDigest.isEqual(token, header.strip().getBytes(StandardCharsets.UTF_8));
    }

    private void post(HttpExchange ex, String id) throws IOException {
        if (!ID.matcher(id).matches()) {
            send(ex, 400, error("ungültige Conversation-ID (erlaubt: A-Z a-z 0-9 . _ : @ -, höchstens 200 Zeichen)"));
            return;
        }
        byte[] raw = readBody(ex.getRequestBody());
        if (raw == null) {
            send(ex, 413, error("Anfrage größer als " + MAX_BODY / 1024 + " KB"));
            return;
        }
        JsonNode body;
        try {
            body = Json.parse(new String(raw, StandardCharsets.UTF_8));
        } catch (JacksonException e) {
            send(ex, 400, error("kein gültiges JSON"));
            return;
        }
        String text = body.path("text").isString() ? body.path("text").asString().strip() : "";
        if (text.isEmpty() || text.length() > MAX_TEXT) {
            send(ex, 400, error("\"text\" fehlt, ist leer oder länger als " + MAX_TEXT + " Zeichen"));
            return;
        }
        String sender = body.path("sender").isString() ? body.path("sender").asString().strip() : null;
        String agent = null;
        if (body.hasNonNull("agent")) {
            agent = body.path("agent").isString() ? body.path("agent").asString().strip() : "";
            if (agent.isEmpty()) {
                send(ex, 400, error("\"agent\" muss ein nicht leerer Text sein"));
                return;
            }
        }
        try {
            conversations.checkAgent(id, agent);
        } catch (AgentRejectedException e) {
            send(ex, e.status(), error(e.getMessage()));
            return;
        }
        URI callback = null;
        if (body.hasNonNull("callback_url")) {
            callback = callbackUri(Json.str(body, "callback_url"));
            if (callback == null) {
                send(ex, 400, error("\"callback_url\" muss eine http(s)-URL sein"));
                return;
            }
        }
        if (!pending.tryAcquire()) {
            send(ex, 503, error("zu viele offene Anfragen"));
            return;
        }
        if (callback == null) {
            try {
                ObjectNode result = answer(id, text, sender, agent);
                send(ex, result.has("error") ? status(result) : 200, result);
            } finally {
                pending.release();
            }
            return;
        }
        URI target = callback;
        String chosen = agent;
        send(ex, 202, Json.object().put("conversation", id).put("status", "accepted"));
        executor.execute(() -> {
            try {
                deliver(target, answer(id, text, sender, chosen));
            } finally {
                pending.release();
            }
        });
    }

    /** Antwort als JSON; Fehler mit {@code "error"} und internem {@code "_status"}. */
    private ObjectNode answer(String id, String text, String sender, String agent) {
        ObjectNode out = Json.object().put("conversation", id);
        try {
            Conversations.Answer answer = conversations.ask(id, text, sender, agent);
            return out.put("session", answer.session()).put("answer", answer.text());
        } catch (AgentRejectedException e) {
            return out.put("error", e.getMessage()).put("_status", e.status());
        } catch (PromptInterruptedException e) {
            return out.put("error", "abgebrochen: " + e.getMessage()).put("_status", 409);
        } catch (OpencodeException e) {
            log.warn("REST {}: opencode-Fehler: {}", id, e.getMessage());
            return out.put("error", "opencode-Fehler: " + e.getMessage()).put("_status", 502);
        } catch (RuntimeException e) {
            log.error("REST {}: Antwort fehlgeschlagen", id, e);
            return out.put("error", "Fehler: " + e.getMessage()).put("_status", 500);
        }
    }

    private static int status(ObjectNode result) {
        int status = result.path("_status").asInt(500);
        result.remove("_status");
        return status;
    }

    private void delete(HttpExchange ex, String id) throws IOException {
        if (!ID.matcher(id).matches()) {
            send(ex, 400, error("ungültige Conversation-ID"));
            return;
        }
        boolean known = conversations.forget(id);
        send(ex, known ? 200 : 404, known ? Json.object().put("conversation", id).put("status", "forgotten")
                : error("Unterhaltung unbekannt"));
    }

    /** Antwort per POST an {@code callback_url}; bei Fehlschlag einige neue Versuche. */
    void deliver(URI target, ObjectNode result) {
        result.remove("_status");
        byte[] json = Json.write(result).getBytes(StandardCharsets.UTF_8);
        String id = Json.str(result, "conversation");
        for (int attempt = 0; ; attempt++) {
            String problem;
            try {
                HttpResponse<Void> response = callbacks.send(HttpRequest.newBuilder(target)
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header("X-Captain-Conversation", id)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(json)).build(),
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() < 300) {
                    log.info("REST {}: Antwort an Callback zugestellt (HTTP {})", id, response.statusCode());
                    return;
                }
                problem = "HTTP " + response.statusCode();
            } catch (IOException e) {
                problem = e.toString();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (attempt >= callbackRetry.length) {
                log.error("REST {}: Callback {} nicht zustellbar ({}) – Antwort verloren", id, target.getHost(), problem);
                return;
            }
            log.warn("REST {}: Callback {} fehlgeschlagen ({}), neuer Versuch in {} s", id, target.getHost(), problem,
                    callbackRetry[attempt]);
            Clock.sleep(callbackRetry[attempt]);
        }
    }

    static URI callbackUri(String value) {
        try {
            URI uri = new URI(value.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null) {
                return uri;
            }
        } catch (URISyntaxException e) {
            // ungültig
        }
        return null;
    }

    /** Body lesen; {@code null}, wenn er {@link #MAX_BODY} überschreitet. */
    private static byte[] readBody(InputStream in) throws IOException {
        byte[] data = in.readNBytes(MAX_BODY + 1);
        return data.length > MAX_BODY ? null : data;
    }

    private static ObjectNode error(String message) {
        return Json.object().put("error", message);
    }

    private static void send(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
