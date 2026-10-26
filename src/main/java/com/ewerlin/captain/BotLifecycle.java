package com.ewerlin.captain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.ewerlin.captain.bot.Bot;

/**
 * Startet den Bot in einem eigenen (nicht-Daemon-)Thread und beendet ihn geordnet.
 *
 * <p>SIGTERM/SIGINT schließen den Spring-Kontext (Shutdown-Hook); {@link #stop()} ruft dann
 * {@link Bot#stop()} – Cursor sichern, laufende Antworten abbrechen – und wartet, bis der
 * Bot-Thread fertig ist: Laufende Antworten bekommen bis zu {@link Bot#STOP_GRACE} Sekunden für ihre
 * „unterbrochen“-Notiz ({@code stop_grace_period: 20s} in Compose).
 */
public class BotLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BotLifecycle.class);

    private final Bot bot;
    private volatile Thread thread;

    public BotLifecycle(Bot bot) {
        this.bot = bot;
    }

    @Override
    public void start() {
        Thread t = new Thread(() -> {
            try {
                bot.run();
            } catch (RuntimeException e) {
                log.error("Bot beendet sich mit Fehler", e);
                System.exit(1);
            }
        }, "captain-main");
        t.setDaemon(false);
        thread = t;
        t.start();
    }

    @Override
    public void stop() {
        Thread t = thread;
        if (t == null) {
            return;
        }
        log.info("Beende Captain");
        bot.stop();
        try {
            t.join((long) ((Bot.STOP_GRACE + 5) * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        thread = null;
    }

    @Override
    public boolean isRunning() {
        Thread t = thread;
        return t != null && t.isAlive();
    }
}
