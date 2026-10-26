package com.ewerlin.captain.bot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ewerlin.captain.util.Clock;
import com.ewerlin.captain.util.Json;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Nachhol-Zustand: Cursor pro Kanal und beantwortete Posts.
 *
 * <p>Grundlage fürs Nachholen verpasster Posts nach einem Neustart oder einer WebSocket-Lücke
 * (siehe {@link Catchup}). Datei {@code cursors.json} in {@code DATA_DIR}:
 * <pre>
 * {"version": 2, "initialized": true,
 *  "channels": {"&lt;channel_id&gt;": 1790798700148, ...},
 *  "answered": {"&lt;post_id&gt;": 1790798701234, ...}}
 * </pre>
 *
 * <h2>Cursor (Low-Watermark)</h2>
 * Gespeichert wird pro Kanal {@code min(neuester gesehener Post, ältester offener Post − 1)}.
 * <i>Offen</i> ist ein an Captain gerichteter Post, der angenommen, aber noch nicht fertig
 * beantwortet ist (Warteschlange, Nachschlagen, Streaming). Stirbt der Bot vorher, bleibt der Cursor
 * davor stehen und der Post wird beim nächsten Start nachgeholt. Zusätzlich lassen sich Kanäle
 * <b>einfrieren</b> (Nachholen fehlgeschlagen): Ihr Cursor bleibt dann, wo er war, auch wenn live
 * weitere Posts kommen – die Lücke wird beim nächsten Versuch geschlossen.
 *
 * <h2>Beantwortete Posts</h2>
 * Post-IDs fertig beantworteter Posts ({@code answered}, mit Zeitpunkt) werden {@code answeredTtl}
 * lang gespeichert und beim Nachholen übersprungen – so bleibt ein Post, den der Low-Watermark
 * erneut liefert, bei genau einer Antwort.
 *
 * <h2>Erster Start</h2>
 * Solange {@link #initialized()} falsch ist (keine Datei), wird <b>nichts</b> gespeichert; erst
 * {@link #setAll} (alle Kanäle auf „jetzt“) setzt den Marker. Ein fehlgeschlagener erster Start
 * hinterlässt also keine halbe Datei, die beim nächsten Start alle übrigen Kanäle als „neu“
 * erscheinen ließe.
 *
 * <p>Gespeichert wird atomar und gedrosselt (höchstens alle {@code interval} Sekunden,
 * Timer-Thread); {@link #close()} schreibt den Rest.
 */
public class CursorStore {

    private static final Logger log = LoggerFactory.getLogger(CursorStore.class);

    public static final int VERSION = 2;

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "captain-cursors");
        t.setDaemon(true);
        return t;
    });

    private final Path path;
    private final double interval;
    private final long answeredTtlMs;
    private final LongSupplier clock;
    private final Object lock = new Object();
    private final Object saveLock = new Object();

    private boolean initialized;
    private Map<String, Long> persisted; // zuletzt geschrieben
    private final Set<String> known; // Kanäle mit gültigem Cursor
    private final Map<String, Long> seen; // neuester gesehener Post
    private final Map<String, Map<String, Long>> open = new HashMap<>(); // Kanal → {Post-ID: create_at}
    private final Map<String, Long> frozen = new HashMap<>(); // Kanal → Obergrenze
    private boolean frozenAll;
    private Map<String, Long> answered; // Post-ID → beantwortet um (ms)
    private boolean dirty;
    private double lastSave = Clock.monotonic(); // erster gedrosselter Schreibvorgang nach interval
    private ScheduledFuture<?> timer;
    private boolean closed;

    public CursorStore(Path path, double interval, double answeredTtl, LongSupplier clock) {
        this.path = path;
        this.interval = interval;
        this.answeredTtlMs = (long) (answeredTtl * 1000);
        this.clock = clock;
        Loaded loaded = load();
        this.persisted = loaded.channels;
        this.known = new HashSet<>(loaded.channels.keySet());
        this.seen = new HashMap<>(loaded.channels);
        this.answered = loaded.answered;
        this.initialized = loaded.initialized;
    }

    public CursorStore(Path path, double answeredTtl) {
        this(path, 2.0, answeredTtl, Clock::nowMs);
    }

    private record Loaded(Map<String, Long> channels, Map<String, Long> answered, boolean initialized) {
    }

    private Loaded load() {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString();
        } catch (NoSuchFileException e) {
            return new Loaded(new HashMap<>(), new HashMap<>(), false);
        } catch (CharacterCodingException e) {
            return corrupt(e.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Cursor-Datei " + path + " nicht lesbar", e);
        }
        JsonNode raw;
        try {
            raw = Json.parse(text);
        } catch (JacksonException e) {
            return corrupt(e.getOriginalMessage());
        }
        if (raw == null || !raw.isObject() || !raw.path("channels").isObject()) {
            log.warn("Cursor-Datei {} ohne gültiges „channels“ – wie erster Start", path);
            return new Loaded(new HashMap<>(), new HashMap<>(), false);
        }
        // Version 1 kannte den Marker nicht, war aber nur nach einem Start vorhanden
        JsonNode marker = raw.get("initialized");
        boolean init = marker != null ? marker.asBoolean(false) : raw.path("version").asInt(0) == 1;
        return new Loaded(ints(raw.get("channels")), ints(raw.get("answered")), init);
    }

    private Loaded corrupt(String reason) {
        Path broken = Path.of(path + ".corrupt");
        log.warn("Cursor-Datei {} unlesbar ({}), verschoben nach {}", path, reason, broken);
        try {
            Files.move(path, broken, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Loaded(new HashMap<>(), new HashMap<>(), false);
    }

    private static Map<String, Long> ints(JsonNode raw) {
        Map<String, Long> out = new HashMap<>();
        if (raw != null && raw.isObject()) {
            for (Map.Entry<String, JsonNode> e : raw.properties()) {
                if (e.getValue().isNumber()) {
                    out.put(e.getKey(), e.getValue().asLong());
                }
            }
        }
        return out;
    }

    // -- Lesen ----------------------------------------------------------------

    public boolean initialized() {
        synchronized (lock) {
            return initialized;
        }
    }

    /** Cursor zum Nachholen ({@code null} = Kanal unbekannt). */
    public Long get(String channelId) {
        synchronized (lock) {
            return known.contains(channelId) ? effective(channelId) : null;
        }
    }

    /** Was jetzt gespeichert würde. */
    public Map<String, Long> snapshot() {
        synchronized (lock) {
            return channelsToSave();
        }
    }

    public boolean isAnswered(String postId) {
        synchronized (lock) {
            return answered.containsKey(postId);
        }
    }

    public Map<String, Long> openPosts(String channelId) {
        synchronized (lock) {
            return new HashMap<>(open.getOrDefault(channelId, Map.of()));
        }
    }

    /** Aufrufer hält {@code lock}. */
    private long effective(String channelId) {
        long value = seen.getOrDefault(channelId, 0L);
        Map<String, Long> pending = open.get(channelId);
        if (pending != null && !pending.isEmpty()) {
            value = Math.min(value, pending.values().stream().mapToLong(Long::longValue).min().getAsLong() - 1);
        }
        if (frozen.containsKey(channelId)) {
            value = Math.min(value, frozen.get(channelId));
        }
        if (frozenAll && persisted.containsKey(channelId)) {
            value = Math.min(value, persisted.get(channelId));
        }
        return Math.max(value, 0);
    }

    private Map<String, Long> channelsToSave() {
        if (frozenAll) {
            return new HashMap<>(persisted);
        }
        Map<String, Long> out = new HashMap<>();
        for (String ch : known) {
            out.put(ch, effective(ch));
        }
        return out;
    }

    // -- Schreiben ------------------------------------------------------------

    /** Neuester gesehener Post (nie zurück). */
    public void advance(String channelId, long createAt) {
        if (channelId == null || channelId.isEmpty() || createAt == 0) {
            return;
        }
        synchronized (lock) {
            if (createAt <= seen.getOrDefault(channelId, 0L)) {
                return;
            }
            seen.put(channelId, createAt);
            changed();
        }
    }

    /** Adressierter Post angenommen: Cursor bleibt davor, bis {@link #release}. */
    public void hold(String channelId, String postId, long createAt) {
        if (channelId == null || channelId.isEmpty() || postId == null || postId.isEmpty()) {
            return;
        }
        synchronized (lock) {
            open.computeIfAbsent(channelId, k -> new HashMap<>()).put(postId, createAt);
            changed();
        }
    }

    /** Post erledigt; {@code answered} = fertig beantwortet (wird gemerkt). */
    public void release(String channelId, String postId, boolean answered) {
        synchronized (lock) {
            Map<String, Long> pending = open.get(channelId);
            if (pending != null) {
                pending.remove(postId);
                if (pending.isEmpty()) {
                    open.remove(channelId);
                }
            }
            if (answered && postId != null && !postId.isEmpty()) {
                this.answered.put(postId, clock.getAsLong());
            }
            changed();
        }
    }

    public void markAnswered(String postId) {
        synchronized (lock) {
            answered.put(postId, clock.getAsLong());
            changed();
        }
    }

    /** Kanal hat ab jetzt einen gültigen Cursor ({@code start}: gesehen bis, {@code null} = unverändert). */
    public void markKnown(String channelId, Long start) {
        synchronized (lock) {
            known.add(channelId);
            if (start != null && start > seen.getOrDefault(channelId, 0L)) {
                seen.put(channelId, start);
            }
            changed();
        }
    }

    /** Cursor des Kanals bleibt höchstens {@code at}, bis {@link #unfreeze}. */
    public void freeze(String channelId, long at) {
        synchronized (lock) {
            frozen.merge(channelId, at, Math::min);
        }
    }

    public void unfreeze(String channelId) {
        synchronized (lock) {
            frozen.remove(channelId);
            changed();
        }
    }

    /** Nichts Neues an Cursorn schreiben (Kanalliste nicht ladbar). */
    public void freezeAll() {
        synchronized (lock) {
            frozenAll = true;
        }
    }

    public void unfreezeAll() {
        synchronized (lock) {
            frozenAll = false;
            changed();
        }
    }

    public Set<String> frozen() {
        synchronized (lock) {
            return new HashSet<>(frozen.keySet());
        }
    }

    /** Erster Start: alle Kanäle auf {@code createAt}, Marker setzen, sofort schreiben. */
    public void setAll(Collection<String> channelIds, long createAt) {
        synchronized (lock) {
            for (String cid : channelIds) {
                known.add(cid);
                seen.merge(cid, createAt, Math::max);
            }
            initialized = true;
            frozenAll = false;
            dirty = true;
        }
        flush();
    }

    /** Speichern einplanen (Aufrufer hält {@code lock}). */
    private void changed() {
        dirty = true;
        if (timer != null || closed || !initialized) {
            return;
        }
        double wait = Math.max(0.0, interval - (Clock.monotonic() - lastSave));
        timer = TIMER.schedule(this::timedFlush, (long) (wait * 1000), TimeUnit.MILLISECONDS);
    }

    private void timedFlush() {
        synchronized (lock) {
            timer = null;
        }
        flush();
    }

    /** Ungespeicherte Änderungen sofort schreiben (nicht vor dem ersten Start). */
    public void flush() {
        synchronized (saveLock) { // Platten-I/O ohne lock: advance() blockiert nicht
            Map<String, Object> data = new LinkedHashMap<>();
            Map<String, Long> channels;
            synchronized (lock) {
                if (!dirty || !initialized) {
                    return;
                }
                long oldest = clock.getAsLong() - answeredTtlMs;
                answered.values().removeIf(v -> v < oldest);
                channels = channelsToSave();
                data.put("answered", new TreeMap<>(answered));
                data.put("channels", new TreeMap<>(channels));
                data.put("initialized", true);
                data.put("version", VERSION);
                dirty = false;
            }
            try {
                AtomicFile.write(path, Json.PRETTY.writeValueAsString(data), ".cursors-", AtomicFile.REPLACE);
            } catch (IOException | RuntimeException e) {
                synchronized (lock) {
                    dirty = true;
                }
                log.error("Cursor-Datei {} nicht schreibbar", path, e);
                return;
            }
            synchronized (lock) {
                persisted = channels;
                lastSave = Clock.monotonic();
            }
        }
    }

    public void close() {
        ScheduledFuture<?> pending;
        synchronized (lock) {
            closed = true;
            pending = timer;
            timer = null;
        }
        if (pending != null) {
            pending.cancel(false);
        }
        flush();
    }
}
