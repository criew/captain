package com.ewerlin.captain.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ewerlin.captain.mattermost.MattermostApi;
import com.ewerlin.captain.mattermost.PostStreamer;
import com.ewerlin.captain.util.Clock;
import com.ewerlin.captain.util.Signal;

/**
 * Zwischenstände einer Antwort: Text + Tool-Status, Typing, Nachreichen.
 *
 * <p>Ein Ticker-Thread schickt bis zum ersten Text regelmäßig den Typing-Indicator und reicht
 * gedrosselte Zwischenstände nach, damit ein Post nicht auf einem veralteten Stand stehen bleibt
 * (z. B. vor einem langen Tool-Lauf).
 */
final class LiveAnswer {

    private static final Logger log = LoggerFactory.getLogger(LiveAnswer.class);

    private final MattermostApi mm;
    private final PostStreamer streamer;
    private final String channelId;
    private final String rootId;
    private final double typingInterval;
    private final double flushInterval;
    private final Object lock = new Object();
    private final Signal stop = new Signal();
    private final Thread thread;
    private String text = "";
    private String status = "";
    /** Z. B. Verspätungshinweis, steht vor jedem Zwischenstand. */
    volatile String prefix = "";

    LiveAnswer(MattermostApi mm, PostStreamer streamer, String channelId, String rootId) {
        this(mm, streamer, channelId, rootId, 4.0, 1.0);
    }

    LiveAnswer(MattermostApi mm, PostStreamer streamer, String channelId, String rootId, double typingInterval,
            double flushInterval) {
        this.mm = mm;
        this.streamer = streamer;
        this.channelId = channelId;
        this.rootId = rootId;
        this.typingInterval = typingInterval;
        this.flushInterval = flushInterval;
        this.thread = new Thread(this::tick, "captain-live");
        this.thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    void stop() {
        stop.set();
        if (thread.isAlive() && thread != Thread.currentThread()) {
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void reset() {
        synchronized (lock) {
            text = "";
            status = "";
        }
    }

    String text() {
        synchronized (lock) {
            return text;
        }
    }

    void onDelta(String value) {
        synchronized (lock) {
            text = value;
            status = ""; // Text nach einem Tool-Lauf ersetzt den Status
            push();
        }
    }

    void onStatus(String value) {
        synchronized (lock) {
            status = value;
            push();
        }
    }

    String render() {
        String body = text;
        if (!status.isEmpty()) {
            body = body.isEmpty() ? "_" + status + "_" : body + "\n\n_" + status + "_";
        }
        return prefix + body + Bot.CURSOR;
    }

    /** Aufrufer hält {@code lock}. */
    private void push() {
        if (stop.isSet()) {
            return;
        }
        if (!text.isEmpty()) {
            streamer.update(render());
        } else if (!status.isEmpty()) {
            streamer.status(render());
        }
    }

    private void tick() {
        double lastTyping = 0.0;
        while (!stop.await(flushInterval)) {
            try {
                boolean hasText;
                synchronized (lock) {
                    if (!streamer.postIds().isEmpty()) {
                        push(); // gedrosselt; unverändert → kein Request
                    }
                    hasText = !text.isEmpty();
                }
                double now = Clock.monotonic();
                if (!hasText && now - lastTyping >= typingInterval) {
                    lastTyping = now;
                    mm.typing(channelId, rootId);
                }
            } catch (RuntimeException e) { // nur Kosmetik
                log.debug("Live-Aktualisierung fehlgeschlagen: {}", e.toString());
            }
        }
    }
}
