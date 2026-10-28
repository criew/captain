package com.ewerlin.captain.bot;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ewerlin.captain.api.AgentRejectedException;
import com.ewerlin.captain.api.Conversations;
import com.ewerlin.captain.config.CaptainConfig;
import com.ewerlin.captain.mattermost.AttachmentTooLargeException;
import com.ewerlin.captain.mattermost.MattermostApi;
import com.ewerlin.captain.mattermost.Post;
import com.ewerlin.captain.mattermost.PostStreamer;
import com.ewerlin.captain.opencode.ModelInfo;
import com.ewerlin.captain.opencode.OpencodeApi;
import com.ewerlin.captain.opencode.OpencodeException;
import com.ewerlin.captain.opencode.PromptInterruptedException;
import com.ewerlin.captain.opencode.PromptOptions;
import com.ewerlin.captain.opencode.SessionGoneException;
import com.ewerlin.captain.opencode.SessionPermissions;
import com.ewerlin.captain.util.Clock;
import com.ewerlin.captain.util.Json;
import com.ewerlin.captain.util.Signal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Captain-Bot: Mattermost-Posts → opencode-Sessions → Antwort als Live-Post.
 *
 * <h2>Ablauf</h2>
 * {@code MattermostApi.listen} ruft {@link #onPost} im WebSocket-Thread auf. Dort wird nur gefiltert
 * (Bots/Webhooks, Duplikate, nicht an Captain gerichtet – siehe {@link Routing}) und der
 * Post an eine <b>Spur</b> pro Session-Key übergeben; die eigentliche Arbeit läuft in einem
 * Thread-Pool.
 *
 * <p>In Kanälen und Gruppen-DMs ist jede Unterhaltung ein Thread ({@code th:<root>}): Eine
 * Top-Level-Erwähnung öffnet einen Thread unter dem Post; dessen neue Session bekommt die
 * Kanal-Posts seit Captains letzter Beteiligung als Vorgeschichte.
 *
 * <p>Pro Session-Key gibt es höchstens einen aktiven Abarbeiter (zusätzlich abgesichert über
 * {@link KeyLocks}), denn opencode stellt einen zweiten Prompt während einer laufenden Ausführung
 * per {@code steer} zu und würde ihn in die laufende Antwort hineinlenken. Verschiedene Keys laufen
 * parallel.
 *
 * <h2>Warteschlange und Bündelung</h2>
 * Nachrichten, die eintreffen, während eine Antwort läuft, gehen nicht verloren: Sie bekommen eine
 * ⏳-Reaktion und werden nach der laufenden Antwort abgearbeitet – und zwar <b>gebündelt</b> in
 * <i>einem</i> Prompt. Begründung: Das lokale Modell braucht pro Ausführung spürbar Zeit; in einem
 * Gruppenchat mit mehreren Beiträgen wäre eine Antwort pro Beitrag langsam und inhaltlich überholt,
 * sobald sie erscheint. Ein Prompt mit allen Beiträgen ({@code Name: Text} je Zeile) liefert eine
 * zusammenhängende Antwort auf den aktuellen Stand. {@code !neu} bleibt in der Reihenfolge (trennt
 * Bündel), die übrigen Befehle wirken sofort.
 *
 * <h2>Nachholen</h2>
 * Nach dem Start und nach jedem Reconnect ohne Replay holt {@link #catchUp} verpasste Posts per REST
 * nach (Cursor pro Kanal, {@link CursorStore}); Live-Posts werden solange gepuffert. Details in
 * {@link Catchup}.
 *
 * <h2>REST-Eingabekanal</h2>
 * {@link #ask} beantwortet eine Nachricht ohne Mattermost: Die Conversation-ID des Aufrufers wird
 * zum Session-Key {@code rest:<id>}, gleiche ID = gleiche opencode-Session. Anfragen derselben ID
 * laufen nacheinander (fairer {@link KeyLocks}-Lock), siehe {@link com.ewerlin.captain.api.ApiServer}.
 *
 * <h2>Aufräumen</h2>
 * {@link #sweep} löscht stündlich Sessions, die länger als {@code SESSION_MAX_IDLE} unbenutzt sind
 * (opencode-Session, Verzeichnis, Store-Eintrag; Modell bleibt). Die Cursor ({@link CursorStore})
 * bleiben unberührt. Kommt danach eine neue Nachricht, entsteht eine neue Session: im Thread mit dem
 * Thread-Verlauf, in der DM mit den letzten DM-Posts als Kontext, im REST-Kanal ohne Verlauf.
 */
public class Bot implements Conversations {

    private static final Logger log = LoggerFactory.getLogger(Bot.class);

    /** Hängt an jedem Zwischenstand; der Endstand hat ihn nie. */
    public static final String CURSOR = " ▌";
    public static final String QUEUED_EMOJI = "hourglass_flowing_sand";
    /** Sekunden ohne Event, bevor abgebrochen wird. */
    public static final double PROMPT_IDLE_TIMEOUT = 600.0;
    /** So viele Thread-Posts gehen in eine neue Thread-Session … */
    static final int THREAD_CONTEXT_POSTS = 20;
    /** … pro Post höchstens so viele Zeichen. */
    static final int THREAD_CONTEXT_CHARS = 2000;
    /** Janitor: erster Lauf nach so vielen Sekunden, danach in diesem Abstand. */
    static final long JANITOR_DELAY = 60;
    static final long JANITOR_INTERVAL = 3600;
    /** Antwort auf {@code !neu}; ältere Posts gehören nicht mehr in den Kontext einer neuen Session. */
    static final String NEW_NOTE = "🆕 Neue Unterhaltung – der bisherige Verlauf ist vergessen.";
    /** So viele nachgeholte Post-IDs für den Verspätungshinweis merken. */
    static final int LATE_MAX = 1000;
    /** Dedupe live/nachgeholt (dazu die gespeicherten beantworteten IDs). */
    static final int SEEN_MAX = 20000;
    /** Sekunden, die laufende Antworten beim Beenden für ihre Notiz bekommen. */
    public static final double STOP_GRACE = 10.0;
    /** Sekunden für alle opencode-Abbrüche beim Beenden zusammen. */
    static final double ABORT_TIMEOUT = 3.0;
    /** Mattermost-Nutzer so lange (Sekunden) und so viele zwischenspeichern. */
    static final double USER_TTL = 600.0;
    static final int USERS_MAX = 2000;
    /** Systemanweisung {@code captain-kanal}: wie im jeweiligen Eingabekanal antworten. */
    static final String MATTERMOST_NOTE = """
            # Kanal: Mattermost
            Du chattest über Mattermost mit Menschen.
            - Formatiere für Mattermost-Markdown: `**fett**`, `_kursiv_`, `` `Code` ``, Codeblöcke mit \
            ```` ``` ````, Listen mit `-`, Tabellen nur wenn sie wirklich helfen. Keine HTML-Tags.
            - In Gruppenchats kommen Nachrichten als `Name: Text`. Sprich Personen bei Bedarf mit Namen an.
            - Rückfragen stellst du bei Bedarf einfach als normale Chat-Antwort.""";
    static final String API_NOTE = """
            # Kanal: REST-Schnittstelle
            Die Nachrichten kommen von einer Automatisierung (z. B. einem Webhook), nicht aus einem Chat; \
            deine Antwort verarbeitet sie weiter (z. B. als Kommentar in einem Merge Request).
            - Antworte direkt mit dem Ergebnis, ohne Rückfragen, Begrüßung oder Chat-Floskeln.
            - Formatiere als gewöhnliches Markdown (CommonMark/GitLab), keine HTML-Tags.
            - Kommt eine Nachricht als `Name: Text`, stammt sie von dieser Person bzw. diesem System.""";

    static final String HELP = """
            **Captain** – ich reiche Nachrichten an opencode weiter und antworte hier.

            **Wann ich antworte**
            - Direktnachricht: auf jede Nachricht, direkt im Chat.
            - Kanal oder Gruppe: nur wenn du mich mit `@{name}` erwähnst – ich antworte \
            im Thread unter deinem Post und kenne die Kanal-Nachrichten seit meiner letzten Antwort dort.
            - Thread, in dem ich schon geschrieben habe: auf jede Antwort, ohne Erwähnung.

            **Befehle** (in Kanälen und Gruppen oben mit Erwähnung, z. B. `@{name} !help`)
            - `!neu` – neue Unterhaltung (Verlauf vergessen, Modell bleibt); im Thread nur für den \
            Thread – in Kanälen beginnt jede Erwähnung oben ohnehin eine neue
            - `!modell` – verfügbare Modelle anzeigen
            - `!modell <provider/modell> [variante]` – Modell setzen (in Kanälen oben: für den \
            ganzen Kanal, im Thread: nur für den Thread)
            - `!modell standard` – zurück zum Standardmodell
            - `!stopp` – laufende Antwort abbrechen, Wartende verwerfen (in Kanälen oben: alle im Kanal)
            - `!help` / `!hilfe` – diese Hilfe

            Nachrichten, die während einer Antwort kommen (⏳), beantworte ich danach gesammelt.""";

    static final Map<String, String> COMMANDS = Map.of(
            "!neu", "neu", "!new", "neu", "!reset", "neu",
            "!hilfe", "hilfe", "!help", "hilfe",
            "!modell", "modell", "!model", "modell",
            "!stopp", "stopp", "!stop", "stopp");
    /** Wirken erst nach allem, was vorher kam. */
    static final Set<String> QUEUED_COMMANDS = Set.of("neu");

    /** Befehl und Argument, z. B. {@code ("modell", "a b")}. */
    public record Command(String name, String arg) {
    }

    /** Modell/Variante und woher sie kommen: {@code hier}, {@code kanal} oder {@code standard}. */
    record Settings(String model, String variant, String source) {
    }

    /** In Tests austauschbar. */
    volatile CaptainConfig cfg;
    final MattermostApi mm;
    final OpencodeApi oc;
    final SessionStore store;
    final CursorStore cursors;
    final double promptTimeout;
    private final ThreadPoolExecutor pool;
    /** Nebenaufgaben (Reaktionen); in Tests durch einen FIFO-Executor ersetzbar. */
    ExecutorService side;
    /** Sofort-Befehle (!modell, !stopp, …) – eigener Pool, damit ein langsamer Befehl keine Reaktionen aufhält. */
    ExecutorService commands;
    /** Nachschlagen per REST außerhalb des WebSocket-Threads, in Reihenfolge. */
    final ExecutorService lookup;
    /** Janitor ({@link #sweep}), läuft ab {@link #run}. */
    private final ScheduledThreadPoolExecutor janitor;
    private final KeyLocks locks = new KeyLocks();

    final Object mutex = new Object();
    private final Map<String, Deque<Post>> lanes = new HashMap<>();
    final Set<String> active = new HashSet<>(); // Keys mit laufendem Abarbeiter
    private final Set<String> noInstructions = new HashSet<>(); // Sessions ohne Systemanweisungen (API-Fehler)
    private final Map<String, String> running = new ConcurrentHashMap<>(); // Key → Session der laufenden Antwort
    private final Set<String> inflight = new HashSet<>(); // Keys, deren Bündel gerade beantwortet wird
    private final Set<String> cancelled = new HashSet<>(); // !stopp, bevor der Prompt lief (Anhänge, Session)
    private record CachedUser(JsonNode user, double expires) {
    }
    private final LinkedHashMap<String, CachedUser> users = new LinkedHashMap<>(); // Nutzer-Cache (LRU)
    private final Set<String> queued = new HashSet<>(); // Post-IDs mit ⏳-Reaktion
    private final LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
    private final Map<String, String> channels = new HashMap<>(); // aktiver Key → Kanal (für !stopp im Kanal)
    // Thread-Beteiligung: positiv (LRU) und negativ (LRU mit Ablaufzeit)
    final LinkedHashMap<String, Boolean> threads = new LinkedHashMap<>();
    private final LinkedHashMap<String, Double> noThread = new LinkedHashMap<>();
    private final Map<String, Integer> pending = new HashMap<>(); // Root → Posts, die aufs Nachschlagen warten
    final Signal stopping = new Signal();
    // Nachholen: Live-Posts puffern, solange es läuft (siehe onHello)
    private final Object bufLock = new Object();
    private boolean buffering;
    private boolean catchupAgain;
    private final Deque<Post> buffer = new ArrayDeque<>();
    private final LinkedHashMap<String, Boolean> late = new LinkedHashMap<>(); // nachgeholte Post-IDs
    /** Nachholen samt Puffer (erstmals) fertig. */
    public final Signal catchupDone = new Signal();
    /** Letztes Nachholen vollständig. */
    public final Signal catchupOk = new Signal();
    private final Signal wakeup = new Signal();
    private Thread worker;

    // in Tests austauschbar
    double[] catchupBackoff = {1.0, 60.0};
    LongSupplier clock = Clock::nowMs;
    long startedMs;
    Supplier<String> newSessionId = SessionKeys::newSessionId;
    int threadsMax = 5000; // so viele Thread-Roots mit Captain-Beteiligung merken
    int noThreadMax = 1000; // Negativ-Cache „Captain nicht im Thread“ …
    double noThreadTtl = 300.0; // … und wie lange ein Eintrag gilt (Sekunden)

    public Bot(CaptainConfig cfg, MattermostApi mm, OpencodeApi oc, SessionStore store) {
        this(cfg, mm, oc, store, null, 8, PROMPT_IDLE_TIMEOUT);
    }

    public Bot(CaptainConfig cfg, MattermostApi mm, OpencodeApi oc, SessionStore store, CursorStore cursors,
            int workers, double promptTimeout) {
        this.cfg = cfg;
        this.mm = mm;
        this.oc = oc;
        this.store = store;
        this.cursors = cursors != null ? cursors
                : new CursorStore(Path.of(cfg.dataDir(), "cursors.json"), cfg.catchupMaxAge().toMillis() / 1000.0);
        this.promptTimeout = promptTimeout;
        this.pool = executor(workers, "captain-session");
        this.side = executor(2, "captain-side");
        this.commands = executor(4, "captain-command");
        this.lookup = executor(1, "captain-lookup");
        this.janitor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "captain-janitor");
            t.setDaemon(true);
            return t;
        });
        this.startedMs = clock.getAsLong();
    }

    private static ThreadPoolExecutor executor(int threads, String name) {
        AtomicInteger n = new AtomicInteger();
        return new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    // -- Hilfen ---------------------------------------------------------------

    /** Bytes → MB mit höchstens einer Nachkommastelle, deutsches Komma. */
    static String mb(long size) {
        String text = String.format(Locale.ROOT, "%.1f", size / (1024.0 * 1024.0));
        if (text.endsWith(".0")) {
            text = text.substring(0, text.length() - 2);
        }
        return text.replace(".", ",");
    }

    /** {@code "!modell a b"} → {@code ("modell", "a b")}; {@code null} für normalen Text. */
    public static Command parseCommand(String text) {
        if (!text.startsWith("!")) {
            return null;
        }
        String stripped = text.strip();
        int space = stripped.indexOf(' ');
        String word = space < 0 ? stripped : stripped.substring(0, space);
        String rest = space < 0 ? "" : stripped.substring(space + 1);
        String name = COMMANDS.get(word.toLowerCase(Locale.ROOT));
        return name == null ? null : new Command(name, rest.strip());
    }

    /** Anzeigename wie in Mattermost: Spitzname, sonst Vor-/Nachname, sonst Username. */
    public static String displayName(JsonNode user) {
        List<String> parts = new ArrayList<>();
        for (String key : List.of("first_name", "last_name")) {
            if (Json.truthy(user, key)) {
                parts.add(Json.str(user, key));
            }
        }
        String full = String.join(" ", parts);
        if (Json.truthy(user, "nickname")) {
            return Json.str(user, "nickname");
        }
        if (!full.isEmpty()) {
            return full;
        }
        return Json.truthy(user, "username") ? Json.str(user, "username") : "?";
    }

    private void submit(ExecutorService executor, String what, Runnable task) {
        try {
            executor.execute(() -> guarded(what, task));
        } catch (RejectedExecutionException e) {
            if (!stopping.isSet()) {
                log.warn("{} nicht mehr ausführbar: {}", what, e.toString());
            }
        }
    }

    private static void guarded(String what, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("Fehler in {}", what, e);
        }
    }

    // -- Lebenszyklus --------------------------------------------------------

    /**
     * Wartet, bis Mattermost den Bot-Token akzeptiert; liefert den Bot-User. {@code null}, falls
     * vorher {@link #stop} aufgerufen wurde.
     */
    public JsonNode waitForMattermost(Double timeout) {
        double deadline = timeout == null ? Double.MAX_VALUE : Clock.monotonic() + timeout;
        double delay = 1.0;
        while (true) {
            try {
                return mm.me();
            } catch (RuntimeException e) { // Server startet evtl. noch
                if (Clock.monotonic() >= deadline) {
                    throw e;
                }
                log.info("Mattermost noch nicht erreichbar ({}), neuer Versuch in {} s", e.getMessage(), Math.round(delay));
                if (stopping.await(delay)) {
                    return null;
                }
                delay = Math.min(delay * 2, 15.0);
            }
        }
    }

    /** Blockiert bis {@link #stop}. */
    public void run() {
        JsonNode me = waitForMattermost(null);
        if (me == null) {
            return;
        }
        try {
            Files.createDirectories(Path.of(cfg.sessionsDir()));
        } catch (IOException e) {
            throw new IllegalStateException("SESSIONS_DIR " + cfg.sessionsDir() + " nicht anlegbar", e);
        }
        log.info("Captain läuft als @{} ({})", Json.str(me, "username"), Json.str(me, "id"));
        if (!cfg.sessionMaxIdle().isZero()) {
            try {
                janitor.scheduleWithFixedDelay(() -> guarded("sweep", this::sweep), JANITOR_DELAY, JANITOR_INTERVAL,
                        TimeUnit.SECONDS);
            } catch (RejectedExecutionException e) {
                // schon beendet
            }
        }
        mm.listen(this::onWsPost, this::onHello);
        waitQuiet(STOP_GRACE);
    }

    /**
     * Beenden: Cursor sichern, laufende Antworten abbrechen.
     *
     * <p>Offene Posts (Warteschlange, Nachschlagen, laufende Antwort) gelten danach nicht als
     * beantwortet – der Cursor bleibt davor, der nächste Start holt sie nach. Abgebrochene Antworten
     * enden mit {@link Catchup#INTERRUPTED_NOTE}.
     */
    public void stop() {
        stopping.set();
        wakeup.set();
        cursors.close(); // vor allem Weiteren: späteres „erledigt“ zählt nicht
        mm.close();
        abortAll(new ArrayList<>(running.values()));
        janitor.shutdownNow();
        for (ExecutorService executor : List.of(pool, side, commands, lookup)) {
            if (executor instanceof ThreadPoolExecutor tpe) {
                tpe.getQueue().clear(); // Wartendes verwerfen, Laufendes zu Ende bringen
            }
            executor.shutdown();
        }
    }

    /** Nach {@link #stop}: wartet höchstens {@code timeout} s, bis alle Pools leer sind (Tests). */
    void awaitStopped(double timeout) {
        long deadline = System.nanoTime() + (long) (timeout * 1e9);
        for (ExecutorService executor : List.of(pool, side, commands, lookup, janitor)) {
            try {
                executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Laufende opencode-Ausführungen parallel abbrechen, höchstens {@link #ABORT_TIMEOUT}. */
    private void abortAll(List<String> sessions) {
        List<Thread> threads = new ArrayList<>();
        for (String sid : sessions) {
            Thread t = new Thread(() -> {
                try {
                    oc.abort(sid);
                } catch (RuntimeException e) {
                    log.debug("Abbruch von {} beim Beenden fehlgeschlagen: {}", sid, e.toString());
                }
            }, "captain-abort");
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
        double deadline = Clock.monotonic() + ABORT_TIMEOUT;
        for (Thread t : threads) {
            try {
                t.join(Math.max(1, (long) ((deadline - Clock.monotonic()) * 1000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // -- Nachholen -----------------------------------------------------------

    /**
     * Neue WebSocket-Verbindung ohne Replay (WebSocket-Thread).
     *
     * <p>Ab jetzt werden Live-Posts gepuffert; der Nachhol-Thread holt die Lücke per REST und
     * arbeitet danach den Puffer ab. Kommt währenddessen ein weiteres {@code hello}, wird danach
     * noch einmal nachgeholt.
     */
    void onHello() {
        synchronized (bufLock) {
            catchupAgain = true;
            buffering = true;
            if (worker == null) {
                worker = new Thread(this::catchupWorker, "captain-catchup");
                worker.setDaemon(true);
                worker.start();
            }
        }
        wakeup.set();
    }

    void onWsPost(Post post) {
        synchronized (bufLock) {
            if (buffering) {
                buffer.add(post);
                return;
            }
        }
        onPost(post);
    }

    /**
     * Nachholen auf {@code hello}; nach einem Fehlschlag Wiederholung mit Backoff.
     *
     * <p>Bei der Wiederholung (ohne neues {@code hello}) wird nicht gepuffert: Die betroffenen Cursor
     * sind eingefroren, Live-Posts laufen normal weiter.
     */
    private void catchupWorker() {
        double first = catchupBackoff[0];
        double most = catchupBackoff[1];
        double delay = first;
        boolean retry = false;
        while (true) {
            boolean woke = wakeup.await(retry ? delay : -1);
            if (stopping.isSet()) {
                return;
            }
            wakeup.clear();
            if (retry && !woke) {
                log.info("Nachholen: neuer Versuch");
                synchronized (bufLock) {
                    catchupAgain = true;
                }
            }
            if (catchupPass()) {
                retry = false;
                delay = first;
                catchupOk.set();
            } else {
                if (retry) {
                    delay = Math.min(delay * 2, most);
                }
                retry = true;
                catchupOk.clear();
                log.warn("Nachholen unvollständig – neuer Versuch in {} s", String.format(Locale.ROOT, "%.1f", delay));
            }
        }
    }

    /** Nachholen, dann Puffer abarbeiten; {@code true} = vollständig nachgeholt. */
    private boolean catchupPass() {
        boolean ok = true;
        int drained = 0;
        while (true) {
            Post post;
            int waiting;
            synchronized (bufLock) {
                if (stopping.isSet()) {
                    buffering = false;
                    buffer.clear();
                    return ok;
                }
                if (catchupAgain) {
                    catchupAgain = false;
                    post = null;
                } else if (!buffer.isEmpty()) {
                    post = buffer.poll();
                } else {
                    buffering = false; // ab jetzt wieder direkt
                    if (drained > 0) {
                        log.info("Nachholen: {} gepufferte Live-Post(s) abgearbeitet", drained);
                    }
                    catchupDone.set();
                    return ok;
                }
                waiting = buffer.size();
            }
            if (post == null) {
                if (waiting > 0) {
                    log.info("Nachholen startet, {} Live-Post(s) gepuffert", waiting);
                }
                try {
                    ok = catchUp();
                } catch (RuntimeException e) {
                    log.error("Nachholen fehlgeschlagen", e);
                    ok = false;
                }
            } else {
                drained++;
                Post p = post;
                guarded("onPost", () -> onPost(p));
            }
        }
    }

    /**
     * Holt verpasste Posts aller Kanäle nach; {@code true} = alle Kanäle geschafft.
     *
     * <p>Blockiert (REST); läuft im Nachhol-Thread, nie im WebSocket-Thread. Kanäle, bei denen etwas
     * schiefgeht, bleiben eingefroren (ihr Cursor wandert nicht über die Lücke) und werden beim
     * nächsten Versuch geholt.
     */
    public boolean catchUp() {
        long now = clock.getAsLong();
        List<JsonNode> channelList;
        try {
            channelList = mm.myChannels();
        } catch (RuntimeException e) {
            log.warn("Nachholen: Kanalliste nicht ladbar ({}) – alle Cursor eingefroren", e.toString());
            cursors.freezeAll();
            return false;
        }
        String botId = Json.str(mm.me(), "id");
        if (!cursors.initialized()) {
            cursors.setAll(channelList.stream().map(c -> Json.str(c, "id")).toList(), now);
            log.info("Erster Start: Cursor für {} Kanäle auf jetzt – nichts nachgeholt", channelList.size());
            return true;
        }
        long maxAge = cfg.catchupMaxAge().toMillis();
        long oldest = now - maxAge;
        Map<String, String> types = new HashMap<>();
        channelList.forEach(c -> types.put(Json.str(c, "id"), Json.str(c, "type")));
        // zuerst alle Cursor festhalten, dann erst das globale Einfrieren lösen
        Map<String, Long> cursorOf = new HashMap<>();
        for (JsonNode c : channelList) {
            String cid = Json.str(c, "id");
            Long cursor = cursors.get(cid);
            cursorOf.put(cid, cursor);
            if (cursor != null) {
                cursors.freeze(cid, cursor);
            }
        }
        cursors.unfreezeAll();
        Map<String, List<JsonNode>> fetched = new LinkedHashMap<>();
        Map<String, Long> starts = new HashMap<>();
        boolean ok = true;
        int skipped = 0;
        for (JsonNode ch : channelList) {
            if (stopping.isSet()) {
                return false;
            }
            String cid = Json.str(ch, "id");
            Long cursor = cursorOf.get(cid);
            JsonNode last = ch.get("last_post_at");
            if (cursor != null && last != null && last.isIntegralNumber() && last.asLong() <= Math.max(cursor, oldest)) {
                skipped++; // nichts Neues seit dem Cursor
                cursors.unfreeze(cid);
                continue;
            }
            List<JsonNode> posts;
            long start;
            try {
                if (cursor == null) { // neu beigetreten (bzw. neue DM)
                    posts = mm.getPostsAfter(cid, oldest);
                    start = Catchup.startForNew(ch, posts, botId, oldest);
                    long from = start;
                    posts = posts.stream().filter(p -> Json.lng(p, "create_at") > from).toList();
                    log.info("Kanal {} ohne Cursor: nachholen ab {} (Beitritt bzw. Höchstalter)", cid, start);
                } else {
                    Catchup.FetchAfter after = Catchup.fetchAfter(cursor, now, maxAge);
                    start = after.start();
                    if (after.tooOld()) {
                        log.warn("Kanal {}: Cursor {} h alt – Posts älter als {} h werden übersprungen", cid,
                                String.format(Locale.ROOT, "%.1f", (now - cursor) / 3.6e6),
                                String.format(Locale.ROOT, "%.1f", maxAge / 3.6e6));
                    }
                    posts = mm.getPostsAfter(cid, start);
                }
            } catch (RuntimeException e) { // andere Kanäle trotzdem nachholen
                log.warn("Kanal {} nicht nachholbar ({}) – Cursor eingefroren", cid, e.toString());
                ok = false;
                continue;
            }
            fetched.put(cid, posts);
            starts.put(cid, start);
        }
        List<JsonNode> everything = fetched.values().stream().flatMap(List::stream).toList();
        Set<String> done = Catchup.answeredIn(everything, botId);
        int passed = 0;
        List<JsonNode> ordered = Catchup.orderMissed(everything);
        for (JsonNode raw : ordered) {
            if (stopping.isSet()) {
                return false;
            }
            String cid = Json.str(raw, "channel_id");
            Post post = Post.fromJson(raw, types.getOrDefault(cid, ""), "", botId);
            if (post != null && !post.fromBot() && unseen(post.id()) && !cursors.isAnswered(post.id())
                    && addressed(post)) {
                if (done.contains(post.id())) {
                    log.info("Post {} ist schon beantwortet – übersprungen", post.id());
                    cursors.markAnswered(post.id());
                } else {
                    post = post.withSenderName(Json.str(user(post.userId()), "username"));
                    markLate(post.id());
                    onPost(post); // hält den Post offen, bis er beantwortet ist
                    passed++;
                }
            }
            cursors.advance(cid, Json.lng(raw, "create_at"));
        }
        for (String cid : fetched.keySet()) {
            cursors.markKnown(cid, starts.get(cid));
            cursors.unfreeze(cid);
        }
        closePartials(everything, botId);
        log.info("Nachgeholt: {} Kanäle ohne Neues, {} verpasste Post(s) geprüft, {} an Captain gerichtet{}",
                skipped, ordered.size(), passed, ok ? "" : " – unvollständig");
        return ok;
    }

    /** Zwischenstände ({@code ▌}) aus der Zeit vor dem Start als abgebrochen markieren. */
    private void closePartials(List<JsonNode> posts, String botId) {
        for (JsonNode p : Catchup.stalePartials(posts, botId, startedMs)) {
            String text = Json.str(p, "message").stripTrailing();
            String mark = CURSOR.strip();
            if (text.endsWith(mark)) {
                text = text.substring(0, text.length() - mark.length());
            }
            text = text.stripTrailing();
            text = text.isEmpty() ? Catchup.INTERRUPTED_NOTE : text + "\n\n" + Catchup.INTERRUPTED_NOTE;
            try {
                mm.updatePost(Json.str(p, "id"), text, null);
            } catch (RuntimeException e) { // nur Kosmetik
                log.debug("Zwischenstand {} nicht korrigierbar: {}", Json.str(p, "id"), e.toString());
            }
        }
    }

    /** Wartet (höchstens {@code timeout} s), bis keine Antwort mehr läuft. */
    public void waitQuiet(double timeout) {
        double deadline = Clock.monotonic() + timeout;
        while (Clock.monotonic() < deadline) {
            synchronized (mutex) {
                if (active.isEmpty()) {
                    return;
                }
            }
            Clock.sleep(0.05);
        }
    }

    private boolean unseen(String postId) {
        synchronized (mutex) {
            return !seen.containsKey(postId);
        }
    }

    private void markLate(String postId) {
        synchronized (mutex) {
            late.put(postId, true);
            trim(late, LATE_MAX);
        }
    }

    /** Ist einer der Posts nachgeholt? (Markierung wird verbraucht) */
    private boolean takeLate(List<Post> posts) {
        boolean any = false;
        synchronized (mutex) {
            for (Post p : posts) {
                any |= late.remove(p.id()) != null;
            }
        }
        return any;
    }

    private static void trim(LinkedHashMap<String, ?> map, int max) {
        Iterator<String> it = map.keySet().iterator();
        while (map.size() > max && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    /** Adressierter Post angenommen: Cursor bleibt davor, bis {@link #done}. */
    private void hold(Post post) {
        cursors.hold(post.channelId(), post.id(), post.createAt());
    }

    /** Post erledigt. Beim Beenden nicht: dann holt der nächste Start ihn nach. */
    private void done(Post post, boolean answered) {
        if (stopping.isSet()) {
            return;
        }
        cursors.release(post.channelId(), post.id(), answered);
    }

    // -- Eingang (WebSocket-Thread: nur filtern und übergeben) ---------------

    public void onPost(Post post) {
        try {
            handlePost(post);
        } finally {
            // erst nach dem Festhalten (hold) – sonst könnte der Cursor kurz über einen
            // angenommenen, noch unbeantworteten Post gespeichert werden
            cursors.advance(post.channelId(), post.createAt());
        }
    }

    private void handlePost(Post post) {
        if (post.fromBot()) {
            return;
        }
        if (cursors.isAnswered(post.id()) || !firstTime(post.id())) {
            log.debug("Post {} doppelt zugestellt – ignoriert", post.id());
            return;
        }
        if (post.message().strip().isEmpty() && post.fileIds().isEmpty()) {
            return;
        }
        String root = post.channelType().equals("D") ? "" : post.rootId();
        if (!root.isEmpty()) {
            Boolean known;
            boolean later;
            synchronized (mutex) {
                known = knownThread(root);
                // Beteiligung unbekannt (oder schon ein Post in der Warteschlange fürs Nachschlagen
                // → Reihenfolge wahren): im Lookup-Worker entscheiden
                later = pending.getOrDefault(root, 0) > 0 || (known == null && !shouldAnswer(post, false));
                if (later) {
                    pending.merge(root, 1, Integer::sum);
                }
            }
            if (later) {
                hold(post); // evtl. adressiert – erst der Lookup entscheidet
                submit(lookup, "routeLater", () -> routeLater(post));
                return;
            }
            route(post, Boolean.TRUE.equals(known));
        } else {
            route(post, false);
        }
    }

    private void routeLater(Post post) {
        boolean routed = false;
        try {
            routed = route(post, inThread(post.rootId()));
        } finally {
            if (!routed) {
                done(post, false);
            }
            synchronized (mutex) {
                int left = pending.getOrDefault(post.rootId(), 1) - 1;
                if (left > 0) {
                    pending.put(post.rootId(), left);
                } else {
                    pending.remove(post.rootId());
                }
            }
        }
    }

    /** Adressierten Post an Befehl oder Warteschlange übergeben ({@code true}). */
    private boolean route(Post post, boolean inThread) {
        if (!shouldAnswer(post, inThread)) {
            return false;
        }
        hold(post);
        String key = Routing.conversationKey(post);
        String root = Routing.replyRoot(post);
        if (root != null) {
            markThread(root); // Folgeposts im Thread brauchen keine Erwähnung
        }
        Command cmd = parseCommand(text(post));
        boolean top = !post.channelType().equals("D") && post.rootId().isEmpty();
        if (cmd != null && (!QUEUED_COMMANDS.contains(cmd.name()) || top)) {
            submit(commands, "commandNow", () -> commandNow(key, post, cmd));
            return true;
        }
        enqueue(key, post);
        return true;
    }

    private void commandNow(String key, Post post, Command cmd) {
        try {
            command(key, post, cmd);
        } finally {
            done(post, true);
        }
    }

    private String name() {
        return Json.str(mm.me(), "username");
    }

    /** Nachricht ohne {@code @captain} (für Befehle und Prompt). */
    private String text(Post post) {
        return Routing.stripMention(post.message(), name(), displayName(mm.me()));
    }

    private boolean shouldAnswer(Post post, boolean inThread) {
        JsonNode me = mm.me();
        return Routing.shouldAnswer(post, Json.str(me, "id"), Json.str(me, "username"), inThread);
    }

    /**
     * {@link Routing#shouldAnswer} inkl. Thread-Beteiligung.
     *
     * <p>Blockiert ggf. für ein REST-Nachschlagen – nicht im WebSocket-Thread aufrufen
     * ({@code onPost} verlagert das in den Lookup-Worker).
     */
    public boolean addressed(Post post) {
        if (shouldAnswer(post, false)) {
            return true;
        }
        if (post.rootId().isEmpty() || post.channelType().equals("D")) {
            return false;
        }
        return shouldAnswer(post, inThread(post.rootId()));
    }

    /** Beteiligung laut Cache ({@code null} = unbekannt). Aufrufer hält {@code mutex}. */
    private Boolean knownThread(String rootId) {
        if (threads.containsKey(rootId)) {
            threads.remove(rootId);
            threads.put(rootId, true); // zuletzt benutzt
            return true;
        }
        Double expires = noThread.get(rootId);
        if (expires != null) {
            if (expires > Clock.monotonic()) {
                return false;
            }
            noThread.remove(rootId);
        }
        return null;
    }

    /** Captain schreibt (gleich) im Thread: positiv merken, Negativ-Eintrag verwerfen. */
    void markThread(String rootId) {
        synchronized (mutex) {
            noThread.remove(rootId);
            threads.remove(rootId);
            threads.put(rootId, true);
            trim(threads, threadsMax);
        }
    }

    /** Hat Captain im Thread {@code rootId} schon geschrieben? (Cache, sonst REST) */
    private boolean inThread(String rootId) {
        Boolean known;
        synchronized (mutex) {
            known = knownThread(rootId);
        }
        if (known != null) {
            return known;
        }
        List<JsonNode> thread;
        try {
            thread = mm.getThread(rootId);
        } catch (RuntimeException e) {
            log.warn("Thread {} nicht ladbar: {}", rootId, e.toString());
            return false;
        }
        String me = Json.str(mm.me(), "id");
        if (thread.stream().anyMatch(p -> Json.str(p, "user_id").equals(me))) {
            markThread(rootId);
            return true;
        }
        synchronized (mutex) {
            if (!threads.containsKey(rootId)) { // inzwischen selbst geantwortet?
                noThread.remove(rootId);
                noThread.put(rootId, Clock.monotonic() + noThreadTtl);
                trim(noThread, noThreadMax);
            }
        }
        return false;
    }

    private boolean firstTime(String postId) {
        synchronized (mutex) {
            if (seen.containsKey(postId)) {
                return false;
            }
            seen.put(postId, true);
            trim(seen, SEEN_MAX);
            return true;
        }
    }

    private void enqueue(String key, Post post) {
        boolean busy;
        synchronized (mutex) {
            lanes.computeIfAbsent(key, k -> new ArrayDeque<>()).add(post);
            channels.put(key, post.channelId());
            busy = active.contains(key);
            if (!busy) {
                active.add(key);
                try {
                    pool.execute(() -> drain(key));
                } catch (RejectedExecutionException e) {
                    active.remove(key); // beim Beenden: Post bleibt offen → nächster Start
                }
            } else {
                queued.add(post.id());
            }
        }
        if (busy) {
            log.info("{}: Antwort läuft, Post {} wartet", key, post.id());
            submit(side, "addReaction", () -> mm.addReaction(post.id(), QUEUED_EMOJI));
        }
    }

    // -- Abarbeitung pro Session ---------------------------------------------

    private void drain(String key) {
        try (KeyLocks.Held held = locks.lock(key)) {
            while (true) {
                List<Post> batch;
                synchronized (mutex) {
                    if (stopping.isSet()) { // Rest bleibt offen → nächster Start
                        active.remove(key);
                        return;
                    }
                    // Bündel erst jetzt aus der Spur nehmen: Bis dahin verwirft !stopp es noch
                    batch = nextBatch(lanes.get(key));
                    if (batch.isEmpty()) {
                        active.remove(key);
                        lanes.remove(key);
                        channels.remove(key);
                        return;
                    }
                    inflight.add(key);
                }
                try {
                    unmarkQueued(batch);
                    Command cmd = parseCommand(text(batch.getFirst()));
                    if (cmd != null && QUEUED_COMMANDS.contains(cmd.name())) {
                        command(key, batch.getFirst(), cmd);
                    } else {
                        answer(key, batch);
                    }
                } catch (RuntimeException e) { // Spur darf nicht sterben
                    log.error("{}: Verarbeitung fehlgeschlagen", key, e);
                    reply(batch.getLast(), "⚠️ Interner Fehler: " + e.getMessage(), false,
                            batch.stream().map(Post::id).toList());
                } finally {
                    synchronized (mutex) {
                        inflight.remove(key);
                        cancelled.remove(key);
                    }
                    // Bündel: alle erst nach der fertigen Gesamtantwort
                    batch.forEach(p -> done(p, true));
                }
            }
        }
    }

    /** Nächstes Bündel vorne aus der Spur: ein Befehl allein oder alle Nachrichten bis zum nächsten Befehl. */
    private List<Post> nextBatch(Deque<Post> lane) {
        List<Post> out = new ArrayList<>();
        while (lane != null && !lane.isEmpty()) {
            boolean cmd = parseCommand(text(lane.peekFirst())) != null;
            if (cmd && !out.isEmpty()) {
                break;
            }
            out.add(lane.pollFirst());
            if (cmd) {
                break;
            }
        }
        return out;
    }

    private void unmarkQueued(List<Post> posts) {
        List<String> marked = new ArrayList<>();
        synchronized (mutex) {
            for (Post p : posts) {
                if (queued.remove(p.id())) {
                    marked.add(p.id());
                }
            }
        }
        for (String pid : marked) {
            submit(side, "removeReaction", () -> mm.removeReaction(pid, QUEUED_EMOJI));
        }
    }

    /** Verzeichnis der opencode-Session {@code sid}: {@code <SESSIONS_DIR>/<sid>}. */
    String sessionDir(String sid) {
        return cfg.sessionsDir().replaceAll("/+$", "") + "/" + sid;
    }

    /** Liegt {@code directory} direkt unterhalb von SESSIONS_DIR? */
    private boolean owned(String directory) {
        if (directory == null || directory.isEmpty()) {
            return false;
        }
        try {
            Path parent = Path.of(directory).normalize().getParent();
            return parent != null && parent.equals(Path.of(cfg.sessionsDir()).normalize());
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /** Löscht ein Session-Verzeichnis – nur direkt unterhalb von SESSIONS_DIR. */
    void removeDir(String directory) {
        if (!owned(directory)) {
            return;
        }
        Path root = Path.of(directory);
        if (!Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.debug("Session-Verzeichnis {} nicht ganz gelöscht: {}", directory, e.toString());
        }
    }

    /** {@code expired}: neu angelegt, weil der Janitor die vorige aufgeräumt hat. */
    private record SessionRef(String sid, String directory, boolean created, boolean expired) {
    }

    /**
     * (opencode-Session, Verzeichnis, neu angelegt?) für {@code key}.
     *
     * <p>Jede opencode-Session hat ein eigenes Verzeichnis {@code <SESSIONS_DIR>/<id>}, das der Bot
     * <b>vor</b> dem Anlegen der Session erzeugt. Eine neue Session ({@code fresh} bzw. alte ohne
     * gültiges Verzeichnis) löscht das alte.
     */
    private SessionRef session(String key, boolean fresh) {
        Map<String, String> entry = store.get(key);
        String sid = entry == null ? null : entry.get("opencode_session_id");
        String directory = entry == null ? null : entry.get("directory");
        long now = clock.getAsLong();
        if (sid != null && !sid.isEmpty() && !fresh && owned(directory)) {
            mkdirs(directory); // vor dem Prompt, sonst HTTP 500
            store.touch(key, now);
            return new SessionRef(sid, directory, false, false);
        }
        boolean expired = entry != null && entry.containsKey(SessionStore.EXPIRED_AT);
        removeDir(directory);
        sid = newSessionId.get();
        directory = sessionDir(sid);
        mkdirs(directory);
        String agent = entry == null ? null : entry.get("agent");
        List<String> own = agent == null ? List.of() : cfg.restAgents().getOrDefault(agent, List.of());
        String created = oc.createSession(directory, (key.startsWith(SessionKeys.REST) ? "REST " : "Mattermost ") + key,
                SessionPermissions.of(directory, cfg.webfetchAllow(), cfg.shared(), cfg.agentTools(), own), sid, agent);
        if (!created.equals(sid)) {
            log.warn("{}: opencode vergab {} statt {}", key, created, sid);
            sid = created;
        }
        if (!setInstructions(key, sid, directory)) {
            synchronized (mutex) {
                noInstructions.add(sid); // → Vorspann im ersten Prompt
            }
        }
        store.set(key, sid, directory, now);
        log.info("{}: neue opencode-Session {} in {}{}", key, sid, directory, agent == null ? "" : " (Agent " + agent + ")");
        return new SessionRef(sid, directory, true, expired);
    }

    private static void mkdirs(String directory) {
        try {
            Files.createDirectories(Path.of(directory));
        } catch (IOException e) {
            throw new IllegalStateException("Session-Verzeichnis " + directory + " nicht anlegbar: " + e, e);
        }
    }

    private Map<String, String> instructions(String key, String directory) {
        Map<String, String> entries = SessionKeys.sessionInstructions(cfg.systemPrompt(), directory, cfg.webfetchAllow(),
                cfg.shared());
        entries.put("captain-kanal", key.startsWith(SessionKeys.REST) ? API_NOTE : MATTERMOST_NOTE);
        return entries;
    }

    private boolean setInstructions(String key, String sid, String directory) {
        try {
            oc.setInstructions(sid, instructions(key, directory));
            return true;
        } catch (OpencodeException e) { // Permissions greifen trotzdem
            log.warn("{}: Systemanweisungen für {} nicht gesetzt: {}", key, sid, e.getMessage());
            return false;
        }
    }

    /**
     * Ersatz, falls die (experimentelle) Instructions-API scheiterte.
     *
     * <p>Neue Session: Persona + Arbeitsumgebung als markierter Vorspann im ersten Prompt
     * (Kompaktierung kann ihn verlieren). Beim nächsten Prompt einmal erneut über die API versuchen,
     * danach nicht mehr.
     */
    private String preface(String key, String sid, String directory, boolean created) {
        synchronized (mutex) {
            if (!noInstructions.contains(sid)) {
                return "";
            }
            if (!created) {
                noInstructions.remove(sid);
            }
        }
        if (!created && setInstructions(key, sid, directory)) {
            log.info("{}: Systemanweisungen für {} nachgetragen", key, sid);
            return "";
        }
        String body = String.join("\n\n", instructions(key, directory).values());
        return "[Systemhinweise für diese Unterhaltung]\n" + body + "\n[Ende der Systemhinweise]\n\n";
    }

    /** Mattermost-Nutzer, {@link #USER_TTL} s zwischengespeichert (Bot-Prüfung, Anzeigenamen). */
    private JsonNode user(String userId) {
        double now = Clock.monotonic();
        synchronized (users) {
            CachedUser cached = users.get(userId);
            if (cached != null && cached.expires() > now) {
                return cached.user();
            }
        }
        try {
            JsonNode u = mm.getUser(userId);
            synchronized (users) {
                users.remove(userId);
                users.put(userId, new CachedUser(u, now + USER_TTL));
                trim(users, USERS_MAX);
            }
            return u;
        } catch (RuntimeException e) {
            log.warn("Nutzer {} nicht ladbar: {}", userId, e.toString());
            return Json.object();
        }
    }

    private record PromptParts(String text, List<String> files, List<String> skipped) {
    }

    /**
     * Text, Anhangpfade und Hinweise zu übergangenen Anhängen für ein Bündel.
     *
     * <p>Anhänge über {@code MAX_ATTACHMENT_MB} werden nicht geladen; das Modell erfährt es im
     * Prompt, der Nutzer über einen Hinweis unter der Antwort.
     */
    private PromptParts buildPrompt(List<Post> posts, String directory) {
        boolean group = !posts.getFirst().channelType().equals("D");
        List<String> parts = new ArrayList<>();
        List<String> files = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Long limit = cfg.maxAttachmentBytes();
        for (Post post : posts) {
            StringBuilder text = new StringBuilder(text(post));
            List<String> names = new ArrayList<>();
            for (String fid : post.fileIds()) {
                String path;
                try {
                    path = mm.downloadFile(fid, directory, limit);
                } catch (AttachmentTooLargeException e) {
                    String note = "„" + e.name() + "“ nicht geladen: größer als " + mb(e.limit()) + " MB";
                    log.info("Anhang {}: {}", fid, note);
                    skipped.add(note);
                    text.append("\n(Anhang „").append(e.name()).append("“ wurde nicht geladen, weil er größer als ")
                            .append(mb(e.limit())).append(" MB ist – sein Inhalt ist dir unbekannt.)");
                    continue;
                } catch (RuntimeException e) {
                    log.warn("Anhang {} nicht ladbar: {}", fid, e.toString());
                    text.append("\n(Anhang ").append(fid).append(" konnte nicht geladen werden)");
                    continue;
                }
                files.add(path.replace("\\", "/")); // Container-Pfad (POSIX)
                names.add(Path.of(path).getFileName().toString());
            }
            if (!names.isEmpty()) {
                text.append("\n(Anhänge im Arbeitsverzeichnis: ").append(String.join(", ", names)).append(")");
            }
            String body = text.toString().strip();
            if (group) {
                JsonNode u = user(post.userId());
                String name = !u.isEmpty() ? displayName(u) : (post.senderName().isEmpty() ? "?" : post.senderName());
                parts.add(name + ": " + body);
            } else {
                parts.add(body);
            }
        }
        return new PromptParts(String.join(group ? "\n" : "\n\n", parts), files, skipped);
    }

    /** Bisheriger Thread (ohne {@code exclude}) als Kontext für eine neue Thread-Session. */
    private String threadContext(String rootId, Set<String> exclude) {
        List<JsonNode> thread;
        try {
            thread = mm.getThread(rootId);
        } catch (RuntimeException e) {
            log.warn("Thread {} nicht ladbar: {}", rootId, e.toString());
            return "";
        }
        List<String> lines = recent(thread, exclude);
        if (lines.isEmpty()) {
            return "";
        }
        return "[Bisheriger Verlauf dieses Threads]\n" + String.join("\n", lines) + "\n[Ende des Verlaufs]\n\n";
    }

    /** Letzte DM-Posts (ohne {@code exclude}) als Kontext, nachdem der Janitor die Session aufgeräumt hat. */
    private String dmContext(String channelId, Set<String> exclude) {
        List<JsonNode> posts;
        try {
            posts = mm.getChannelPosts(channelId);
        } catch (RuntimeException e) {
            log.warn("Kanal {} nicht ladbar: {}", channelId, e.toString());
            return "";
        }
        List<String> lines = recent(posts, exclude);
        if (lines.isEmpty()) {
            return "";
        }
        return "[Bisheriger Verlauf dieser Direktnachrichten – die frühere Sitzung wurde nach längerer Pause "
                + "beendet]\n" + String.join("\n", lines) + "\n[Ende des Verlaufs]\n\n";
    }

    /**
     * Die letzten {@link #THREAD_CONTEXT_POSTS} Posts (älteste zuerst) als {@code Name: Text}, jeweils
     * gekürzt; ohne {@code exclude}, Systemposts und alles bis einschließlich Captains letzter
     * {@code !neu}-Bestätigung.
     */
    private List<String> recent(List<JsonNode> posts, Set<String> exclude) {
        String me = Json.str(mm.me(), "id");
        int from = 0;
        for (int i = posts.size() - 1; i >= 0; i--) {
            JsonNode post = posts.get(i);
            if (Json.str(post, "user_id").equals(me) && Json.str(post, "message").strip().endsWith(NEW_NOTE)) {
                from = i + 1;
                break;
            }
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode post : posts.subList(Math.max(from, posts.size() - THREAD_CONTEXT_POSTS), posts.size())) {
            String message = Json.str(post, "message").strip();
            if (message.isEmpty() || exclude.contains(Json.str(post, "id")) || Json.truthy(post, "type")) {
                continue;
            }
            if (message.length() > THREAD_CONTEXT_CHARS) {
                message = message.substring(0, THREAD_CONTEXT_CHARS) + " […]";
            }
            String name;
            if (Json.str(post, "user_id").equals(me)) {
                name = "Captain (du)";
            } else {
                name = displayName(user(Json.str(post, "user_id")));
            }
            lines.add(name + ": " + message);
        }
        return lines;
    }

    /** Kanal-Posts seit Captains letzter Beteiligung als Vorgeschichte. */
    private String channelContext(Post post, Set<String> exclude) {
        List<JsonNode> posts;
        try {
            posts = mm.getChannelPosts(post.channelId());
        } catch (RuntimeException e) {
            log.warn("Kanal {} nicht ladbar: {}", post.channelId(), e.toString());
            return "";
        }
        long before = post.createAt() != 0 ? post.createAt()
                : posts.stream().mapToLong(p -> Json.lng(p, "create_at")).max().orElse(0) + 1;
        List<ObjectNode> history = Routing.channelHistory(posts, Json.str(mm.me(), "id"), before, exclude,
                uid -> user(uid).path("is_bot").asBoolean(false), uid -> displayName(user(uid)),
                cfg.historyMaxPosts(), cfg.historyMaxChars(), Routing.HISTORY_POST_CHARS);
        if (history.isEmpty()) {
            return "";
        }
        String body = String.join("\n", history.stream().map(p -> Json.str(p, "name") + ": " + Json.str(p, "message")).toList());
        return "[Bisherige Unterhaltung im Kanal seit deiner letzten Antwort hier – nur Vorgeschichte]\n"
                + body + "\n[Ende der Vorgeschichte]\n\n";
    }

    /** Modell/Variante: Session vor Kanal ({@code ch:<id>}) vor Standard. */
    Settings settings(String key, Post post) {
        Map<String, String> entry = store.get(key);
        if (entry != null && entry.get("model") != null && !entry.get("model").isEmpty()) {
            return new Settings(entry.get("model"), entry.get("variant"), "hier");
        }
        String channel = SessionKeys.MATTER_CHANNEL + post.channelId();
        if (!post.channelType().equals("D") && !key.equals(channel)) {
            entry = store.get(channel);
            if (entry != null && entry.get("model") != null && !entry.get("model").isEmpty()) {
                return new Settings(entry.get("model"), entry.get("variant"), "kanal");
            }
        }
        return new Settings(cfg.opencodeModel(), cfg.opencodeVariant(), "standard");
    }

    private static String describe(Settings settings) {
        String text = "`" + (settings.model() == null ? "opencode-Standard" : settings.model()) + "`";
        if (settings.variant() != null && !settings.variant().isEmpty()) {
            text += ", Variante `" + settings.variant() + "`";
        }
        return text + switch (settings.source()) {
            case "kanal" -> " (Kanal)";
            case "standard" -> " (Standard)";
            default -> "";
        };
    }

    private void answer(String key, List<Post> batch) {
        List<String> batchIds = batch.stream().map(Post::id).toList();
        // Bots (auch ohne from_bot-Prop) nie beantworten → keine Bot-Schleifen
        List<Post> posts = batch.stream().filter(p -> !user(p.userId()).path("is_bot").asBoolean(false)).toList();
        if (posts.isEmpty()) {
            return;
        }
        Post first = posts.getFirst();
        String root = Routing.replyRoot(first);
        Settings settings = settings(key, first);

        PostStreamer streamer = new PostStreamer(mm, first.channelId(), root);
        LiveAnswer live = new LiveAnswer(mm, streamer, first.channelId(), root);
        if (takeLate(posts)) {
            live.prefix = Catchup.LATE_NOTE + "\n\n";
        }
        double started = Clock.monotonic();
        live.start();
        String result = null;
        String note = "";
        List<String> skipped = new ArrayList<>();
        try {
            PromptOptions base = PromptOptions.in(null)
                    .model(settings.model(), settings.variant())
                    .onDelta(live::onDelta).onStatus(live::onStatus)
                    .idleTimeout(promptTimeout);
            try {
                result = send(key, posts, first, base, skipped, false);
            } catch (SessionGoneException e) {
                log.warn("{}: Session {} verschwunden – neue Session, neuer Versuch", key, running.get(key));
                live.reset();
                result = send(key, posts, first, base, skipped, true);
            }
        } catch (PromptInterruptedException e) {
            note = "_(abgebrochen)_";
        } catch (OpencodeException e) {
            log.warn("{}: opencode-Fehler: {}", key, e.getMessage());
            note = "⚠️ opencode-Fehler: " + e.getMessage();
        } catch (RuntimeException e) { // Netzwerk, Mattermost, …
            log.error("{}: Antwort fehlgeschlagen", key, e);
            note = "⚠️ Fehler: " + e.getMessage();
        } finally {
            running.remove(key);
            live.stop();
        }
        if (stopping.isSet()) {
            // Neustart: Zwischenstand als abgebrochen kennzeichnen (zählt nicht als Antwort), der
            // nächste Start beantwortet den Post neu
            String partial = live.text().strip();
            try {
                streamer.finish(partial.isEmpty() ? Catchup.INTERRUPTED_NOTE : partial + "\n\n" + Catchup.INTERRUPTED_NOTE);
            } catch (RuntimeException e) { // Mattermost evtl. schon zu
                log.info("{}: abgebrochene Antwort nicht markierbar: {}", key, e.toString());
            }
            log.info("{}: Antwort durch Beenden unterbrochen – wird nach dem Start nachgeholt", key);
            return;
        }
        if (result == null) {
            String partial = live.text().strip();
            result = partial.isEmpty() ? note : partial + "\n\n" + note;
            if (partial.isEmpty()) {
                live.prefix = ""; // kein Verspätungshinweis vor einer reinen Fehlernotiz
            }
        }
        if (!skipped.isEmpty()) { // Hinweis sicher in der Antwort, unabhängig vom Modell
            result = result + "\n\n" + String.join("\n", skipped.stream().map(s -> "_(Anhang " + s + ")_").toList());
        }
        streamer.finish(live.prefix + result, Catchup.answersProps(batchIds));
        log.info("{}: Antwort fertig nach {} s ({} Zeichen)", key,
                String.format(Locale.ROOT, "%.1f", Clock.monotonic() - started), result.length());
    }

    private String send(String key, List<Post> posts, Post first, PromptOptions base, List<String> skipped,
            boolean fresh) {
        // Anhänge landen im Verzeichnis der (ggf. neuen) Session.
        SessionRef s = session(key, fresh);
        running.put(key, s.sid());
        PromptParts parts = buildPrompt(posts, s.directory());
        skipped.clear();
        skipped.addAll(parts.skipped());
        log.info("{}: Prompt mit {} Nachricht(en), {} Anhang/Anhängen", key, posts.size(), parts.files().size());
        String text = parts.text();
        if (s.created()) {
            Set<String> ids = new HashSet<>(posts.stream().map(Post::id).toList());
            if (!first.rootId().isEmpty()) { // Erwähnung in bestehendem Thread
                text = threadContext(first.rootId(), ids) + text;
            } else if (!first.channelType().equals("D")) { // Top-Level-Erwähnung → neuer Thread
                text = channelContext(first, ids) + text;
            } else if (s.expired()) { // DM nach dem Aufräumen
                text = dmContext(first.channelId(), ids) + text;
            }
        }
        String prompt = preface(key, s.sid(), s.directory(), s.created()) + text;
        PromptOptions options = new PromptOptions(s.directory(), base.model(), base.variant(),
                parts.files().isEmpty() ? null : parts.files(), base.onDelta(), base.onStatus(), base.idleTimeout());
        synchronized (mutex) {
            if (cancelled.remove(key)) { // !stopp, während Anhänge luden bzw. die Session entstand
                throw new PromptInterruptedException("vor dem Start abgebrochen");
            }
        }
        return oc.prompt(s.sid(), prompt, options);
    }

    // -- REST-Eingabekanal ----------------------------------------------------

    /**
     * Beantwortet {@code text} in der Unterhaltung {@code conversation} (Session-Key
     * {@code rest:<conversation>}); blockiert bis zur fertigen Antwort.
     *
     * <p>Gleiche ID = gleiche opencode-Session, auch über Neustarts (Session-Store). Anfragen
     * derselben ID laufen nacheinander. Modell: wie für den Key gesetzt, sonst Standard. Agent: beim
     * ersten Aufruf gewählt und am Key gemerkt (siehe {@link #agent}).
     *
     * @param sender Name, der vor die Nachricht gesetzt wird ({@code Name: Text}); {@code null} = keiner
     * @param agent  opencode-Agent aus {@code CAPTAIN_REST_AGENTS}; {@code null} = gemerkter bzw. Standard
     * @throws AgentRejectedException     Agent nicht erlaubt (400) oder Wechsel (409)
     * @throws PromptInterruptedException bei {@link #forget} oder Beenden während der Antwort
     */
    @Override
    public Answer ask(String conversation, String text, String sender, String agent) {
        String key = SessionKeys.REST + conversation;
        String prompt = sender == null || sender.isBlank() ? text : sender.strip() + ": " + text;
        try (KeyLocks.Held held = locks.lock(key)) {
            Map<String, String> before = store.get(key);
            String chosen = agent(key, before, agent);
            if (chosen != null && (before == null || before.get("agent") == null)) {
                store.setSetting(key, Map.of("agent", chosen));
            }
            synchronized (mutex) {
                if (stopping.isSet()) {
                    throw new PromptInterruptedException("Captain wird beendet");
                }
                active.add(key); // waitQuiet wartet beim Beenden auch hierauf
            }
            try {
                Map<String, String> entry = store.get(key);
                boolean own = entry != null && entry.get("model") != null && !entry.get("model").isEmpty();
                PromptOptions base = PromptOptions.in(null)
                        .model(own ? entry.get("model") : cfg.opencodeModel(), own ? entry.get("variant") : cfg.opencodeVariant())
                        .onDelta(t -> { }).onStatus(t -> { })
                        .idleTimeout(promptTimeout);
                double started = Clock.monotonic();
                Answer answer;
                try {
                    answer = askOnce(key, prompt, base, false);
                } catch (SessionGoneException e) {
                    log.warn("{}: Session {} verschwunden – neue Session, neuer Versuch", key, running.get(key));
                    answer = askOnce(key, prompt, base, true);
                }
                log.info("{}: REST-Antwort fertig nach {} s ({} Zeichen)", key,
                        String.format(Locale.ROOT, "%.1f", Clock.monotonic() - started), answer.text().length());
                return answer;
            } finally {
                running.remove(key);
                synchronized (mutex) {
                    active.remove(key);
                }
            }
        }
    }

    @Override
    public void checkAgent(String conversation, String agent) {
        String key = SessionKeys.REST + conversation;
        agent(key, store.get(key), agent);
    }

    /**
     * Agent der REST-Unterhaltung: der gemerkte, sonst {@code requested}; {@code null} = Standard.
     *
     * <p>Abgelehnt wird ein Agent, der nicht in {@code CAPTAIN_REST_AGENTS} steht (400, auch ein
     * gemerkter, der dort nicht mehr steht), und ein Wechsel (409): Agent und Rechte stehen beim
     * Anlegen der Session fest. Ein stiller Neustart würde den bisherigen Verlauf verwerfen; wer
     * wirklich wechseln will, schickt vorher {@code DELETE}.
     */
    private String agent(String key, Map<String, String> entry, String requested) {
        String stored = entry == null ? null : entry.get("agent");
        if (requested != null && !cfg.restAgents().containsKey(requested)) {
            throw new AgentRejectedException(400, "Agent \"" + requested + "\" ist nicht erlaubt (CAPTAIN_REST_AGENTS)");
        }
        if (stored != null && !cfg.restAgents().containsKey(stored)) {
            throw new AgentRejectedException(400, "Agent \"" + stored + "\" dieser Unterhaltung ist nicht mehr erlaubt"
                    + " (CAPTAIN_REST_AGENTS) – vorher DELETE");
        }
        if (requested == null || requested.equals(stored)) {
            return stored;
        }
        if (stored != null) {
            throw new AgentRejectedException(409, "Unterhaltung läuft mit Agent \"" + stored + "\" – vorher DELETE");
        }
        String sid = entry == null ? null : entry.get("opencode_session_id");
        if (sid != null && !sid.isEmpty()) {
            throw new AgentRejectedException(409, "Unterhaltung läuft ohne Agent – vorher DELETE");
        }
        return requested;
    }

    private Answer askOnce(String key, String prompt, PromptOptions base, boolean fresh) {
        SessionRef s = session(key, fresh);
        running.put(key, s.sid());
        log.info("{}: REST-Prompt ({} Zeichen)", key, prompt.length());
        String text = preface(key, s.sid(), s.directory(), s.created()) + prompt;
        PromptOptions options = new PromptOptions(s.directory(), base.model(), base.variant(), null,
                base.onDelta(), base.onStatus(), base.idleTimeout());
        return new Answer(s.sid(), oc.prompt(s.sid(), text, options));
    }

    /**
     * Vergisst die REST-Unterhaltung {@code conversation} (wie {@code !neu}): bricht eine laufende
     * Antwort ab, löscht opencode-Session, Session-Eintrag und -Verzeichnis. {@code false} = gab es
     * nicht.
     */
    @Override
    public boolean forget(String conversation) {
        String key = SessionKeys.REST + conversation;
        String sid = running.get(key);
        if (sid != null) {
            oc.abort(sid);
        }
        try (KeyLocks.Held held = locks.lock(key)) {
            Map<String, String> entry = store.get(key);
            if (entry == null) {
                return false;
            }
            store.reset(key);
            if (entry.containsKey("agent")) { // vergessen heißt hier: auch den Agenten
                Map<String, String> clear = new HashMap<>();
                clear.put("agent", null);
                store.setSetting(key, clear);
            }
            removeDir(entry.get("directory"));
            deleteSession(key, entry.get("opencode_session_id"));
            log.info("{}: Unterhaltung vergessen", key);
            return true;
        }
    }

    /** Löscht die opencode-Session {@code sid} (falls vorhanden); Fehler nur protokollieren. */
    private void deleteSession(String key, String sid) {
        if (sid == null || sid.isEmpty()) {
            return;
        }
        synchronized (mutex) {
            noInstructions.remove(sid);
        }
        try {
            oc.deleteSession(sid);
        } catch (RuntimeException e) { // Eintrag ist schon weg – Session bleibt in opencode liegen
            log.warn("{}: opencode-Session {} nicht gelöscht: {}", key, sid, e.toString());
        }
    }

    // -- Aufräumen -----------------------------------------------------------

    /**
     * Janitor-Lauf: räumt jede Session auf, deren letzte Nutzung länger als {@code SESSION_MAX_IDLE}
     * her ist (alle Kanäle); liefert die Anzahl.
     *
     * <p>Aufräumen = opencode-Session löschen, Verzeichnis löschen, Store-Eintrag zurücksetzen
     * (Modell bleibt; DMs bekommen {@code expired_at} für {@link #dmContext}). Keys mit laufender
     * oder wartender Antwort und solche, deren Lock gerade jemand hält, kommen beim nächsten Lauf
     * dran. Einträge ohne {@code last_used} (vor diesem Feld angelegt) gelten ab jetzt als genutzt.
     * Schlägt das Löschen in opencode fehl, bleibt alles stehen (nächster Lauf).
     */
    int sweep() {
        long maxIdle = cfg.sessionMaxIdle().toMillis();
        if (maxIdle <= 0) {
            return 0;
        }
        long now = clock.getAsLong();
        int removed = 0;
        int busy = 0;
        for (String key : store.keys()) {
            if (stopping.isSet()) {
                break;
            }
            Map<String, String> entry = store.get(key);
            if (entry == null || entry.get("opencode_session_id") == null) {
                continue; // nur Einstellungen (z. B. Kanal-Modell)
            }
            Long last = SessionStore.lastUsed(entry);
            if (last == null) {
                store.touch(key, now);
                continue;
            }
            if (now - last < maxIdle) {
                continue;
            }
            Boolean done = expire(key, now, maxIdle);
            if (done == null) {
                busy++;
            } else if (done) {
                removed++;
            }
        }
        if (removed > 0 || busy > 0) {
            log.info("Aufgeräumt: {} Session(s) länger als {} h unbenutzt{}", removed,
                    String.format(Locale.ROOT, "%.0f", maxIdle / 3.6e6), busy > 0 ? ", " + busy + " gerade belegt" : "");
        }
        return removed;
    }

    /** Eine Session aufräumen: {@code true} = erledigt, {@code false} = nicht (mehr) fällig, {@code null} = belegt. */
    private Boolean expire(String key, long now, long maxIdle) {
        KeyLocks.Held held = locks.tryLock(key);
        if (held == null) {
            return null;
        }
        try (held) {
            synchronized (mutex) {
                if (active.contains(key) || running.containsKey(key)) {
                    return null;
                }
            }
            Map<String, String> entry = store.get(key); // inzwischen benutzt?
            Long last = SessionStore.lastUsed(entry);
            String sid = entry == null ? null : entry.get("opencode_session_id");
            if (sid == null || last == null || now - last < maxIdle) {
                return false;
            }
            try {
                oc.deleteSession(sid);
            } catch (RuntimeException e) {
                log.warn("{}: opencode-Session {} nicht löschbar ({}) – nächster Versuch beim nächsten Lauf", key, sid,
                        e.toString());
                return false;
            }
            store.expire(key, now, key.startsWith(SessionKeys.MATTER_DM));
            removeDir(entry.get("directory"));
            synchronized (mutex) {
                noInstructions.remove(sid);
            }
            log.info("{}: Session {} seit {} h unbenutzt – aufgeräumt", key, sid,
                    String.format(Locale.ROOT, "%.0f", (now - last) / 3.6e6));
            return true;
        }
    }

    // -- Befehle -------------------------------------------------------------

    private void reply(Post post, String message) {
        reply(post, message, true, null);
    }

    /** Antwort auf {@code post}; {@code props.captain_answers} = {@code answers} (Standard: der Post). */
    private void reply(Post post, String message, boolean late, List<String> answers) {
        if (takeLate(List.of(post)) && late) {
            message = Catchup.LATE_NOTE + "\n\n" + message;
        }
        try {
            mm.createPost(post.channelId(), message, Routing.replyRoot(post), null,
                    Catchup.answersProps(answers != null ? answers : List.of(post.id())));
        } catch (RuntimeException e) {
            log.error("Antwort an {} nicht zustellbar", post.channelId(), e);
        }
    }

    private void command(String key, Post post, Command cmd) {
        switch (cmd.name()) {
            case "hilfe" -> reply(post, HELP.replace("{name}", name().isEmpty() ? "captain" : name()));
            case "neu" -> {
                if (!post.channelType().equals("D") && post.rootId().isEmpty()) {
                    reply(post, "Hier beginnt jede Erwähnung ohnehin eine neue Unterhaltung in einem "
                            + "eigenen Thread. `!neu` in einem Thread setzt diesen Thread zurück.");
                    return;
                }
                Map<String, String> entry = store.get(key);
                store.reset(key);
                if (entry != null) {
                    removeDir(entry.get("directory"));
                    deleteSession(key, entry.get("opencode_session_id"));
                }
                reply(post, NEW_NOTE);
            }
            case "stopp" -> {
                if (!post.channelType().equals("D") && post.rootId().isEmpty()) {
                    stopChannel(post);
                } else {
                    stopKey(key, post);
                }
            }
            case "modell" -> modelCommand(Routing.settingsKey(post), post, cmd.arg());
            default -> throw new IllegalStateException("unbekannter Befehl " + cmd.name());
        }
    }

    private record Stopped(int aborted, int dropped) {
    }

    /** Bricht laufende Antworten der Keys ab, verwirft Wartende. */
    private Stopped stopKeys(List<String> keys, Post post) {
        List<Post> dropped = new ArrayList<>();
        synchronized (mutex) {
            for (String key : keys) {
                Deque<Post> lane = lanes.get(key);
                if (lane != null) {
                    lane.stream().filter(p -> !p.id().equals(post.id())).forEach(dropped::add);
                    lane.clear();
                }
            }
            dropped.forEach(p -> queued.remove(p.id()));
        }
        for (Post p : dropped) {
            done(p, true); // bewusst verworfen – nicht nachholen
            submit(side, "removeReaction", () -> mm.removeReaction(p.id(), QUEUED_EMOJI));
        }
        int aborted = 0;
        for (String key : keys) {
            boolean busy;
            synchronized (mutex) {
                // läuft ein Bündel (auch noch vor dem Prompt), bricht es ab – sonst nichts merken
                busy = inflight.contains(key);
                if (busy) {
                    cancelled.add(key);
                }
            }
            String sid = running.get(key);
            if (sid != null) {
                oc.abort(sid);
            }
            if (busy || sid != null) {
                aborted++;
            }
        }
        return new Stopped(aborted, dropped.size());
    }

    private static String stopMessage(Stopped s, boolean many) {
        String msg;
        if (s.aborted() == 0) {
            msg = "Gerade läuft keine Antwort.";
        } else if (many) {
            msg = "⏹️ " + s.aborted() + " laufende Antwort(en) im Kanal abgebrochen.";
        } else {
            msg = "⏹️ Abgebrochen.";
        }
        if (s.dropped() > 0) {
            msg += " " + s.dropped() + " wartende Nachricht(en) verworfen.";
        }
        return msg;
    }

    private void stopKey(String key, Post post) {
        reply(post, stopMessage(stopKeys(List.of(key), post), false));
    }

    /** {@code @captain !stopp} oben im Kanal: alle Unterhaltungen dieses Kanals. */
    private void stopChannel(Post post) {
        List<String> keys;
        synchronized (mutex) {
            keys = channels.entrySet().stream().filter(e -> e.getValue().equals(post.channelId()))
                    .map(Map.Entry::getKey).toList();
        }
        reply(post, stopMessage(stopKeys(keys, post), true));
    }

    private void modelCommand(String key, Post post, String arg) {
        if (arg.isEmpty()) {
            List<ModelInfo> models = oc.listModels(null);
            List<String> lines = new ArrayList<>(List.of("**Modell hier:** " + describe(settings(key, post)), ""));
            for (ModelInfo m : models) {
                String variants = m.variants().isEmpty() ? "" : " – Varianten: " + String.join(", ", m.variants());
                lines.add("- `" + m.id() + "`" + variants);
            }
            if (models.isEmpty()) {
                lines.add("_(opencode meldet keine Modelle)_");
            }
            lines.add("\nSetzen: `!modell <id> [variante]`, zurück: `!modell standard`");
            reply(post, String.join("\n", lines));
            return;
        }
        String[] words = arg.split("\\s+");
        if (List.of("standard", "default", "reset").contains(words[0].toLowerCase(Locale.ROOT))) {
            store.setModel(key, null, null);
            reply(post, "Modell zurückgesetzt – jetzt gilt " + describe(settings(key, post)) + ".");
            return;
        }
        String model = words[0];
        String variant = words.length > 1 ? words[1] : null;
        Map<String, ModelInfo> known = new LinkedHashMap<>();
        oc.listModels(null).forEach(m -> known.put(m.id(), m));
        if (!known.isEmpty() && !known.containsKey(model)) {
            reply(post, "Unbekanntes Modell `" + model + "`. Liste: `!modell`");
            return;
        }
        if (variant != null && !known.isEmpty() && !known.get(model).variants().contains(variant)) {
            reply(post, "Unbekannte Variante `" + variant + "` für `" + model + "`.");
            return;
        }
        store.setModel(key, model, variant);
        String suffix = variant != null ? ", Variante `" + variant + "`" : "";
        String where = key.startsWith(SessionKeys.MATTER_CHANNEL) ? "diesen Kanal (neue Threads)" : "diesen Chat";
        reply(post, "✅ Modell für " + where + ": `" + model + "`" + suffix);
    }
}
