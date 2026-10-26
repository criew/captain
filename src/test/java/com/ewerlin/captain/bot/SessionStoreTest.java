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
    void sessionKey() {
        assertThat(SessionKeys.of("D", "c1", null)).isEqualTo("dm:c1");
        assertThat(SessionKeys.of("G", "c1", null)).isEqualTo("ch:c1");
        assertThat(SessionKeys.of("O", "c1", null)).isEqualTo("ch:c1");
        assertThat(SessionKeys.of("P", "c1", "")).isEqualTo("ch:c1");
        assertThat(SessionKeys.of("P", "c1", "r1")).isEqualTo("th:r1");
        assertThat(SessionKeys.of("D", "c1", "r1")).isEqualTo("th:r1");
    }

    @Test
    void getMissing() {
        assertThat(new SessionStore(path).get("dm:x")).isNull();
    }

    @Test
    void setGetPersist() throws IOException {
        SessionStore store = new SessionStore(path);
        Map<String, String> entry = store.set("dm:a", "ses_1", "/tmp/captain/ses_1");
        assertThat(entry).isEqualTo(Map.of("opencode_session_id", "ses_1", "directory", "/tmp/captain/ses_1"));
        assertThat(new SessionStore(path).get("dm:a")).isEqualTo(entry);
        assertThat(Json.parse(Files.readString(path))).isEqualTo(Json.tree(Map.of("dm:a", entry)));
    }

    @Test
    void getReturnsCopy() {
        SessionStore store = new SessionStore(path);
        store.set("dm:a", "ses_1", "/w");
        store.get("dm:a").put("opencode_session_id", "manipuliert");
        assertThat(store.get("dm:a").get("opencode_session_id")).isEqualTo("ses_1");
    }

    @Test
    void settingsSurviveSetAndReset() {
        SessionStore store = new SessionStore(path);
        store.setModel("ch:a", "anthropic/m", "high");
        store.set("ch:a", "ses_1", "/w");
        assertThat(store.get("ch:a")).isEqualTo(Map.of(
                "opencode_session_id", "ses_1", "directory", "/w", "model", "anthropic/m", "variant", "high"));
        store.reset("ch:a");
        assertThat(new SessionStore(path).get("ch:a")).isEqualTo(Map.of("model", "anthropic/m", "variant", "high"));
    }

    @Test
    void resetWithoutSettingsRemovesEntry() {
        SessionStore store = new SessionStore(path);
        store.set("th:r", "ses_1", "/w");
        store.reset("th:r");
        store.reset("th:unbekannt");
        assertThat(store.get("th:r")).isNull();
        assertThat(new SessionStore(path).get("th:r")).isNull();
    }

    @Test
    void setSettingNullRemoves() {
        SessionStore store = new SessionStore(path);
        store.setModel("dm:a", "m", "v");
        assertThat(store.setSetting("dm:a", setting("variant", null))).isEqualTo(Map.of("model", "m"));
        assertThat(store.setSetting("dm:a", setting("model", null))).isEmpty();
        assertThat(store.get("dm:a")).isNull();
    }

    @Test
    void setSettingRejectsUnknown() {
        assertThatThrownBy(() -> new SessionStore(path).setSetting("dm:a", setting("effort", "max")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void corruptFileIsMovedAside() throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{kaputt");
        SessionStore store = new SessionStore(path);
        assertThat(store.get("dm:a")).isNull();
        assertThat(Files.readString(Path.of(path + ".corrupt"))).isEqualTo("{kaputt");
    }

    @Test
    void noTempFilesLeft() throws IOException {
        SessionStore store = new SessionStore(path);
        store.set("dm:a", "s", "/w");
        store.setSetting("dm:a", setting("model", "m"));
        try (Stream<Path> files = Files.list(path.getParent())) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("sessions.json");
        }
    }

    @Test
    void failedWriteKeepsOldFile() {
        SessionStore store = new SessionStore(path);
        store.set("dm:a", "s1", "/w");
        store.mover = (from, to) -> {
            throw new IOException("disk voll");
        };
        assertThatThrownBy(() -> store.set("dm:a", "s2", "/w")).isInstanceOf(UncheckedIOException.class);
        assertThat(new SessionStore(path).get("dm:a").get("opencode_session_id")).isEqualTo("s1");
    }

    @Test
    void failedWriteKeepsMemoryState() {
        SessionStore store = new SessionStore(path);
        store.set("dm:a", "s1", "/w");
        store.setSetting("dm:a", setting("model", "m"));
        store.mover = (from, to) -> {
            throw new IOException("disk voll");
        };
        assertThatThrownBy(() -> store.set("dm:a", "s2", "/w")).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> store.setSetting("dm:a", setting("model", null))).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> store.reset("dm:a")).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> store.set("dm:neu", "s3", "/w")).isInstanceOf(UncheckedIOException.class);
        assertThat(store.get("dm:a")).isEqualTo(Map.of("opencode_session_id", "s1", "directory", "/w", "model", "m"));
        assertThat(store.get("dm:neu")).isNull();
    }

    @Test
    void concurrentWrites() throws InterruptedException {
        SessionStore store = new SessionStore(path);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            int n = i;
            threads.add(new Thread(() -> store.set("dm:" + n, "s" + n, "/w")));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }
        SessionStore reloaded = new SessionStore(path);
        for (int i = 0; i < 30; i++) {
            assertThat(reloaded.get("dm:" + i).get("opencode_session_id")).isEqualTo("s" + i);
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
                try (KeyLocks.Held held = locks.lock("ch:serial")) {
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
        try (KeyLocks.Held a = locks.lock("dm:a"); KeyLocks.Held b = locks.lock("dm:b")) {
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
        store.setModel("dm:ä", "m", null);
        assertThat(Files.readString(path, StandardCharsets.UTF_8)).contains("\"model\"");
        assertThat(new SessionStore(path).get("dm:ä")).isEqualTo(Map.of("model", "m"));
    }
}
