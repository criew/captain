package com.ewerlin.captain.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ewerlin.captain.opencode.Rule;
import com.ewerlin.captain.opencode.SessionPermissions;
import com.ewerlin.captain.policy.Webfetch;
import com.ewerlin.captain.util.Json;

/**
 * Integrationstests: webfetch-Allowlist ({@code CAPTAIN_WEBFETCH_ALLOW}) gegen echtes opencode.
 *
 * <p>Startet eigene opencode-Container aus dem Image mit dem Startskript, der Admin-Config-Vorlage
 * und zwei Hilfsservern im Testprozess: {@link FakeLlm} als Modell (ruft auf Kommando
 * {@code webfetch} auf und hält fest, welche Tools opencode anbietet) und {@link WebTestServer} als
 * Web: ein Server, mehrere Hostnamen per {@code --add-host <name>:host-gateway} –
 * {@code text-example.org} (erlaubt), {@code docs.test} (erlaubt nur unter {@code /handbuch}),
 * {@code evil.test} und {@code text-example.org.evil.test} (fremd). Unterschieden wird am Host-Header.
 *
 * <p>Nachweise: erlaubte URLs liefern Inhalt, fremde Hosts und Präfix-Tricks werden abgelehnt
 * (Session-Regel bzw. Policy), eine <b>Weiterleitung</b> vom erlaubten auf einen fremden Host
 * erreicht den fremden Host nicht (Egress-Filter), eine Admin-Config mit {@code webfetch * allow}
 * schaltet nicht mehr frei, ohne Allowlist bleibt webfetch ganz aus. Braucht Docker.
 */
@Tag("integration")
class WebfetchIntegrationTest {

    static final String PASSWORD = "wf-" + Integration.hex(8);
    static final List<String> HOSTS = List.of("text-example.org", "docs.test", "evil.test", "text-example.org.evil.test");
    static final String BLOCKED = "Blocked by configuration policy";
    static final String DENIED = "Permission denied: webfetch";
    static final List<Rule> ALLOW_ALL = List.of(Rule.allow("*", "*"));
    /** Versuch, webfetch aus der Admin-Config ganz freizugeben. */
    static final String HOSTILE = """

              "permissions": [ { "action": "webfetch", "resource": "*", "effect": "allow" },
                               { "action": "*", "resource": "*", "effect": "allow" } ],
              "experimental": { "policies": [
                { "action": "tool.use", "resource": "webfetch:*", "effect": "allow" } ] },
              "policies": [ { "action": "tool.use", "resource": "webfetch:*", "effect": "allow" } ],""";

    static FakeLlm llm;
    static WebTestServer web;
    @TempDir
    Path tmp;
    final List<Stack> made = new ArrayList<>();

    @BeforeAll
    static void servers() {
        Integration.requireImage();
        llm = new FakeLlm();
        web = new WebTestServer();
    }

    @AfterAll
    static void stopServers() {
        if (llm != null) {
            llm.close();
            web.close();
        }
    }

    @AfterEach
    void cleanup() {
        made.forEach(Stack::close);
    }

    /** Ergebnis eines webfetch: Antwort, angebotene Tools, Anfragen am Web-Testserver. */
    record Fetched(String answer, List<String> offered, List<Map.Entry<String, String>> seen) {
    }

    final class Stack implements AutoCloseable {
        final OpencodeContainer c;
        final List<String> prefixes;

        Stack(String allow, boolean hostile) {
            String text = Integration.template();
            if (hostile) {
                text = text.replaceFirst("\"permissions\": \\[", "\"permissions_orig\": [");
                text = text.replaceFirst("\"model\":", java.util.regex.Matcher.quoteReplacement(HOSTILE + "\n  \"model\":"));
            }
            Path conf = Integration.adminConf(tmp, text, Map.of());
            List<String> args = new ArrayList<>(List.of("--rm", "--add-host", "host.docker.internal:host-gateway"));
            HOSTS.forEach(h -> args.addAll(List.of("--add-host", h + ":host-gateway")));
            args.addAll(List.of(
                    "-e", "OPENCODE_SERVER_PASSWORD=" + PASSWORD,
                    "-e", "LLM_BASE_URL=http://host.docker.internal:" + llm.port() + "/v1",
                    "-e", "LLM_API_KEY=k", "-e", "LLM_MODEL=fake", "-e", "OPENCODE_MODEL=llm/fake",
                    "-e", "CAPTAIN_WEBFETCH_ALLOW=" + allow,
                    "-v", Integration.mountPath(conf) + ":/etc/captain:ro",
                    Integration.IMAGE));
            c = OpencodeContainer.start("captain-wf-" + Integration.hex(8), PASSWORD, llm, args);
            prefixes = Webfetch.parse(allow);
        }

        /** Neue Session wie beim Bot, Modell ruft webfetch(url). */
        Fetched fetch(String url, List<Rule> permissions) {
            int m = web.size();
            OpencodeContainer.Asked a = c.ask("TOOL:webfetch " + Json.write(Map.of("url", url, "format", "text")),
                    permissions, 90, d -> SessionPermissions.of(d, prefixes, false));
            List<Map.Entry<String, String>> all = web.requests();
            return new Fetched(a.answer(), a.offered(), all.subList(Math.min(m, all.size()), all.size()));
        }

        Fetched fetch(String url) {
            return fetch(url, null);
        }

        @Override
        public void close() {
            c.close();
        }
    }

    Stack stack(String allow, boolean hostile) {
        Stack s = new Stack(allow, hostile);
        made.add(s);
        return s;
    }

    /** Bewusst unnormalisiert: Groß-/Kleinschreibung, Schrägstrich am Ende. */
    static String allowValue(int port) {
        return "HTTP://Text-Example.org:" + port + "/ , http://docs.test:" + port + "/handbuch/";
    }

    static Set<String> hosts(List<Map.Entry<String, String>> requests) {
        return requests.stream().map(r -> r.getKey().substring(0, r.getKey().lastIndexOf(':') < 0
                ? r.getKey().length() : r.getKey().lastIndexOf(':'))).collect(Collectors.toSet());
    }

    @Test
    void allowlistRulesPoliciesAndRedirects() {
        int p = web.port();
        Stack s = stack(allowValue(p), false);
        String ok = "http://text-example.org:" + p;

        // erlaubt: Inhalt kommt an, webfetch wird angeboten, Shell & Co. nicht
        Fetched f = s.fetch(ok + "/hallo?x=1");
        assertThat(f.answer()).contains("WEBTEST host=text-example.org:" + p + " path=/hallo?x=1");
        assertThat(f.offered()).contains("webfetch");
        for (String hidden : List.of("shell", "bash", "websearch", "execute", "subagent", "task", "skill", "question")) {
            assertThat(f.offered()).doesNotContain(hidden);
        }
        assertThat(hosts(f.seen())).containsExactly("text-example.org");

        assertThat(s.fetch("http://docs.test:" + p + "/handbuch/kapitel-1").answer()).contains("path=/handbuch/kapitel-1");

        // abgelehnt: fremde Hosts, Präfix-Tricks, anderes Schema/Port/Schreibweise, Pfad außerhalb
        // des Präfixes und ..-Ausbruch daraus
        for (String url : List.of("http://evil.test:" + p + "/x",
                "http://text-example.org.evil.test:" + p + "/x",
                "http://text-example.org:" + p + "@evil.test:" + p + "/x",
                "https://text-example.org:" + p + "/x",
                "http://TEXT-EXAMPLE.ORG:" + p + "/x",
                "http://text-example.org:" + (p < 65535 ? p + 1 : p - 1) + "/x",
                "http://docs.test:" + p + "/geheim",
                "http://docs.test:" + p + "/handbuch-geheim",
                "http://docs.test:" + p + "/handbuch/../geheim",
                "http://docs.test:" + p + "/handbuch/%2e%2e/geheim")) {
            f = s.fetch(url);
            assertThat(f.answer()).as(url).contains(DENIED);
            assertThat(f.seen()).as(url).isEmpty();
        }

        // Backslash: opencode liest ihn als "/", der URL-Parser auch → bleibt beim erlaubten Host
        f = s.fetch(ok + "\\@evil.test:" + p + "/x");
        assertThat(hosts(f.seen())).as(f.seen().toString()).containsExactly("text-example.org");

        // Weiterleitung auf fremden Host: opencode folgt ohne erneute Prüfung, der Egress-Filter
        // blockt das Ziel – evil.test sieht keine Anfrage
        f = s.fetch(ok + "/redirect?to=http://evil.test:" + p + "/geheim");
        assertThat(hosts(f.seen())).containsExactly("text-example.org");
        assertThat(f.answer()).doesNotContain("path=/geheim");
        assertThat(s.c.logs()).contains("blockiert: evil.test:" + p);
        // … auf denselben Host: erlaubt
        assertThat(s.fetch(ok + "/redirect?to=/lokal").answer()).contains("path=/lokal");

        // Gegenprobe ohne Session-Sperren (Session-Regel "* * allow"): die Policies aus start.mjs
        // bilden die Allowlist als harte Obergrenze ab
        assertThat(s.fetch(ok + "/policy", ALLOW_ALL).answer()).contains("path=/policy");
        for (String url : List.of("http://evil.test:" + p + "/x", "http://text-example.org.evil.test:" + p + "/x",
                "http://docs.test:" + p + "/handbuch/../geheim")) {
            f = s.fetch(url, ALLOW_ALL);
            assertThat(f.answer()).as(url).contains(BLOCKED);
            assertThat(f.seen()).as(url).isEmpty();
        }

        // opencode bekommt die Variable nicht, aber den Filter als Proxy
        String env = s.c.env();
        assertThat(env).doesNotContain("CAPTAIN_WEBFETCH_ALLOW", "MM_BOT_TOKEN")
                .contains("HTTP_PROXY=http://127.0.0.1:", "NO_PROXY=localhost,127.0.0.1,::1");
    }

    @Test
    void hostileAdminConfigCannotWiden() {
        int p = web.port();
        Stack s = stack("http://text-example.org:" + p, true);
        Fetched f = s.fetch("http://text-example.org:" + p + "/ok");
        assertThat(f.answer()).contains("path=/ok");
        for (String hidden : List.of("shell", "bash", "websearch", "execute", "subagent", "task", "skill", "question")) {
            assertThat(f.offered()).doesNotContain(hidden);
        }
        f = s.fetch("http://evil.test:" + p + "/x");
        assertThat(f.answer()).contains(DENIED);
        assertThat(f.seen()).isEmpty();
        f = s.fetch("http://evil.test:" + p + "/x", ALLOW_ALL);
        assertThat(f.answer()).contains(BLOCKED);
        assertThat(f.seen()).isEmpty();
        assertThat(s.c.logs()).contains("experimental", "\"policies\""); // vom Startskript verworfen
    }

    @Test
    void withoutAllowlistWebfetchStaysOff() {
        int p = web.port();
        Stack s = stack("", false);
        Fetched f = s.fetch("http://text-example.org:" + p + "/x");
        assertThat(f.offered()).doesNotContain("webfetch");
        assertThat(f.answer()).contains("No tool named");
        assertThat(f.seen()).isEmpty();
        f = s.fetch("http://text-example.org:" + p + "/x", ALLOW_ALL);
        assertThat(f.answer()).contains(BLOCKED);
        assertThat(f.seen()).isEmpty();
        String proxies = s.c.env().lines().filter(l -> l.toUpperCase().startsWith("HTTP")).collect(Collectors.joining());
        assertThat(proxies).doesNotContain("127.0.0.1:");
        assertThat(s.c.logs()).contains("webfetch aus");
    }

    /** Ungültiger Eintrag: opencode startet gar nicht (statt still etwas anderes freizugeben). */
    @Test
    void invalidAllowlistFailsClosed() {
        Integration.Result out = Integration.dockerRun(false, null, 60, "run", "--rm",
                "-e", "OPENCODE_SERVER_PASSWORD=" + PASSWORD,
                "-e", "CAPTAIN_WEBFETCH_ALLOW=http://text-example.org/*", Integration.IMAGE);
        assertThat(out.code()).isNotZero();
        assertThat(out.err()).contains("CAPTAIN_WEBFETCH_ALLOW", "opencode startet nicht");
    }
}
