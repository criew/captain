package com.ewerlin.captain.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigLoaderTest {

    static final Map<String, String> BASE = Map.of(
            "MM_URL", "http://localhost:8065/",
            "MM_BOT_TOKEN", "tok",
            "OPENCODE_URL", "http://localhost:4096");

    @TempDir
    Path tmp;

    static CaptainConfig load(Map<String, String> extra) {
        Map<String, String> env = new HashMap<>(BASE);
        env.putAll(extra);
        return ConfigLoader.load(env::get);
    }

    static CaptainConfig load(String path, Map<String, String> env) {
        return ConfigLoader.load(path, env::get);
    }

    @Test
    void envOnlyWithDefaults() {
        CaptainConfig cfg = load(Map.of());
        assertThat(cfg.mmUrl()).isEqualTo("http://localhost:8065");
        assertThat(cfg.mmBotToken()).isEqualTo("tok");
        assertThat(cfg.opencodeUrl()).isEqualTo("http://localhost:4096");
        assertThat(cfg.opencodePassword()).isNull();
        assertThat(cfg.opencodeModel()).isNull();
        assertThat(cfg.opencodeVariant()).isNull();
        assertThat(cfg.sessionsDir()).isEqualTo("/tmp/captain");
        assertThat(cfg.systemPrompt()).isEqualTo(ConfigLoader.DEFAULT_SYSTEM_PROMPT);
        assertThat(cfg.dataDir()).isEqualTo("data");
        assertThat(cfg.apiToken()).isNull();
        assertThat(cfg.historyMaxPosts()).isEqualTo(50);
        assertThat(cfg.historyMaxChars()).isEqualTo(8000);
    }

    @Test
    void historyLimits() {
        CaptainConfig cfg = load(Map.of("HISTORY_MAX_POSTS", " 10 ", "HISTORY_MAX_CHARS", "0"));
        assertThat(cfg.historyMaxPosts()).isEqualTo(10);
        assertThat(cfg.historyMaxChars()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"HISTORY_MAX_POSTS,viele", "HISTORY_MAX_CHARS,-1", "HISTORY_MAX_POSTS,1.5"})
    void historyLimitsInvalid(String key, String value) {
        assertThatThrownBy(() -> load(Map.of(key, value))).isInstanceOf(ConfigException.class).hasMessageContaining(key);
    }

    @Test
    void historyLimitsFromJson() throws IOException {
        Path path = write("c.json", "{\"MM_URL\": \"http://localhost:8065/\", \"MM_BOT_TOKEN\": \"tok\", "
                + "\"OPENCODE_URL\": \"http://localhost:4096\", \"HISTORY_MAX_POSTS\": 5}");
        assertThat(load(path.toString(), Map.of()).historyMaxPosts()).isEqualTo(5);
    }

    @Test
    void missingRequiredValues() {
        assertThatThrownBy(() -> ConfigLoader.load(Map.of("MM_URL", "x")::get))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("MM_BOT_TOKEN").hasMessageContaining("OPENCODE_URL");
    }

    @Test
    void jsonFileAndEnvOverride() throws IOException {
        Path path = write("captain.json", "{\"MM_URL\": \"http://localhost:8065/\", \"MM_BOT_TOKEN\": \"tok\", "
                + "\"OPENCODE_URL\": \"http://localhost:4096\", \"OPENCODE_MODEL\": \"llm/x\", "
                + "\"SESSIONS_DIR\": \"/ws/\"}");
        CaptainConfig cfg = ConfigLoader.load(Map.of("CAPTAIN_CONFIG", path.toString(),
                "OPENCODE_MODEL", "llm/y", "MM_BOT_TOKEN", "")::get);
        assertThat(cfg.opencodeModel()).isEqualTo("llm/y"); // Umgebung gewinnt
        assertThat(cfg.mmBotToken()).isEqualTo("tok"); // leere Umgebung überschreibt nicht
        assertThat(cfg.sessionsDir()).isEqualTo("/ws");
    }

    @Test
    void explicitPathArgument() throws IOException {
        Path path = write("c.json", "{\"MM_URL\": \"http://localhost:8065/\", \"MM_BOT_TOKEN\": \"tok\", "
                + "\"OPENCODE_URL\": \"http://localhost:4096\"}");
        assertThat(load(path.toString(), Map.of()).mmBotToken()).isEqualTo("tok");
    }

    @Test
    void apiToken() {
        assertThat(load(Map.of("CAPTAIN_API_TOKEN", " " + "a".repeat(32) + " ")).apiToken()).isEqualTo("a".repeat(32));
        assertThat(load(Map.of("CAPTAIN_API_TOKEN", "")).apiToken()).isNull();
        assertThatThrownBy(() -> load(Map.of("CAPTAIN_API_TOKEN", "kurz")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("CAPTAIN_API_TOKEN");
        assertThatThrownBy(() -> load(Map.of("CAPTAIN_API_TOKEN", "a".repeat(20) + " " + "b".repeat(20))))
                .isInstanceOf(ConfigException.class);
    }

    @Test
    void allowedUsersIsGone() throws IOException {
        Path path = write("c.json", "{\"MM_URL\": \"http://x\", \"MM_BOT_TOKEN\": \"t\", \"OPENCODE_URL\": \"http://y\", "
                + "\"ALLOWED_USERS\": [\"alice\"]}");
        assertThatThrownBy(() -> load(path.toString(), Map.of())).hasMessageContaining("Unbekannte Schlüssel")
                .hasMessageContaining("ALLOWED_USERS");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"{kaputt|kein gültiges JSON", "[1, 2]|JSON-Objekt", "{\"FOO\": 1}|Unbekannte"})
    void invalidFile(String content, String message) throws IOException {
        Path path = write("c.json", content);
        assertThatThrownBy(() -> load(path.toString(), BASE)).isInstanceOf(ConfigException.class).hasMessageContaining(message);
    }

    @Test
    void missingFile() {
        assertThatThrownBy(() -> load(tmp.resolve("nope.json").toString(), BASE))
                .isInstanceOf(ConfigException.class).hasMessageContaining("fehlt");
    }

    @Test
    void opencodeServerPasswordFallback() {
        Map<String, String> base = Map.of("MM_URL", "http://mm", "MM_BOT_TOKEN", "t", "OPENCODE_URL", "http://oc");
        Map<String, String> env = new HashMap<>(base);
        env.put("OPENCODE_SERVER_PASSWORD", "srv");
        assertThat(ConfigLoader.load(env::get).opencodePassword()).isEqualTo("srv");
        env.put("OPENCODE_PASSWORD", "pw");
        assertThat(ConfigLoader.load(env::get).opencodePassword()).isEqualTo("pw");
    }

    @Test
    void systemPromptFromEnv() {
        assertThat(load(Map.of("CAPTAIN_SYSTEM_PROMPT", "  Du bist CaptainSuperman.  ")).systemPrompt())
                .isEqualTo("Du bist CaptainSuperman.");
    }

    @Test
    void systemPromptFileWins() throws IOException {
        Path path = write("persona.txt", "Du bist CaptainSuperman,\nlustig.\n");
        CaptainConfig cfg = load(Map.of("CAPTAIN_SYSTEM_PROMPT", "Env", "CAPTAIN_SYSTEM_PROMPT_FILE", path.toString()));
        assertThat(cfg.systemPrompt()).isEqualTo("Du bist CaptainSuperman,\nlustig.");
    }

    @Test
    void systemPromptFileMissing() {
        assertThatThrownBy(() -> load(Map.of("CAPTAIN_SYSTEM_PROMPT_FILE", tmp.resolve("fehlt.txt").toString())))
                .isInstanceOf(ConfigException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "relativ/pfad", "data", "/tmp/..", "C:/", "//"})
    void sessionsDirRejected(String value) {
        assertThatThrownBy(() -> load(Map.of("SESSIONS_DIR", value))).isInstanceOf(ConfigException.class);
    }

    @Test
    void sessionsDirNormalized() {
        assertThat(load(Map.of("SESSIONS_DIR", "/tmp/captain//")).sessionsDir()).isEqualTo("/tmp/captain");
    }

    @Test
    void catchupMaxAge() {
        assertThat(load(Map.of()).catchupMaxAge()).isEqualTo(Duration.ofHours(24));
        assertThat(load(Map.of("CATCHUP_MAX_AGE", "90m")).catchupMaxAge()).isEqualTo(Duration.ofSeconds(5400));
        assertThat(load(Map.of("CATCHUP_MAX_AGE", "3600")).catchupMaxAge()).isEqualTo(Duration.ofSeconds(3600));
        assertThat(ConfigLoader.parseDuration("2d")).isEqualTo(Duration.ofDays(2));
        assertThat(ConfigLoader.parseDuration(1.5)).isEqualTo(Duration.ofMillis(1500));
        assertThatThrownBy(() -> load(Map.of("CATCHUP_MAX_AGE", "einen Tag"))).isInstanceOf(ConfigException.class);
    }

    @Test
    void catchupMaxAgeZeroAndNegative() {
        assertThat(load(Map.of("CATCHUP_MAX_AGE", "0")).catchupMaxAge()).isZero();
        for (String bad : new String[] {"-5", "-1h"}) {
            assertThatThrownBy(() -> load(Map.of("CATCHUP_MAX_AGE", bad))).isInstanceOf(ConfigException.class);
        }
        assertThatThrownBy(() -> ConfigLoader.parseDuration(-5)).isInstanceOf(ConfigException.class);
    }

    @Test
    void sessionMaxIdle() {
        assertThat(load(Map.of()).sessionMaxIdle()).isEqualTo(Duration.ofDays(14));
        assertThat(load(Map.of("SESSION_MAX_IDLE", "36h")).sessionMaxIdle()).isEqualTo(Duration.ofHours(36));
        assertThat(load(Map.of("SESSION_MAX_IDLE", "0")).sessionMaxIdle()).isZero();
        assertThatThrownBy(() -> load(Map.of("SESSION_MAX_IDLE", "nie"))).isInstanceOf(ConfigException.class);
    }

    @Test
    void maxAttachmentMbDefaultAndValues() {
        CaptainConfig cfg = load(Map.of());
        assertThat(cfg.maxAttachmentMb()).isEqualTo(20);
        assertThat(cfg.maxAttachmentBytes()).isEqualTo(20L * 1024 * 1024);
        assertThat(load(Map.of("MAX_ATTACHMENT_MB", "0,5")).maxAttachmentBytes()).isEqualTo(512L * 1024);
        assertThat(load(Map.of("MAX_ATTACHMENT_MB", "0")).maxAttachmentBytes()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "zwanzig", "inf", "nan"})
    void maxAttachmentMbInvalid(String value) {
        assertThatThrownBy(() -> load(Map.of("MAX_ATTACHMENT_MB", value)))
                .isInstanceOf(ConfigException.class).hasMessageContaining("MAX_ATTACHMENT_MB");
    }

    private Path write(String name, String content) throws IOException {
        Path path = tmp.resolve(name);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    @Test
    void restAgents() {
        assertThat(load(Map.of()).restAgents()).isEmpty();
        CaptainConfig cfg = load(Map.of("CAPTAIN_REST_AGENTS", " reviewer, doku ",
                "CAPTAIN_AGENT_TOOLS", "reviewer: gitlab_get_mr_diff, gitlab_create_note gitlab_create_note; doku: wiki_*"));
        assertThat(cfg.restAgents()).containsExactly(
                Map.entry("reviewer", List.of("gitlab_get_mr_diff", "gitlab_create_note")),
                Map.entry("doku", List.of("wiki_*")));
        assertThat(cfg.agentTools()).containsExactly("gitlab_get_mr_diff", "gitlab_create_note", "wiki_*");
        assertThat(load(Map.of("CAPTAIN_REST_AGENTS", "reviewer")).restAgents()).containsEntry("reviewer", List.of());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "rev/iewer|",
            "reviewer|andere: x_y",
            "reviewer|gitlab_note",
            "reviewer|reviewer: shell",
            "reviewer|reviewer: opencode_list_mcp_resources",
            "reviewer|reviewer: read",
            "reviewer|reviewer: *",
            "reviewer|reviewer: a\"b"})
    void restAgentsRejected(String agents, String tools) {
        Map<String, String> env = new HashMap<>(Map.of("CAPTAIN_REST_AGENTS", agents));
        if (tools != null) {
            env.put("CAPTAIN_AGENT_TOOLS", tools);
        }
        assertThatThrownBy(() -> load(env)).isInstanceOf(ConfigException.class).hasMessageContaining("CAPTAIN_");
    }
}
