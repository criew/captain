package com.ewerlin.captain.policy;

import static com.ewerlin.captain.policy.RuleEval.evaluate;
import static com.ewerlin.captain.policy.RuleEval.visible;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.ewerlin.captain.TestData;
import com.ewerlin.captain.bot.SessionKeys;
import com.ewerlin.captain.config.CaptainConfig;
import com.ewerlin.captain.config.ConfigException;
import com.ewerlin.captain.config.ConfigLoader;
import com.ewerlin.captain.opencode.Rule;
import com.ewerlin.captain.opencode.SessionPermissions;
import com.ewerlin.captain.util.Json;

import tools.jackson.databind.JsonNode;

/**
 * Geteiltes Verzeichnis ({@code CAPTAIN_SHARED_DIR}): Pfadprüfung, Session-Regeln, Hinweis.
 *
 * <p>Pfade außerhalb des Session-Verzeichnisses prüft opencode zuerst mit
 * {@code external_directory} = {@code <lexikalisch aufgelöstes Verzeichnis>/*}, danach {@code read}
 * mit dem absoluten Pfad. Dass opencode wirklich so entscheidet, belegt {@code SharedIntegrationTest}.
 */
class SharedDirTest {

    static final JsonNode CASES = TestData.json("shared_dir.json");
    static final String DIR = "/tmp/captain/ses_abc";
    static final List<Rule> BASE = List.of(Rule.deny("*", "*"));
    static final Map<String, String> ENV = Map.of("MM_URL", "http://mm", "MM_BOT_TOKEN", "t", "OPENCODE_URL", "http://oc");

    /** {@code posixpath.normpath} für absolute Pfade. */
    static String normpath(String path) {
        List<String> out = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!out.isEmpty()) {
                    out.removeLast();
                }
            } else {
                out.add(part);
            }
        }
        return "/" + String.join("/", out);
    }

    static String dirname(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }

    static String join(String a, String b) {
        return b.startsWith("/") ? b : (a.endsWith("/") ? a + b : a + "/" + b);
    }

    /** Wie opencode {@code read} prüft: relativ im Session-Verzeichnis, sonst erst external_directory. */
    static String access(List<Rule> rules, String path, boolean isDir) {
        String absolute = normpath(join(DIR, path));
        if (absolute.equals(DIR) || absolute.startsWith(DIR + "/")) {
            String relative = absolute.equals(DIR) ? "." : absolute.substring(DIR.length() + 1);
            return evaluate(rules, "read", relative);
        }
        String parent = isDir ? absolute : dirname(absolute);
        if (!evaluate(rules, "external_directory", join(parent, "*")).equals("allow")) {
            return "deny";
        }
        return evaluate(rules, "read", absolute);
    }

    static Stream<JsonNode> valid() {
        return Json.list(CASES.get("valid")).stream();
    }

    static Stream<JsonNode> home() {
        return Json.list(CASES.get("home")).stream();
    }

    static Stream<String> invalid() {
        return Json.list(CASES.get("invalid")).stream().map(JsonNode::asString);
    }

    @ParameterizedTest
    @MethodSource("valid")
    void normalizeValid(JsonNode c) {
        JsonNode path = c.get("path");
        assertThat(SharedDir.normalize(c.get("input").asString())).isEqualTo(path.isNull() ? null : path.asString());
    }

    @ParameterizedTest
    @MethodSource("home")
    void homeAllowlist(JsonNode c) {
        String path = c.get("path").asString();
        String home = c.get("home").asString();
        if (c.get("ok").asBoolean()) {
            assertThat(SharedDir.normalize(path, home)).isEqualTo(path.replaceAll("/+$", ""));
        } else {
            assertThatThrownBy(() -> SharedDir.normalize(path, home)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageMatching("(?s).*(CAPTAIN_SHARED_DIR|CAPTAIN_HOME).*");
        }
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void normalizeInvalid(String value) {
        assertThatThrownBy(() -> SharedDir.normalize(value)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CAPTAIN_SHARED_DIR");
    }

    static CaptainConfig load(Map<String, String> extra) {
        Map<String, String> env = new HashMap<>(ENV);
        env.putAll(extra);
        return ConfigLoader.load(env::get);
    }

    @Test
    void config() {
        CaptainConfig cfg = load(Map.of());
        assertThat(cfg.sharedDir()).isNull();
        assertThat(cfg.shared()).isFalse();
        cfg = load(Map.of("CAPTAIN_SHARED_DIR", "/srv/captain-shared/"));
        assertThat(cfg.sharedDir()).isEqualTo("/srv/captain-shared");
        assertThat(cfg.shared()).isTrue();
        for (String bad : List.of("relativ", "/", "/etc", "/srv/../etc")) {
            assertThatThrownBy(() -> load(Map.of("CAPTAIN_SHARED_DIR", bad))).isInstanceOf(ConfigException.class)
                    .hasMessageContaining("CAPTAIN_SHARED_DIR");
        }
        // CAPTAIN_HOME: nur <home>/shared[/…] – die Prüfung läuft auch im Bot
        assertThat(load(Map.of("CAPTAIN_HOME", "/opt/captain", "CAPTAIN_SHARED_DIR", "/opt/captain/shared/infos")).shared())
                .isTrue();
        for (String bad : List.of("/opt/captain", "/opt/captain/config", "/opt/captain/sharedX")) {
            assertThatThrownBy(() -> load(Map.of("CAPTAIN_HOME", "/opt/captain", "CAPTAIN_SHARED_DIR", bad)))
                    .isInstanceOf(ConfigException.class).hasMessageContaining("CAPTAIN_HOME");
        }
        assertThatThrownBy(() -> load(Map.of("CAPTAIN_HOME", "opt/captain", "CAPTAIN_SHARED_DIR", "/srv/x")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("CAPTAIN_HOME");
    }

    static Stream<Arguments> admins() {
        return Stream.of(
                Arguments.of(List.of()),
                Arguments.of(List.of(Rule.allow("*", "*"))),
                Arguments.of(List.of(Rule.allow("external_directory", "*"), Rule.allow("read", "*"), Rule.allow("edit", "*"))));
    }

    /** Global: Basis + Admin-Freigaben, danach die Session-Regeln – Admin-Config ändert nichts. */
    @ParameterizedTest
    @MethodSource("admins")
    void rulesReadOnlyBelowShared(List<Rule> admin) {
        List<Rule> rules = new ArrayList<>(BASE);
        rules.addAll(admin);
        rules.addAll(SessionPermissions.of(DIR, List.of(), true));
        record P(String path, boolean dir) {
        }
        for (P p : List.of(new P("/shared", true), new P("/shared/", true), new P("/shared/a.txt", false),
                new P("/shared/sub/dir/b.md", false), new P("/shared/mit leer.txt", false),
                new P("../../../shared/a.txt", false), new P("/shared/sub/../a.txt", false),
                new P("/shared/repo/.github/x.yml", false), new P("/shared/a.gitx/y", false),
                new P("/shared/.gitignore", false), new P("/shared/netrc.md", false), new P("notiz.txt", false))) {
            assertThat(access(rules, p.path(), p.dir())).as(p.path()).isEqualTo("allow");
        }
        for (P p : List.of(new P("/shared/../etc/passwd", false), new P("/sharedX/a.txt", false),
                new P("/shared-x/a", false), new P("/SHARED/a.txt", false), new P("/Shared/a.txt", false),
                new P("/etc/passwd", false), new P("/tmp/captain/ses_other/x.txt", false),
                new P("/shared/../tmp/captain/ses_other/x", false),
                new P("/shared/sub/../../tmp/captain/ses_other/x", false), new P("/", true), new P("/tmp", true),
                new P("/root/.local/share/opencode/opencode.db", false),
                new P("/shared/.git/config", false), new P("/shared/.git", true),
                new P("/shared/repo/.git/config", false), new P("/shared/repo/.git", true),
                new P("/shared/a/b/c/.git/objects/x", false), new P("/shared/repo/.git", false),
                new P("/shared/repo/.GIT/config", false), new P("/shared/repo/.Git", true),
                new P("/shared/mirror.git/config", false), new P("/shared/a/infos.git", true),
                new P("/shared/.netrc", false), new P("/shared/a/.netrc", false), new P("/shared/_netrc", false),
                new P("/shared/.git-credentials", false), new P("/shared/a/b/.git-credentials", false))) {
            assertThat(access(rules, p.path(), p.dir())).as(p.path()).isEqualTo("deny");
        }
        // schreiben nie: edit prüft den absoluten Pfad (nach external_directory)
        for (String path : List.of("/shared", "/shared/a.txt", "/shared/sub/neu.md")) {
            assertThat(evaluate(rules, "edit", path)).as(path).isEqualTo("deny");
        }
        assertThat(evaluate(rules, "edit", "notiz.txt")).isEqualTo("allow");
        // glob/grep: Muster frei, Suchpfad über external_directory
        assertThat(evaluate(rules, "glob", "**/*.md")).isEqualTo("allow");
        assertThat(evaluate(rules, "grep", "x")).isEqualTo("allow");
        assertThat(evaluate(rules, "external_directory", "/shared/*")).isEqualTo("allow");
        assertThat(evaluate(rules, "external_directory", "/shared/repo/.git/*")).isEqualTo("deny");
        assertThat(evaluate(rules, "external_directory", "/tmp/captain/*")).isEqualTo("deny");
    }

    @Test
    void withoutSharedNothingChanges() {
        assertThat(SharedDir.sessionRules(false)).isEmpty();
        assertThat(SessionPermissions.of(DIR, List.of(), false)).isEqualTo(SessionPermissions.of(DIR));
        List<Rule> rules = new ArrayList<>(BASE);
        rules.addAll(SessionPermissions.of(DIR));
        assertThat(access(rules, "/shared/a.txt", false)).isEqualTo("deny");
        assertThat(access(rules, "/shared", true)).isEqualTo("deny");
    }

    @Test
    void rulesAppendedLastAndNothingElseUnlocked() {
        List<Rule> off = SessionPermissions.of(DIR, List.of("http://text-example.org"), false);
        List<Rule> on = SessionPermissions.of(DIR, List.of("http://text-example.org"), true);
        assertThat(on.subList(0, off.size())).isEqualTo(off);
        List<Rule> extra = on.subList(off.size(), on.size());
        assertThat(extra).isEqualTo(SharedDir.sessionRules(true));
        assertThat(extra).allMatch(r -> List.of("external_directory", "read", "edit").contains(r.action()));
        assertThat(extra).allMatch(r -> r.resource().startsWith("/shared"));
        assertThat(extra.stream().filter(r -> r.effect().equals("allow")).toList()).containsExactly(
                Rule.allow("external_directory", "/shared/*"),
                Rule.allow("read", "/shared"),
                Rule.allow("read", "/shared/*"));
        for (String action : List.of("shell", "websearch", "execute", "subagent", "skill", "question",
                "opencode_read_mcp_resource")) {
            assertThat(evaluate(on, action, "/shared/a.txt")).as(action).isEqualTo("deny");
            assertThat(visible(on, action)).as(action).isFalse();
        }
        assertThat(visible(on, "read")).isTrue();
        assertThat(visible(on, "glob")).isTrue();
        assertThat(visible(on, "grep")).isTrue();
    }

    @Test
    void environmentPrompt() {
        String off = SessionKeys.environmentPrompt(DIR);
        assertThat(off).doesNotContain("/shared");
        String on = SessionKeys.environmentPrompt(DIR, List.of(), true);
        assertThat(on).contains("`/shared`", "**nur lesen und suchen**", "PDF",
                "Zugriffe außerhalb werden abgelehnt – einzige Ausnahme ist `/shared`");
        assertThat(SessionKeys.sessionInstructions("P", DIR, List.of(), true).get("captain-umgebung")).isEqualTo(on);
    }
}
