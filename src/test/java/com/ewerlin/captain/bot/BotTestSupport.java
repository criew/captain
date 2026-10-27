package com.ewerlin.captain.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import com.ewerlin.captain.mattermost.Post;
import com.ewerlin.captain.util.Clock;

/** Gemeinsame Helfer der Bot-Tests. */
final class BotTestSupport {

    private static final AtomicInteger N = new AtomicInteger();

    private BotTestSupport() {
    }

    /** Post wie aus dem WebSocket (Defaults: DM, Kanal c1, alice). */
    static PostBuilder post(String text) {
        return new PostBuilder(text);
    }

    static final class PostBuilder {
        private final String text;
        private String ch = "c1";
        private String ctype = "D";
        private String root = "";
        private String user = "u_alice";
        private List<String> files = List.of();
        private boolean fromBot;
        private long at;

        PostBuilder(String text) {
            this.text = text;
        }

        PostBuilder ch(String v) { ch = v; return this; }
        PostBuilder type(String v) { ctype = v; return this; }
        PostBuilder root(String v) { root = v; return this; }
        PostBuilder user(String v) { user = v; return this; }
        PostBuilder files(String... v) { files = List.of(v); return this; }
        PostBuilder fromBot() { fromBot = true; return this; }
        PostBuilder at(long v) { at = v; return this; }

        Post build() {
            String name = FakeMattermost.USERS.get(user).path("username").asString();
            return new Post("p" + N.incrementAndGet(), ch, ctype, root, user, name, text, files, fromBot, at);
        }
    }

    /** Session-IDs {@code ses_1}, {@code ses_2}, … statt zufälliger. */
    static void countingIds(Bot bot) {
        AtomicInteger ids = new AtomicInteger();
        bot.newSessionId = () -> "ses_" + ids.incrementAndGet();
    }

    /** Nebenaufgaben und Sofort-Befehle in einem FIFO-Thread → {@link #waitIdle} kann sie abwarten. */
    static void fifoSide(Bot bot) {
        bot.side.shutdown();
        bot.commands.shutdown();
        bot.side = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-side");
            t.setDaemon(true);
            return t;
        });
        bot.commands = bot.side;
    }

    /** Wartet, bis Nachschlagen, Abarbeiter und Nebenaufgaben fertig sind. */
    static void waitIdle(Bot bot) {
        waitIdle(bot, 5.0);
    }

    static void waitIdle(Bot bot, double timeout) {
        double deadline = Clock.monotonic() + timeout;
        try {
            bot.lookup.submit(() -> { }).get((long) timeout, TimeUnit.SECONDS); // Nachschlagen abwarten
            while (true) {
                boolean idle;
                synchronized (bot.mutex) {
                    idle = bot.active.isEmpty();
                }
                if (idle) {
                    bot.side.submit(() -> { }).get((long) timeout, TimeUnit.SECONDS); // Nebenaufgaben abwarten
                    return;
                }
                assertThat(Clock.monotonic()).as("Bot wird nicht fertig").isLessThan(deadline);
                Thread.sleep(10);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static void waitFor(BooleanSupplier cond, String what) {
        double deadline = Clock.monotonic() + 5.0;
        while (!cond.getAsBoolean()) {
            assertThat(Clock.monotonic()).as(what + " nicht erreicht").isLessThan(deadline);
            Clock.sleep(0.01);
        }
    }

    static String slash(Path path) {
        return path.toString().replace("\\", "/");
    }
}
