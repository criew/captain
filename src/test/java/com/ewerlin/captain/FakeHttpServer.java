package com.ewerlin.captain;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.ewerlin.captain.util.Json;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;

/** Echter HTTP-Server auf localhost für Tests; die Antworten liefert ein {@link Handler}. */
public final class FakeHttpServer implements AutoCloseable {

    /** Eine empfangene Anfrage. */
    public record Request(String method, String path, Map<String, String> query, Headers headers, byte[] body) {

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public JsonNode json() {
            return body.length == 0 ? null : Json.parse(text());
        }

        public String header(String name) {
            return headers.getFirst(name);
        }
    }

    @FunctionalInterface
    public interface Handler {
        void handle(Request request, HttpExchange exchange) throws IOException;
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fake-http");
        t.setDaemon(true);
        return t;
    });
    private final List<Request> requests = new ArrayList<>();

    public FakeHttpServer(Handler handler) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            try (exchange) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        query(exchange.getRequestURI().getRawQuery()), exchange.getRequestHeaders(), body);
                synchronized (requests) {
                    requests.add(request);
                }
                handler.handle(request, exchange);
            } catch (IOException | RuntimeException e) {
                // Client hat die Verbindung geschlossen o. Ä.
            }
        });
        server.setExecutor(executor);
        server.start();
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return out;
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public List<Request> calls(String method, String path) {
        return requests().stream().filter(r -> r.method().equals(method) && r.path().equals(path)).toList();
    }

    public static void send(HttpExchange ex, int status, byte[] body, String contentType, Map<String, String> headers)
            throws IOException {
        if (contentType != null) {
            ex.getResponseHeaders().set("Content-Type", contentType);
        }
        headers.forEach((k, v) -> ex.getResponseHeaders().set(k, v));
        if (status == 204 || body.length == 0) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    public static void json(HttpExchange ex, int status, Object body) throws IOException {
        String text = body instanceof String s ? s : Json.write(body);
        send(ex, status, text.getBytes(StandardCharsets.UTF_8), "application/json", Map.of());
    }

    public static void text(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, body.getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
