package com.ewerlin.captain.it;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Kleiner HTTP-Testserver für webfetch-Tests: ein Server, mehrere Hostnamen. Der opencode-Container
 * bekommt per {@code --add-host <name>:host-gateway} beliebige Namen (erlaubter Host, fremder Host,
 * {@code <erlaubt>.evil.test} …), die alle hier landen. Unterschieden wird über den
 * {@code Host}-Header; jede Anfrage steht in {@link #requests()}.
 *
 * <ul>
 * <li>{@code /redirect?to=<url>} → {@code 302} auf {@code <url>}</li>
 * <li>sonst {@code 200 text/plain} mit {@code WEBTEST host=<host> path=<pfad>}</li>
 * </ul>
 */
public final class WebTestServer implements AutoCloseable {

    private final HttpServer server;
    private final List<Map.Entry<String, String>> requests = new ArrayList<>(); // (Host, Pfad inkl. Query)

    public WebTestServer() {
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
            Thread t = new Thread(r, "web-test");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    private void handle(HttpExchange ex) throws IOException {
        String host = ex.getRequestHeaders().getFirst("Host");
        String raw = ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
        synchronized (requests) {
            requests.add(Map.entry(host == null ? "" : host, raw));
        }
        if (ex.getRequestURI().getPath().equals("/redirect")) {
            String target = "";
            String query = ex.getRequestURI().getRawQuery();
            for (String pair : query == null ? new String[0] : query.split("&")) {
                if (pair.startsWith("to=")) {
                    target = URLDecoder.decode(pair.substring(3), StandardCharsets.UTF_8);
                }
            }
            ex.getResponseHeaders().set("Location", target);
            ex.sendResponseHeaders(302, -1);
            return;
        }
        byte[] body = ("WEBTEST host=" + host + " path=" + raw).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public List<Map.Entry<String, String>> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public int size() {
        synchronized (requests) {
            return requests.size();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
