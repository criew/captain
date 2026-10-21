package com.ewerlin.captain.policy;

import static com.ewerlin.captain.policy.RuleEval.evaluate;
import static com.ewerlin.captain.policy.RuleEval.visible;
import static com.ewerlin.captain.policy.RuleEval.wildcard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.ewerlin.captain.TestData;
import com.ewerlin.captain.bot.SessionKeys;
import com.ewerlin.captain.config.ConfigException;
import com.ewerlin.captain.config.ConfigLoader;
import com.ewerlin.captain.opencode.Rule;
import com.ewerlin.captain.opencode.SessionPermissions;
import com.ewerlin.captain.util.Json;

import tools.jackson.databind.JsonNode;

/** webfetch-Allowlist: Normalisierung, Session-Regeln, Angriffsmuster. */
class WebfetchTest {

    static final JsonNode CASES = TestData.json("webfetch_allow.json");
    static final String DIR = "/tmp/captain/ses_abc";
    static final Map<String, String> ENV = Map.of("MM_URL", "http://mm", "MM_BOT_TOKEN", "t", "OPENCODE_URL", "http://oc");

    static Stream<JsonNode> valid() {
        return Json.list(CASES.get("valid")).stream();
    }

    static Stream<String> invalid() {
        return Json.list(CASES.get("invalid")).stream().map(JsonNode::asString);
    }

    static Stream<JsonNode> match() {
        return Json.list(CASES.get("match")).stream();
    }

    static List<String> strings(JsonNode array) {
        return Json.list(array).stream().map(JsonNode::asString).toList();
    }

    @Test
    void wildcardReplica() {
        assertThat(wildcard("a/b", "a/*")).isTrue();
        assertThat(wildcard("a\\b", "a/*")).isTrue();
        assertThat(wildcard("abc", "a?c")).isTrue();
        assertThat(wildcard("ABC", "abc")).isFalse();
        assertThat(wildcard("x\ny", "x*y")).isTrue();
    }

    @ParameterizedTest
    @MethodSource("valid")
    void parseValid(JsonNode c) {
        assertThat(Webfetch.parse(c.get("input").asString())).isEqualTo(strings(c.get("prefixes")));
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void parseInvalid(String entry) {
        assertThatThrownBy(() -> Webfetch.parse(entry)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CAPTAIN_WEBFETCH_ALLOW");
        // auch zwischen gültigen Einträgen
        assertThatThrownBy(() -> Webfetch.parse("http://ok.example, " + entry)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("match")
    void patternsAndAttacks(JsonNode group) {
        List<String> prefixes = Webfetch.parse(group.get("allow").asString());
        assertThat(Webfetch.allowPatterns(prefixes)).isEqualTo(strings(group.get("patterns")));
        for (String pattern : strings(group.get("patterns"))) {
            assertThat(pattern).doesNotContain("?"); // wäre ein Platzhalter für ein beliebiges Zeichen
        }
        List<Rule> rules = SessionPermissions.of(DIR, prefixes, false);
        for (JsonNode c : Json.list(group.get("cases"))) {
            String url = c.get(0).asString();
            assertThat(evaluate(rules, "webfetch", url)).as(url).isEqualTo(c.get(1).asBoolean() ? "allow" : "deny");
        }
    }

    static Stream<Arguments> admins() {
        return Stream.of(
                Arguments.of(List.of(Rule.allow("webfetch", "*"))),
                Arguments.of(List.of(Rule.allow("*", "*"))),
                Arguments.of(List.of(Rule.allow("webfetch", "http://evil.com/*"))));
    }

    /** Global: Basis {@code * * deny} + Admin-Freigaben, danach die Session-Regeln. */
    @ParameterizedTest
    @MethodSource("admins")
    void adminConfigCannotWidenAllowlist(List<Rule> admin) {
        JsonNode group = CASES.get("match").get(0);
        List<String> prefixes = Webfetch.parse(group.get("allow").asString());
        List<Rule> rules = new ArrayList<>(List.of(Rule.deny("*", "*")));
        rules.addAll(admin);
        rules.addAll(SessionPermissions.of(DIR, prefixes, false));
        for (JsonNode c : Json.list(group.get("cases"))) {
            String url = c.get(0).asString();
            assertThat(evaluate(rules, "webfetch", url)).as(url).isEqualTo(c.get(1).asBoolean() ? "allow" : "deny");
        }
        assertThat(evaluate(rules, "webfetch", "http://evil.com/x")).isEqualTo("deny");
        // ohne Allowlist bleibt webfetch ganz aus – auch bei Admin-Freigabe
        rules = new ArrayList<>(List.of(Rule.deny("*", "*")));
        rules.addAll(admin);
        rules.addAll(SessionPermissions.of(DIR));
        assertThat(evaluate(rules, "webfetch", "http://text-example.org/")).isEqualTo("deny");
        assertThat(visible(rules, "webfetch")).isFalse();
    }

    @Test
    void sessionRulesLayout() {
        List<Rule> base = SessionPermissions.of(DIR);
        List<Rule> rules = SessionPermissions.of(DIR, List.of("http://text-example.org"), false);
        assertThat(rules.subList(0, base.size())).isEqualTo(base); // Allowlist nur hinten angehängt
        List<Rule> extra = rules.subList(base.size(), rules.size());
        assertThat(base).contains(Rule.deny("webfetch", "*"));
        assertThat(extra.subList(0, 2)).containsExactly(
                Rule.allow("webfetch", "http://text-example.org"),
                Rule.allow("webfetch", "http://text-example.org/*"));
        assertThat(extra.subList(2, extra.size())).allMatch(r -> r.action().equals("webfetch") && r.effect().equals("deny"));
        assertThat(visible(rules, "webfetch")).isTrue();
        assertThat(visible(base, "webfetch")).isFalse();
    }

    @Test
    void nothingElseUnlocked() {
        List<Rule> rules = SessionPermissions.of(DIR, List.of("http://text-example.org"), false);
        for (String action : List.of("shell", "websearch", "execute", "subagent", "skill", "question",
                "external_directory", "opencode_read_mcp_resource")) {
            assertThat(evaluate(rules, action, "http://text-example.org/")).as(action).isEqualTo("deny");
            assertThat(visible(rules, action)).as(action).isFalse();
        }
        assertThat(evaluate(rules, "read", "/etc/passwd")).isEqualTo("deny");
    }

    @Test
    void configReadsAllowlist() {
        assertThat(ConfigLoader.load(ENV::get).webfetchAllow()).isEmpty();
        Map<String, String> env = new java.util.HashMap<>(ENV);
        env.put("CAPTAIN_WEBFETCH_ALLOW", "HTTP://Text-Example.org/, https://b.org:443");
        assertThat(ConfigLoader.load(env::get).webfetchAllow()).containsExactly("http://text-example.org", "https://b.org");
        env.put("CAPTAIN_WEBFETCH_ALLOW", "http://text-example.org/*");
        assertThatThrownBy(() -> ConfigLoader.load(env::get)).isInstanceOf(ConfigException.class)
                .hasMessageContaining("CAPTAIN_WEBFETCH_ALLOW");
    }

    @Test
    void environmentPromptMentionsAllowlist() {
        String off = SessionKeys.environmentPrompt(DIR);
        assertThat(off).contains("keinen Internet-Zugriff").doesNotContain("webfetch");
        String on = SessionKeys.environmentPrompt(DIR, List.of("http://text-example.org", "https://b.org/x"), false);
        assertThat(on).contains("`webfetch`", "`http://text-example.org`", "`https://b.org/x`", "keine Websuche", "**keine** Shell")
                .doesNotContain("keinen Internet-Zugriff");
        Map<String, String> entries = SessionKeys.sessionInstructions("P", DIR, List.of("http://text-example.org"), false);
        assertThat(entries.get("captain-umgebung"))
                .isEqualTo(SessionKeys.environmentPrompt(DIR, List.of("http://text-example.org"), false));
    }
}
