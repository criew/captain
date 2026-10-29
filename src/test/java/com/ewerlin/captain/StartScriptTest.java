package com.ewerlin.captain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Führt die Node-Unit-Tests des opencode-Startskripts aus: {@code start.test.mjs} (Admin-Config,
 * Umgebung), {@code webfetch.test.mjs} (webfetch-Allowlist, Egress-Filter) und
 * {@code shared.test.mjs} (geteiltes Verzeichnis).
 *
 * <p>Nutzt ein lokales {@code node} (≥ 18); ohne Node wird übersprungen – im Integrationslauf prüft
 * {@code ConfigLayersIntegrationTest} dasselbe Skript im Image.
 */
class StartScriptTest {

    static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"opencode/start.test.mjs", "opencode/webfetch.test.mjs", "opencode/shared.test.mjs",
            "n8n/captain-umschlag.test.mjs", "n8n/workflow.test.mjs"})
    void startScriptUnitTests(String name) throws Exception {
        assumeTrue(nodeAvailable(), "node nicht installiert");
        Process p = new ProcessBuilder("node", "--test", TestData.ROOT.resolve("infra").resolve(name).toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out.length() > 4000 ? out.substring(out.length() - 4000) : out).isZero();
    }
}
