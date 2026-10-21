package com.ewerlin.captain.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Consumer;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Gemeinsame HTTP-Grundlage: JDK-{@link HttpClient} (Proxy aus der Umgebung) und Springs {@link RestClient}. */
public final class Http {

    /** Antwort mit Status, Kopfzeilen und vollständig gelesenem Körper. */
    public record Response(int status, HttpHeaders headers, byte[] body) {

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public boolean isError() {
            return status >= 400;
        }
    }

    /** Verarbeitet eine Antwort mit offenem Körper (z. B. zum gestreamten Herunterladen). */
    @FunctionalInterface
    public interface StreamHandler<T> {
        T handle(int status, HttpHeaders headers, InputStream body) throws IOException;
    }

    private final HttpClient client;
    private final RestClient rest;

    public Http(Duration timeout) {
        this.client = newClient(Duration.ofSeconds(10));
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(timeout);
        this.rest = RestClient.builder().requestFactory(factory).build();
    }

    /** JDK-Client mit Proxy aus {@code HTTP(S)_PROXY}/{@code NO_PROXY}, ohne Weiterleitungen. */
    public static HttpClient newClient(Duration connectTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1);
        EnvProxySelector proxy = EnvProxySelector.fromEnvironment();
        if (proxy != null) {
            builder.proxy(proxy);
            if (proxy.authenticator() != null) {
                EnvProxySelector.allowBasicTunneling();
                builder.authenticator(proxy.authenticator());
            }
        }
        return builder.build();
    }

    public HttpClient client() {
        return client;
    }

    /** Schickt eine Anfrage und liest die Antwort ganz; Netzfehler als {@link IOException}. */
    public Response send(String method, URI uri, Consumer<HttpHeaders> headers, byte[] body, MediaType type)
            throws IOException {
        return stream(method, uri, headers, body, type,
                (status, h, in) -> new Response(status, h, in == null ? new byte[0] : in.readAllBytes()));
    }

    /** Schickt eine Anfrage und übergibt die Antwort samt offenem Körper an {@code handler}. */
    public <T> T stream(String method, URI uri, Consumer<HttpHeaders> headers, Object body, MediaType type,
            StreamHandler<T> handler) throws IOException {
        try {
            RestClient.RequestBodySpec spec = rest.method(HttpMethod.valueOf(method)).uri(uri).headers(headers);
            if (body != null) {
                if (type != null) {
                    spec.contentType(type);
                }
                spec.body(body);
            }
            return spec.exchange((request, response) -> {
                InputStream in = response.getBody();
                return handler.handle(response.getStatusCode().value(), response.getHeaders(), in);
            }, true);
        } catch (RestClientException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException(e.getMessage(), e);
        }
    }

    /** URL aus Basis, Pfad und Query (Prozent-kodiert wie httpx, Leerzeichen als {@code %20}). */
    public static URI uri(String base, String path, Map<String, String> query) {
        StringBuilder url = new StringBuilder(base).append(path);
        if (query != null && !query.isEmpty()) {
            StringJoiner q = new StringJoiner("&");
            query.forEach((k, v) -> q.add(encode(k) + "=" + encode(v)));
            url.append('?').append(q);
        }
        return URI.create(url.toString());
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
