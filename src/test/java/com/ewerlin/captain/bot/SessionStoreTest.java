package com.ewerlin.captain.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ewerlin.captain.util.Json;

class SessionStoreTest {

    @TempDir
    Path tmp;
    Path path;

    @BeforeEach
    void setUp() {
        path = tmp.resolve("sub").resolve("sessions.json");
    }

    static Map<String, String> setting(String key, String value) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    @Test
    void legacyKeysAreMigrated() throws IOException {
        Path path = tmp.resolve("alt.json");
        Files.writeString(path, """
                {"dm:c1": {"opencode_session_id": "ses_a", "directory": "/w/ses_a"},
                 "th:r1": {"opencode_session_id": "ses_b", "directory": "/w/ses_b"},
                 "ch:c2": {"model": "llm/x"},
                 "ch:c3": {"model": "alt"}, "matter-ch:c3": {"model": "neu"}}""");
        SessionStore store = new SessionStore(path);
        assertThat(store.get("matter-dm:c1")).containsEntry("opencode_session_id", "ses_a");
        assertThat(store.get("matter-th:r1")).containsEntry("directory", "/w/ses_b");
        assertThat(store.get("matter-ch:c2")).containsEntry("model", "llm/x");
        assertThat(store.get("matter-ch:c3")).containsEntry("model", "neu"); // neuer Key gewinnt
        assertThat(store.get("dm:c1")).isNull();
        assertThat(Json.parse(Files.readString(path)).has("dm:c1")).isFalse(); // gleich gespeichert
        assertThat(new SessionStore(path).get("matter-dm:c1")).containsEntry("opencode_session_id", "ses_a");
    }

    @Test
    void sessionKey() {
        assertThat(SessionKeys.of("D", "c1", null)).isEqualTo("matter-dm:c1");
        assertThat(SessionKeys.of("G", "c1", null)).isEqualTo("matter-ch:c1");
        assertThat(SessionKeys.of("O", "c1", null)).isEqualTo("matter-ch:c1");
        assertThat(SessionKeys.of("P", "c1", "")).isEqualTo("matter-ch:c1");
        assertThat(SessionKeys.of("P", "c1", "r1")).isEqualTo("matter-th:r1");
        assertThat(SessionKeys.of("D", "c1", "r1")).isEqualTo("matter-th:r1");
    }

    @Test
    void getMissing() {
        assertThat(new SessionStore(path).get("matter-dm:x")).isNull();
    }

    @Test
    void setGetPersist() throws IOException {
        SessionStore store = new SessionStore(path);
        Map<String, String> entry = store.set("matter-dm:a", "ses_1", "/tmp/captain/ses_1", 42);
        assertThat(entry).isEqualTo(Map.of("opencode_session_id", "ses_1", "directory", "/tmp/captain/ses_1",
                "last_used", "42"));
        assertThat(new SessionStore(path).get("matter-dm:a")).isEqualTo(entry);
        assertThat(Json.parse(Files.readString(path))).isEqualTo(Json.tree(Map.of("matter-dm:a", entry)));
    }

    @Test
    void getReturnsCopy() {
        SessionStore store = new SessionStore(path);
        store.set("matter-dm:a", "ses_1", "/w");
        store.get("matter-dm:a").put("opencode_session_id", "manipuliert");
        assertThat(store.get("matter-dm:a").get("opencode_session_id")).isEqualTo("ses_1");
    }

    @Test
    void settingsSurviveSetAndReset() {
        SessionStore store = new SessionStore(path);
        store.setModel("matter-ch:a", "anthropic/m", "high");
        store.set("matter-ch:a", "ses_1", "/w", 7);
        assertThat(store.get("matter-ch:a")).isEqualTo(Map.of("opencode_session_id", "ses_1", "directory", "/w",
                "last_used", "7", "model", "anthropic/m", "variant", "high"));
        store.reset("matter-ch:a");
        assertThat(new SessionStore(path).get("matter-ch:a")).isEqualTo(Map.of("model", "anthropic/m", "variant", "high"));
    }

    @Test
    void resetWithoutSettingsRemovesEntry() {
        SessionStore store = new SessionStore(path);
        store.set("matter-th:r", "ses_1", "/w");
        store.reset("matter-th:r");
        store.reset("matter-th:unbekannt");
        assertThat(store.get("matter-th:r")).isNull();
        assertThat(new SessionStore(path).get("matter-th:r")).isNull();
    }

    @Test
    void touchAndLastUsed() {
        SessionStore store = new SessionStore(path);
        store.touch("matter-dm:a", 5); // ohne Session: nichts
        assertThat(store.get("matter-dm:a")).isNull();
        store.set("matter-dm:a", "ses_1", "/w", 10);
        store.touch("matter-dm:a", 20);
        assertThat(SessionStore.lastUsed(new SessionStore(path).get("matter-dm:a"))).isEqualTo(20L);
        assertThat(SessionStore.lastUsed(Map.of("opencode_session_id", "alt"))).isNull();
        assertThat(store.keys()).containsExactly("matter-dm:a");
    }

    @Test
    void expireKeepsSettingsAndOptionalMark() {
        SessionStore store = new SessionStore(path);
        store.setModel("matter-dm:a", "m", null);
        store.set("matter-dm:a", "ses_1", "/w", 1);
        store.set("matter-th:r", "ses_2", "/w", 1);
        store.expire("matter-dm:a", 99, true);
        store.expire("matter-th:r", 99, false);
        assertThat(new SessionStore(path).get("matter-dm:a")).isEqualTo(Map.of("model", "m", "expired_at", "99"));
        assertThat(store.get("matter-th:r")).isNull();
        store.set("matter-dm:a", "ses_3", "/w", 100); // neue Session: Markierung weg
        assertThat(store.get("matter-dm:a")).doesNotContainKey("expired_at");
        store.expire("matter-dm:a", 200, true);
        store.reset("matter-dm:a"); // !neu: auch Markierung weg
        assertThat(store.get("matter-dm:a")).isEqualTo(Map.of("model", "m"));
    }

    @Test
    void setSettingNullRemoves() {
        SessionStore store = new SessionStore(path);
        store.setModel("matter-dm:a", "m", "v");
        assertThat(store.setSetting("matter-dm:a", setting("variant", null))).isEqualTo(Map.of("model", "m"));
        assertThat(store.setSetting("matter-dm:a", setting("model", null))).isEmpty();
        assertThat(store.get("matter-dm:a")).isNull();
    }

    @Test
    void setSettingRejectsUnknown() {
        assertThatThrownBy(() -> new SessionStore(path).setSetting("matter-dm:a", setting("effort", "max")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void corruptFileIsMovedAside() throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{kaputt");
        SessionStore store = new SessionStore(path);
        assertThat(store.get("matter-dm:a")).isNull();
        assertThat(Files.readString(Path.of(path + ".corrupt"))).isEqualTo("{kaputt");
    }

    @Test
    void noTempFilesLeft() throws IOException {
        SessionStore store = new SessionStore(path);
        store.set("matter-dm:a", "s", "/w");
        store.setSetting("matter-dm:a", setting("model", "m"));
        try (Stream<Path> files = Files.list(path.getParent())) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("sessions.json");
        }
    }

    @Test
    void failedWriteKeepsOldFile() {
        SessionStore store = new SessionStore(path);
        store.set("matter-dm:a", "s1", "/w");
        store.mover = (from, to) -> {
            throw new IOException("disk voll");
        };
        assertThatThrownBy(() -> store.set("matter-dm:a", "s2", "/w")).isInstanceOf(UncheckedIOException.class);
        assertThat(new SessionStore(path).get("matter-dm:a").get("opencode_session_id")).isEqualTo("s1");
    }

    @Test
    void failedWriteKeepsMemoryState() {
        SessionStore store = new SessionStore(path);
        store.set("matter-dm:a", "s1", "/w", 1);
        store.setSetting("matter-dm:a", setting("model", "m"));
        store.mover = (from, to) -> {
            throw new IOException("disk voll");
        };
        assertThatThrownBy(() -> store.set("matter-dm:a", "s2", "/w")).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> store.setSetting("matter-dm:a", setting("model", null))).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> store.reset("matter-dm:a")).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> store.set("matter-dm:neu", "s3", "/w")).isInstanceOf(UncheckedIOException.class);
        assertThat(store.get("matter-dm:a")).isEqualTo(Map.of("opencode_session_id", "s1", "directory", "/w",
                "last_used", "1", "model", "m"));
        assertThat(store.get("matter-dm:neu")).isNull();
    }

    @Test
    void concurrentWrites() throws InterruptedException {
        SessionStore store = new SessionStore(path);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            int n = i;
            threads.add(new Thread(() -> store.set("matter-dm:" + n, "s" + n, "/w")));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }
        SessionStore reloaded = new SessionStore(path);
        for (int i = 0; i < 30; i++) {
            assertThat(reloaded.get("matter-dm:" + i).get("opencode_session_id")).isEqualTo("s" + i);
        }
    }

    @Test
    void keyLocksSerializeAndAreReleased() throws InterruptedException {
        KeyLocks locks = new KeyLocks();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            threads.add(new Thread(() -> {
                try (KeyLocks.Held held = locks.lock("matter-ch:serial")) {
                    peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                    Thread.sleep(10);
                    active.decrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }
        assertThat(peak.get()).isEqualTo(1);
        assertThat(locks.size()).isZero(); // ungenutzte Locks werden freigegeben
        try (KeyLocks.Held a = locks.lock("matter-dm:a"); KeyLocks.Held b = locks.lock("matter-dm:b")) {
            assertThat(locks.size()).isEqualTo(2);
        }
        assertThat(locks.size()).isZero();
    }

    @Test
    void newSessionId() {
        String a = SessionKeys.newSessionId();
        String b = SessionKeys.newSessionId();
        assertThat(a).isNotEqualTo(b).matches("ses_[0-9a-f]{32}");
    }

    @Test
    void sessionInstructionsOrderAndPath() {
        Map<String, String> entries = SessionKeys.sessionInstructions("  Du bist X.  ", "/tmp/captain/ses_1");
        assertThat(entries.keySet()).containsExactly("captain-persona", "captain-umgebung");
        assertThat(entries.get("captain-persona")).isEqualTo("Du bist X.");
        assertThat(entries.get("captain-umgebung")).contains("`/tmp/captain/ses_1`", "Shell");
        assertThat(SessionKeys.sessionInstructions("", "/d").keySet()).containsExactly("captain-umgebung");
    }

    @Test
    void fileIsUtf8Json() throws IOException {
        SessionStore store = new SessionStore(path);
        store.setModel("matter-dm:ä", "m", null);
        assertThat(Files.readString(path, StandardCharsets.UTF_8)).contains("\"model\"");
        assertThat(new SessionStore(path).get("matter-dm:ä")).isEqualTo(Map.of("model", "m"));
    }
}
