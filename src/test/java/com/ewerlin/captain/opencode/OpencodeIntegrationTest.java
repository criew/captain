package com.ewerlin.captain.opencode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.ewerlin.captain.it.Integration;

/**
 * Integrationstests gegen den laufenden opencode-Server ({@code infra/opencode}).
 *
 * <p>Zugang aus der env-Datei des Test-Setups ({@code OPENCODE_SERVER_PASSWORD},
 * {@code OPENCODE_MODEL}); Pfad per {@code CAPTAIN_OC_ENV} überschreibbar, fehlt sie, wird
 * übersprungen. Server-URL per {@code OPENCODE_URL} (Default {@code http://localhost:4096}),
 * Arbeitsverzeichnis <i>im Container</i> per {@code CAPTAIN_OC_DIR} (Default {@code /tmp} –
 * existiert immer, der Client legt keine Verzeichnisse an).
 *
 * <p>Tool-Aufrufe (Datei schreiben, Shell/Web gesperrt) prüft {@code RestrictIntegrationTest} – ohne
 * Session-Regeln hat eine Session gar keine Tools. Achtung: Mit einem kleinen Modell auf CPU
 * dauert der erste Prompt nach einem Kaltstart ~2 min; die Timeouts sind entsprechend großzügig.
 */
@Tag("integration")
class OpencodeIntegrationTest {

    static final String URL = Integration.env("OPENCODE_URL", "http://localhost:4096");
    static final String DIRECTORY = Integration.env("CAPTAIN_OC_DIR", "/tmp");
    static final double IDLE = 600;

    static OpencodeClient oc;
    static String model;

    @BeforeAll
    static void setUp() {
        Map<String, String> env = Integration.envOrSkip("CAPTAIN_OC_ENV", "C:/source/captain-shared/opencode.env", "opencode-Zugang");
        oc = new OpencodeClient(URL, env.get("OPENCODE_SERVER_PASSWORD"));
        model = env.get("OPENCODE_MODEL");
    }

    @AfterAll
    static void tearDown() {
        if (oc != null) {
            oc.close();
        }
    }

    static PromptOptions options() {
        return PromptOptions.in(DIRECTORY).model(model, null).idleTimeout(IDLE);
    }

    @Test
    void sessionLifecycle() {
        String sid = oc.createSession(DIRECTORY, "captain-test lifecycle", null, null);
        assertThat(sid).startsWith("ses");
        assertThat(oc.sessionExists(sid)).isTrue();
        assertThat(oc.sessionExists("ses_gibtesnicht")).isFalse();
        oc.abort(sid); // idle → harmlos
    }

    @Test
    void listModels() {
        List<ModelInfo> models = oc.listModels();
        assertThat(models).as("keine Modelle").isNotEmpty();
        if (model != null) {
            assertThat(models.stream().map(ModelInfo::id)).contains(model);
        }
    }

    @Test
    void contextAndDeltas() {
        String sid = oc.createSession(DIRECTORY, "captain-test kontext", null, null);
        List<String> deltas = Collections.synchronizedList(new ArrayList<>());
        String first = oc.prompt(sid, "Merk dir die Zahl 42. Antworte nur mit: OK", options().onDelta(deltas::add));
        assertThat(first.strip()).isNotEmpty();
        deltas.clear();
        String second = oc.prompt(sid, "Welche Zahl solltest du dir merken? Antworte mit einem ausführlichen Satz.",
                options().onDelta(deltas::add));
        assertThat(second).contains("42");
        assertThat(deltas.size()).as(deltas.toString()).isGreaterThanOrEqualTo(2);
        assertThat(deltas.getLast()).isEqualTo(second);
    }

    @Test
    void parallelSessions() throws InterruptedException {
        List<String> sids = List.of(oc.createSession(DIRECTORY, "captain-test parallel 1", null, null),
                oc.createSession(DIRECTORY, "captain-test parallel 2", null, null));
        List<String> words = List.of("Apfel", "Birne");
        Map<Integer, String> results = new ConcurrentHashMap<>();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int n = i;
            threads.add(new Thread(() -> {
                try {
                    results.put(n, oc.prompt(sids.get(n), "Antworte nur mit dem Wort: " + words.get(n), options()));
                } catch (Throwable e) {
                    errors.add(e);
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join((long) ((IDLE + 60) * 1000));
        }
        assertThat(errors).isEmpty();
        assertThat(results.get(0)).contains(words.get(0)).doesNotContain(words.get(1));
        assertThat(results.get(1)).contains(words.get(1)).doesNotContain(words.get(0));
    }

    @Test
    void abort() {
        String sid = oc.createSession(DIRECTORY, "captain-test abort", null, null);
        CountDownLatch started = new CountDownLatch(1);
        Thread stopper = new Thread(() -> {
            try {
                started.await((long) IDLE, TimeUnit.SECONDS);
                Thread.sleep(500);
                oc.abort(sid);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        stopper.setDaemon(true);
        stopper.start();
        assertThatThrownBy(() -> oc.prompt(sid,
                "Schreibe einen sehr langen Aufsatz mit 30 Absätzen über die Geschichte der Seefahrt.",
                options().onDelta(d -> started.countDown())))
                .isInstanceOf(PromptInterruptedException.class);
    }
}
