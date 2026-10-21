package com.ewerlin.captain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import com.ewerlin.captain.config.CaptainConfig;
import com.ewerlin.captain.util.Clock;

/** Der Spring-Kontext startet mit Konfiguration aus Eigenschaften und beendet den Bot geordnet. */
class CaptainApplicationTest {

    @TempDir
    Path tmp;

    @Test
    void contextStartsAndStopsWhileMattermostUnreachable() {
        SpringApplication app = new SpringApplication(CaptainApplication.class);
        ConfigurableApplicationContext ctx = app.run(
                "--MM_URL=http://127.0.0.1:9", "--MM_BOT_TOKEN=t", "--OPENCODE_URL=http://127.0.0.1:9",
                "--DATA_DIR=" + tmp.resolve("data"), "--SESSIONS_DIR=/tmp/captain-test",
                "--CAPTAIN_WEBFETCH_ALLOW=HTTP://Text-Example.org/");
        BotLifecycle lifecycle = ctx.getBean(BotLifecycle.class);
        try {
            CaptainConfig cfg = ctx.getBean(CaptainConfig.class);
            assertThat(cfg.mmUrl()).isEqualTo("http://127.0.0.1:9");
            assertThat(cfg.webfetchAllow()).containsExactly("http://text-example.org");
            assertThat(lifecycle.isRunning()).isTrue(); // wartet auf Mattermost
        } finally {
            double t0 = Clock.monotonic();
            ctx.close();
            assertThat(Clock.monotonic() - t0).isLessThan(5.0);
        }
        assertThat(lifecycle.isRunning()).isFalse();
    }
}
