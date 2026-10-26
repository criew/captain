package com.ewerlin.captain.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;

class EnvProxySelectorTest {

    static String target(EnvProxySelector s, String uri) {
        Proxy p = s.select(URI.create(uri)).getFirst();
        if (p == Proxy.NO_PROXY) {
            return "direkt";
        }
        InetSocketAddress a = (InetSocketAddress) p.address();
        return a.getHostString() + ":" + a.getPort();
    }

    @Test
    void schemesAndNoProxy() {
        EnvProxySelector s = new EnvProxySelector(Map.of(
                "HTTP_PROXY", "http://proxy:3128",
                "https_proxy", "proxy.firma.intern:8080",
                "NO_PROXY", "opencode, .intern.example,localhost:8065,10.0.0.5"));
        assertThat(target(s, "http://chat.example.org/api")).isEqualTo("proxy:3128");
        assertThat(target(s, "https://chat.example.org/api")).isEqualTo("proxy.firma.intern:8080");
        assertThat(target(s, "wss://chat.example.org/api/v4/websocket")).isEqualTo("proxy.firma.intern:8080");
        assertThat(target(s, "ws://chat.example.org/api/v4/websocket")).isEqualTo("proxy:3128");
        assertThat(target(s, "http://opencode:4096/api")).isEqualTo("direkt");
        assertThat(target(s, "https://mm.intern.example")).isEqualTo("direkt");
        assertThat(target(s, "https://intern.example")).isEqualTo("direkt");
        assertThat(target(s, "https://notintern.example")).isEqualTo("proxy.firma.intern:8080");
        assertThat(target(s, "http://localhost:8065")).isEqualTo("direkt");
        assertThat(target(s, "http://10.0.0.5:8065")).isEqualTo("direkt");
        assertThat(s.authenticator()).isNull();
    }

    @Test
    void wildcardAndNone() {
        assertThat(target(new EnvProxySelector(Map.of("HTTPS_PROXY", "http://p:1", "NO_PROXY", "*")), "https://x")).isEqualTo("direkt");
        assertThat(target(new EnvProxySelector(Map.of()), "https://x")).isEqualTo("direkt");
    }

    @Test
    void credentialsFromUrl() {
        EnvProxySelector s = new EnvProxySelector(Map.of("HTTPS_PROXY", "http://max%40firma:g%3Aheim@proxy:3128"));
        assertThat(target(s, "https://x")).isEqualTo("proxy:3128");
        assertThat(s.authenticator()).isNotNull();
    }
}
