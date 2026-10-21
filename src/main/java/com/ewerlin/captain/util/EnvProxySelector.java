package com.ewerlin.captain.util;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Proxy-Auswahl aus {@code HTTP_PROXY}/{@code HTTPS_PROXY}/{@code NO_PROXY} (auch klein
 * geschrieben) – wie httpx und curl; Javas {@link java.net.http.HttpClient} liest diese Variablen
 * nicht selbst. {@code compose.deploy.yml} setzt sie aus {@code CAPTAIN_HTTP(S)_PROXY} und
 * {@code CAPTAIN_NO_PROXY}; {@code opencode} steht immer in {@code NO_PROXY}.
 */
public class EnvProxySelector extends ProxySelector {

    private final Proxy http;
    private final Proxy https;
    private final List<String> noProxy;
    private final Map<String, PasswordAuthentication> credentials = new HashMap<>(); // "host:port" → Zugang

    public EnvProxySelector(Map<String, String> env) {
        this.http = proxy(first(env, "HTTP_PROXY", "http_proxy"));
        this.https = proxy(first(env, "HTTPS_PROXY", "https_proxy"));
        List<String> hosts = new ArrayList<>();
        for (String item : first(env, "NO_PROXY", "no_proxy").split(",")) {
            String h = item.strip().toLowerCase(Locale.ROOT);
            if (!h.isEmpty()) {
                hosts.add(h);
            }
        }
        this.noProxy = hosts;
    }

    /** Aus der Prozessumgebung; {@code null}, wenn kein Proxy gesetzt ist. */
    public static EnvProxySelector fromEnvironment() {
        EnvProxySelector selector = new EnvProxySelector(System.getenv());
        return selector.http == Proxy.NO_PROXY && selector.https == Proxy.NO_PROXY ? null : selector;
    }

    private static String first(Map<String, String> env, String... names) {
        for (String name : names) {
            String value = env.get(name);
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private Proxy proxy(String value) {
        if (value.isEmpty()) {
            return Proxy.NO_PROXY;
        }
        URI uri = URI.create(value.contains("://") ? value : "http://" + value);
        int port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        String info = uri.getRawUserInfo();
        if (info != null && !info.isEmpty()) {
            int colon = info.indexOf(':');
            String user = URLDecoder.decode(colon < 0 ? info : info.substring(0, colon), StandardCharsets.UTF_8);
            String pass = colon < 0 ? "" : URLDecoder.decode(info.substring(colon + 1), StandardCharsets.UTF_8);
            credentials.put(uri.getHost().toLowerCase(Locale.ROOT) + ":" + port,
                    new PasswordAuthentication(user, pass.toCharArray()));
        }
        return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(uri.getHost(), port));
    }

    /**
     * Zugangsdaten aus der Proxy-URL ({@code http://user:pass@proxy:3128}) für den
     * {@link java.net.http.HttpClient}; {@code null} ohne Zugangsdaten. Basic über CONNECT erlaubt
     * {@link #allowBasicTunneling()}.
     */
    public Authenticator authenticator() {
        if (credentials.isEmpty()) {
            return null;
        }
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY) {
                    return null;
                }
                return credentials.get(getRequestingHost().toLowerCase(Locale.ROOT) + ":" + getRequestingPort());
            }
        };
    }

    /** Das JDK sperrt Basic-Auth für Proxy-Tunnel (HTTPS) standardmäßig; vor dem ersten Client aufrufen. */
    public static void allowBasicTunneling() {
        if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        }
    }

    /** Ziel ohne Proxy? Einträge: Host, {@code .domain}/{@code domain} (inkl. Subdomains), {@code *}. */
    boolean bypass(String host) {
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        for (String entry : noProxy) {
            String e = entry.contains(":") && !entry.contains("]") && entry.indexOf(':') == entry.lastIndexOf(':')
                    ? entry.substring(0, entry.indexOf(':')) : entry;
            if (e.equals("*")) {
                return true;
            }
            String domain = e.startsWith(".") ? e.substring(1) : e;
            if (h.equals(domain) || h.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<Proxy> select(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (bypass(uri.getHost())) {
            return List.of(Proxy.NO_PROXY);
        }
        Proxy proxy = switch (scheme) {
            case "https", "wss" -> https;
            case "http", "ws" -> http;
            default -> Proxy.NO_PROXY;
        };
        return List.of(proxy);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        // nichts – kein Ausweichen auf andere Proxys
    }
}
