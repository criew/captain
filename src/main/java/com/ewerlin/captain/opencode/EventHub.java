package com.ewerlin.captain.opencode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ewerlin.captain.util.Clock;
import com.ewerlin.captain.util.Json;
import com.ewerlin.captain.util.Signal;

import tools.jackson.databind.JsonNode;

/**
 * Liest {@code /api/event} (SSE) in einem Daemon-Thread und verteilt die Events nach Session.
 *
 * <p>opencode schickt ohnehin <i>alle</i> Events an jeden Abonnenten – ein Stream pro Prompt hieße
 * n-fach Parsen und n offene Verbindungen für dieselben Daten. Die Registrierung passiert synchron
 * vor dem Absenden des Prompts; der Hub ist dann bereits verbunden, es geht also nichts verloren.
 * Nach einem Abriss bekommt jeder Abonnent ein Markierungs-Event ({@link #RECONNECTED}) und gleicht
 * seinen Stand per REST ab, weil opencode keine Events nachliefert.
 */
final class EventHub {

    /** Internes Markierungs-Event nach einem Wiederverbinden. */
    static final String RECONNECTED = "_captain.reconnected";

    private static final Logger log = LoggerFactory.getLogger(EventHub.class);

    private final URI url;
    private final String authorization;
    private final HttpClient client;
    private final double readTimeout; // Heartbeats kommen alle ~10 s
    private final double connectWait;
    volatile double backoffMin = 0.5;
    volatile double backoffMax = 10.0;

    private final Object lock = new Object();
    private final Map<String, List<BlockingQueue<JsonNode>>> subs = new HashMap<>();
    private final Signal connected = new Signal();
    private final Signal stop = new Signal();
    private Thread thread;
    private volatile LineSubscriber current;
    /** Anzahl erfolgreicher Verbindungen (für Tests/Logs). */
    final AtomicInteger connects = new AtomicInteger();

    EventHub(URI url, String authorization, HttpClient client, double readTimeout, double connectWait) {
        this.url = url;
        this.authorization = authorization;
        this.client = client;
        this.readTimeout = readTimeout;
        this.connectWait = connectWait;
    }

    /** Registriert eine Warteschlange und wartet, bis der Stream steht. */
    BlockingQueue<JsonNode> subscribe(String sessionId) {
        BlockingQueue<JsonNode> q = new LinkedBlockingQueue<>();
        synchronized (lock) {
            subs.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(q);
            if (thread == null || !thread.isAlive()) {
                stop.clear();
                thread = new Thread(this::run, "opencode-events");
                thread.setDaemon(true);
                thread.start();
            }
        }
        if (!connected.await(connectWait)) {
            // Kein Abbruch: prompt() gleicht notfalls per REST ab.
            log.warn("opencode-Event-Stream nicht verbunden – fahre ohne fort");
        }
        return q;
    }

    void unsubscribe(String sessionId, BlockingQueue<JsonNode> q) {
        synchronized (lock) {
            List<BlockingQueue<JsonNode>> list = subs.get(sessionId);
            if (list != null && list.remove(q) && list.isEmpty()) {
                subs.remove(sessionId);
            }
        }
    }

    void close() {
        stop.set();
        LineSubscriber sub = current;
        if (sub != null) {
            sub.cancel();
        }
        Thread t;
        synchronized (lock) {
            t = thread;
        }
        if (t != null) {
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String sessionOf(JsonNode event) {
        JsonNode data = event.get("data");
        return data != null && data.isObject() ? Json.str(data, "sessionID") : "";
    }

    private void dispatch(JsonNode event) {
        String sid = sessionOf(event);
        if (sid.isEmpty()) {
            return;
        }
        List<BlockingQueue<JsonNode>> targets;
        synchronized (lock) {
            targets = new ArrayList<>(subs.getOrDefault(sid, List.of()));
        }
        targets.forEach(q -> q.add(event));
    }

    private void broadcast(JsonNode event) {
        List<BlockingQueue<JsonNode>> targets = new ArrayList<>();
        synchronized (lock) {
            subs.values().forEach(targets::addAll);
        }
        targets.forEach(q -> q.add(event));
    }

    private void run() {
        double delay = backoffMin;
        while (!stop.isSet()) {
            LineSubscriber sub = new LineSubscriber();
            current = sub;
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder(url).GET().header("Accept", "text/event-stream");
                if (authorization != null) {
                    req.header("Authorization", authorization);
                }
                CompletableFuture<HttpResponse<Void>> response = client.sendAsync(req.build(), info -> {
                    sub.status = info.statusCode();
                    return info.statusCode() == 200
                            ? HttpResponse.BodySubscribers.fromLineSubscriber(sub)
                            : HttpResponse.BodySubscribers.replacing(null);
                });
                boolean first = true;
                while (!stop.isSet()) {
                    String line = sub.lines.poll(250, TimeUnit.MILLISECONDS);
                    if (line == null) {
                        if (sub.done.isDone() && sub.lines.isEmpty()) {
                            break;
                        }
                        if (response.isCompletedExceptionally()) {
                            try {
                                response.join();
                            } catch (CompletionException e) {
                                throw e.getCause() == null ? e : e.getCause();
                            }
                        }
                        if (response.isDone() && sub.status != 200) {
                            throw new OpencodeException("Event-Stream: HTTP " + sub.status);
                        }
                        if (Clock.monotonic() - sub.lastRead > readTimeout) {
                            throw new TimeoutException("keine Daten seit " + (int) readTimeout + " s");
                        }
                        continue;
                    }
                    JsonNode event = OpencodeClient.parseSseLine(line);
                    if (event == null) {
                        continue;
                    }
                    if (first) {
                        first = false;
                        int n = connects.incrementAndGet();
                        connected.set();
                        delay = backoffMin;
                        if (n > 1) {
                            log.info("opencode-Event-Stream wieder verbunden");
                            broadcast(Json.object().put("type", RECONNECTED).set("data", Json.object()));
                        }
                    }
                    dispatch(event);
                }
                if (stop.isSet()) {
                    return;
                }
                Throwable error = sub.error;
                if (error != null) {
                    throw error;
                }
                log.info("opencode-Event-Stream beendet – verbinde neu");
            } catch (Throwable e) { // Reader darf nie sterben
                if (stop.isSet()) {
                    return;
                }
                log.warn("opencode-Event-Stream unterbrochen: {}", e.toString());
            } finally {
                sub.cancel();
                current = null;
                connected.clear();
            }
            if (stop.await(delay)) {
                return;
            }
            delay = Math.min(delay * 2, backoffMax);
        }
    }

    /** Nimmt Zeilen des Streams entgegen; abbrechbar von außen (Stillstand, Beenden). */
    private static final class LineSubscriber implements Flow.Subscriber<String> {
        final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        final CompletableFuture<Void> done = new CompletableFuture<>();
        volatile int status;
        volatile double lastRead = Clock.monotonic();
        volatile Throwable error;
        private volatile Flow.Subscription subscription;
        private volatile boolean cancelled;

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            if (cancelled) {
                s.cancel();
            } else {
                s.request(Long.MAX_VALUE);
            }
        }

        @Override
        public void onNext(String line) {
            lastRead = Clock.monotonic();
            lines.add(line);
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            done.complete(null);
        }

        @Override
        public void onComplete() {
            done.complete(null);
        }

        void cancel() {
            cancelled = true;
            Flow.Subscription s = subscription;
            if (s != null) {
                s.cancel();
            }
            done.complete(null);
        }
    }
}
