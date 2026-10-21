package com.ewerlin.captain.opencode;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

import com.ewerlin.captain.util.Clock;
import com.ewerlin.captain.util.Http;
import com.ewerlin.captain.util.Json;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * opencode-Client für den Server-Modus ({@code opencode serve}, API v2).
 *
 * <p>Getestet gegen {@code @opencode/cli@2.1.2} (zuvor 2.0.20). REST über Springs {@code RestClient}, Antworten
 * kommen in {@code {"data": ...}} gekapselt. Live-Fortschritt kommt über den Event-Stream
 * {@code GET /api/event} (SSE, nur {@code data:}-Zeilen, alle Sessions gemischt).
 *
 * <h2>Architektur für parallele Prompts</h2>
 * Ein Client hält <b>genau einen</b> SSE-Reader-Thread ({@link EventHub}), der jedes Event anhand
 * von {@code data.sessionID} an die Warteschlangen der gerade laufenden {@code prompt}-Aufrufe
 * verteilt. Reconnect-Logik existiert nur einmal. Nach einem Abriss bekommt jeder Abonnent ein
 * Markierungs-Event und gleicht seinen Stand per REST ab ({@code GET /api/session/{id}} +
 * {@code /message}), weil opencode keine Events nachliefert.
 *
 * <p>Thread-sicher: mehrere {@code prompt}-Aufrufe für <i>verschiedene</i> Sessions dürfen parallel
 * laufen. Pro Session serialisiert der Aufrufer.
 *
 * <p>Hinweis: Das Arbeitsverzeichnis einer Session ist ein Pfad <i>im opencode-Container</i> (z. B.
 * {@code /tmp/captain/ses_…}). Es muss existieren, bevor ein Prompt geschickt wird, sonst antwortet
 * opencode mit HTTP 500. Der Client legt es nicht an – das ist Sache des Aufrufers (der Bot teilt
 * das Volume).
 */
public class OpencodeClient implements OpencodeApi, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OpencodeClient.class);

    /** Basic-Auth-Benutzer von opencode serve. */
    public static final String USER = "opencode";
    /** So lange höchstens auf MCP-Server einer Location warten (Sekunden). */
    public static final double MCP_WAIT = 10.0;
    /** … und so lange, bis eine frische Location ihre Server überhaupt meldet. */
    public static final double MCP_GRACE = 3.0;
    /** Nach dem Verbinden, bis opencode die Tools registriert hat. */
    public static final double MCP_SETTLE = 0.5;

    private static final Set<String> TERMINAL = Set.of(
            "session.execution.succeeded", "session.execution.failed", "session.execution.interrupted");

    private static final Map<String, String> TOOL_STATUS = toolStatus();

    private final String url;
    private final String authorization;
    private final Http http;
    final EventHub events;
    /** Solange ein Prompt läuft, spätestens so oft per REST nachsehen, ob die Ausführung zu Ende ist. */
    private final double reconcileInterval;
    /** Zum Warten; in Tests austauschbar. */
    DoubleConsumer sleeper = Clock::sleep;

    private final Object mcpLock = new Object();
    private Boolean mcpSeen; // zuletzt MCP-Server gemeldet? (null: noch nie geprüft)
    private int mcpEpoch; // Verbindungszähler des Event-Streams bei der letzten Prüfung
    private final Set<String> mcpDirs = new HashSet<>(); // Verzeichnisse, deren MCP-Tools schon bereitstehen

    public OpencodeClient(String url, String password) {
        this(url, password, 30.0, 60.0, 30.0);
    }

    public OpencodeClient(String url, String password, double timeout, double sseReadTimeout, double reconcileInterval) {
        this.url = url.replaceAll("/+$", "");
        this.authorization = password == null || password.isEmpty() ? null
                : "Basic " + Base64.getEncoder().encodeToString((USER + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.http = new Http(Duration.ofMillis((long) (timeout * 1000)));
        this.events = new EventHub(URI.create(this.url + "/api/event"), authorization, http.client(), sseReadTimeout, 15.0);
        this.reconcileInterval = reconcileInterval;
    }

    // -- Hilfsfunktionen -----------------------------------------------------

    private static Map<String, String> toolStatus() {
        Map<String, String> m = new LinkedHashMap<>();
        String agents = "🤖 Ich starte Recherche-Agenten…";
        String web = "🔍 Ich recherchiere im Web…";
        String page = "🌐 Ich lese eine Webseite…";
        String shell = "⚙️ Ich führe Befehle aus…";
        String edit = "✏️ Ich bearbeite Dateien…";
        String files = "📂 Ich durchsuche Dateien…";
        String grep = "🔎 Ich durchsuche Inhalte…";
        String plan = "📝 Ich plane die nächsten Schritte…";
        m.put("task", agents);
        m.put("agent", agents);
        m.put("websearch", web);
        m.put("web_search", web);
        m.put("webfetch", page);
        m.put("web_fetch", page);
        m.put("bash", shell);
        m.put("shell", shell);
        m.put("read", "📄 Ich sehe mir Dateien an…");
        m.put("write", "✏️ Ich erstelle Dateien…");
        m.put("edit", edit);
        m.put("patch", edit);
        m.put("multiedit", edit);
        m.put("notebookedit", "✏️ Ich bearbeite ein Notebook…");
        m.put("glob", files);
        m.put("list", files);
        m.put("ls", files);
        m.put("grep", grep);
        m.put("codesearch", grep);
        m.put("toolsearch", "🧰 Ich suche passende Werkzeuge…");
        m.put("skill", "🧰 Ich lade eine Fähigkeit…");
        m.put("todowrite", plan);
        m.put("todoread", plan);
        return Map.copyOf(m);
    }

    /** Kurzer Statustext für einen Tool-Aufruf (Fallback: generischer Text). */
    public static String toolStatus(String name) {
        return TOOL_STATUS.getOrDefault(name == null ? "" : name.toLowerCase(), "🔧 Ich arbeite…");
    }

    /** {@code "provider/model"} → {@code Model.Ref} von v2 (Modell-ID darf „/“ enthalten). */
    public static ObjectNode splitModel(String model, String variant) {
        int slash = model.indexOf('/');
        if (slash <= 0 || slash == model.length() - 1) {
            throw new IllegalArgumentException("Modell muss 'provider/modell' sein: '" + model + "'");
        }
        ObjectNode ref = Json.object().put("providerID", model.substring(0, slash)).put("id", model.substring(slash + 1));
        if (variant != null && !variant.isEmpty()) {
            ref.put("variant", variant);
        }
        return ref;
    }

    /** Eine SSE-Zeile → Event; {@code null} für Kommentare/Leerzeilen/Müll. */
    public static JsonNode parseSseLine(String line) {
        if (!line.startsWith("data:")) {
            return null;
        }
        String payload = line.substring(5).strip();
        if (payload.isEmpty()) {
            return null;
        }
        try {
            JsonNode event = Json.parse(payload);
            return event.isObject() ? event : null;
        } catch (JacksonException e) {
            log.warn("SSE-Zeile ist kein JSON: {}", payload.length() > 200 ? payload.substring(0, 200) : payload);
            return null;
        }
    }

    /** Verbindet Textblöcke (Text → Tool → Text) mit Leerzeile. */
    public static String joinBlocks(Iterable<String> blocks) {
        List<String> parts = new ArrayList<>();
        for (String b : blocks) {
            if (b != null && !b.strip().isEmpty()) {
                parts.add(b.strip());
            }
        }
        return String.join("\n\n", parts);
    }

    private static String errorText(JsonNode data) {
        JsonNode err = data.get("error");
        if (err != null && err.isObject()) {
            for (String key : List.of("message", "_tag", "type")) {
                if (Json.truthy(err, key)) {
                    return Json.str(err, key);
                }
            }
            return err.toString();
        }
        if (err != null && !err.isNull() && Json.truthy(data, "error")) {
            return Json.scalar(err);
        }
        return Json.truthy(data, "message") ? Json.str(data, "message") : "unbekannter Fehler";
    }

    // -- Lebenszyklus --------------------------------------------------------

    @Override
    public void close() {
        events.close();
    }

    // -- REST-Grundlagen -----------------------------------------------------

    private Http.Response send(String method, String path, Map<String, String> query, JsonNode body) {
        try {
            return http.send(method, Http.uri(url, path, query), h -> {
                if (authorization != null) {
                    h.set("Authorization", authorization);
                }
            }, body == null ? null : Json.write(body).getBytes(StandardCharsets.UTF_8),
                    body == null ? null : MediaType.APPLICATION_JSON);
        } catch (IOException e) {
            throw new OpencodeException(method + " " + path + ": " + e, e);
        }
    }

    JsonNode request(String method, String path, String sessionId, Map<String, String> query, JsonNode body) {
        Http.Response resp = send(method, path, query, body);
        if (resp.status() == 404 && sessionId != null) {
            throw new SessionGoneException("Session " + sessionId + " existiert nicht");
        }
        if (resp.status() >= 400) {
            String text = resp.text();
            throw new OpencodeException(method + " " + path + ": HTTP " + resp.status() + " "
                    + text.substring(0, Math.min(300, text.length())));
        }
        if (resp.status() == 204 || resp.body().length == 0) {
            return null;
        }
        JsonNode parsed;
        try {
            parsed = Json.parse(resp.text());
        } catch (JacksonException e) {
            throw new OpencodeException(method + " " + path + ": keine JSON-Antwort", e);
        }
        return parsed.isObject() && parsed.has("data") ? parsed.get("data") : parsed;
    }

    /** Roher REST-Aufruf (für Tests und Diagnose), Ergebnis ohne {@code data}-Hülle. */
    public JsonNode request(String method, String path, String sessionId) {
        return request(method, path, sessionId, null, null);
    }

    // -- Sessions ------------------------------------------------------------

    @Override
    public String createSession(String directory, String title, List<Rule> permissions, String sessionId) {
        ObjectNode body = Json.object();
        body.set("location", Json.object().put("directory", directory));
        if (title != null && !title.isEmpty()) {
            body.put("title", title);
        }
        if (permissions != null) {
            body.set("permissions", Json.tree(permissions));
        }
        if (sessionId != null && !sessionId.isEmpty()) {
            body.put("id", sessionId);
        }
        JsonNode data = request("POST", "/api/session", null, null, body);
        if (data == null || !data.isObject() || !data.has("id")) {
            throw new OpencodeException("Unerwartete Antwort beim Anlegen: " + data);
        }
        return Json.str(data, "id");
    }

    /**
     * Hängt dauerhafte Systemanweisungen an die Session (experimentelle v2-API).
     *
     * <p>opencode fügt jeden Eintrag bei jedem Schritt als {@code <context key=…>} an den
     * Systemprompt an (nach der globalen {@code AGENTS.md}); sie überleben daher auch eine
     * Kompaktierung.
     */
    @Override
    public void setInstructions(String sessionId, Map<String, String> entries) {
        entries.forEach((key, value) -> request("PUT",
                "/api/experimental/session/" + sessionId + "/instructions/entries/" + key, sessionId, null,
                Json.object().put("value", value)));
    }

    @Override
    public boolean sessionExists(String sessionId) {
        try {
            request("GET", "/api/session/" + sessionId, sessionId, null, null);
            return true;
        } catch (SessionGoneException e) {
            return false;
        }
    }

    @Override
    public void abort(String sessionId) {
        try {
            request("POST", "/api/session/" + sessionId + "/interrupt", sessionId, null, null);
        } catch (SessionGoneException e) {
            // unbekannt → nichts abzubrechen
        }
    }

    /**
     * Verfügbare Modelle als {@code {id: "provider/modell", name, variants}}.
     *
     * <p>Die Liste hängt in v2 vom Verzeichnis ab (Config-Kaskade); ohne {@code directory} nimmt
     * opencode sein Startverzeichnis. Direkt nach dem Start kann sie noch leer sein
     * (Provider-Discovery läuft asynchron).
     */
    @Override
    public List<ModelInfo> listModels(String directory) {
        Map<String, String> params = directory == null ? null : Map.of("location[directory]", directory);
        List<JsonNode> data = List.of();
        for (int attempt = 0; attempt < 3; attempt++) { // Discovery läuft beim ersten Aufruf an
            data = Json.list(request("GET", "/api/model", null, params, null));
            if (!data.isEmpty() || attempt == 2) {
                break;
            }
            sleeper.accept(1 + attempt);
        }
        List<ModelInfo> models = new ArrayList<>();
        for (JsonNode m : data) {
            JsonNode enabled = m.get("enabled");
            if (enabled != null && enabled.isBoolean() && !enabled.asBoolean()) {
                continue;
            }
            String provider = Json.str(m, "providerID");
            String modelId = Json.truthy(m, "modelID") ? Json.str(m, "modelID") : Json.str(m, "id");
            List<String> variants = new ArrayList<>();
            for (JsonNode v : Json.list(m.get("variants"))) {
                if (Json.truthy(v, "id")) {
                    variants.add(Json.str(v, "id"));
                }
            }
            models.add(new ModelInfo(provider.isEmpty() ? modelId : provider + "/" + modelId,
                    Json.truthy(m, "name") ? Json.str(m, "name") : modelId, variants));
        }
        return models;
    }

    public List<ModelInfo> listModels() {
        return listModels(null);
    }

    public List<JsonNode> ensureMcp(String directory) {
        return ensureMcp(directory, MCP_WAIT, MCP_GRACE, MCP_SETTLE);
    }

    /**
     * Wartet, bis die MCP-Server für {@code directory} verbunden sind.
     *
     * <p>opencode (2.0.20–2.1.2) verbindet MCP-Server pro Location (Verzeichnis) erst beim ersten Zugriff und
     * stellt die Tool-Liste eines Prompts zusammen, bevor die Verbindung steht – ohne diesen Schritt
     * fehlen die MCP-Tools im ersten Prompt jeder neuen Session. {@code GET /api/mcp} stößt die
     * Verbindung an; gewartet wird, solange ein Server {@code pending} ist (höchstens
     * {@code timeout}). Eine frische Location meldet anfangs gar keine Server – darauf wird höchstens
     * {@code grace} Sekunden gewartet, und nur, solange zuletzt überhaupt Server gemeldet wurden (bzw.
     * beim ersten Aufruf bzw. nach einem Neustart von opencode, erkannt am Wiederverbinden des
     * Event-Streams). Ohne MCP-Server kostet das also einmal {@code grace} pro opencode-Start, danach
     * nichts mehr. Musste gewartet werden oder ist das Verzeichnis neu, folgen noch {@code settle}
     * Sekunden, bis opencode die Tools registriert hat. Fehler werden geloggt, nicht geworfen.
     * Rückgabe: Server-Status.
     */
    public List<JsonNode> ensureMcp(String directory, double timeout, double grace, double settle) {
        double start = Clock.monotonic();
        double waitEmpty;
        synchronized (mcpLock) {
            int connects = events.connects.get();
            if (connects > 1 && connects != mcpEpoch) {
                // Event-Stream neu verbunden → opencode wurde (vermutlich) neu gestartet, evtl. mit
                // geänderter MCP-Config: neu lernen.
                mcpSeen = null;
                mcpDirs.clear();
            }
            mcpEpoch = connects;
            waitEmpty = !Boolean.FALSE.equals(mcpSeen) ? grace : 0.0;
        }
        List<JsonNode> servers;
        boolean waited = false;
        while (true) {
            try {
                servers = Json.list(request("GET", "/api/mcp", null, Map.of("location[directory]", directory), null));
            } catch (OpencodeException e) {
                log.debug("MCP-Status für {} nicht abrufbar: {}", directory, e.getMessage());
                return List.of();
            }
            List<String> pending = new ArrayList<>();
            for (JsonNode s : servers) {
                if ("pending".equals(Json.str(s.path("status"), "status"))) {
                    pending.add(Json.str(s, "name"));
                }
            }
            double elapsed = Clock.monotonic() - start;
            if (!servers.isEmpty() && pending.isEmpty()) {
                break;
            }
            if (servers.isEmpty() && elapsed >= waitEmpty) {
                break;
            }
            if (elapsed >= timeout) {
                log.warn("MCP-Server noch nicht verbunden: {}", pending);
                break;
            }
            waited = true;
            sleeper.accept(0.2);
        }
        boolean settleNow;
        synchronized (mcpLock) {
            settleNow = !servers.isEmpty() && (waited || !mcpDirs.contains(directory));
        }
        if (settleNow) {
            // opencode registriert die Tools erst kurz nach "connected" (entprellt) – auch wenn
            // schon das Anlegen der Session die Verbindung angestoßen hat.
            Clock.sleep(settle);
        }
        synchronized (mcpLock) {
            if (!servers.isEmpty()) {
                if (mcpDirs.size() > 10000) {
                    mcpDirs.clear();
                }
                mcpDirs.add(directory);
            }
            mcpSeen = !servers.isEmpty();
        }
        for (JsonNode server : servers) {
            JsonNode status = server.path("status");
            String s = Json.str(status, "status");
            if (!s.equals("connected") && !s.equals("pending")) {
                log.warn("MCP-Server {}: {} {}", Json.str(server, "name"), s, Json.str(status, "error"));
            }
        }
        return servers;
    }

    /** Setzt das Modell der Session ({@code provider/modell}, optional Variante). */
    public void setModel(String sessionId, String model, String variant) {
        request("POST", "/api/session/" + sessionId + "/model", sessionId, null,
                Json.object().set("model", splitModel(model, variant)));
    }

    private JsonNode session(String sessionId) {
        JsonNode s = request("GET", "/api/session/" + sessionId, sessionId, null, null);
        return s == null ? Json.object() : s;
    }

    /**
     * Nachrichten <i>nach</i> {@code userMsgId} in chronologischer Reihenfolge; {@code null}, wenn
     * die User-Nachricht (noch) nicht in der Historie liegt, z. B. weil sie in der Inbox auf
     * Zustellung wartet.
     */
    List<JsonNode> messagesSince(String sessionId, String userMsgId, int page, int maxPages) {
        List<JsonNode> newer = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < maxPages; i++) {
            // Folgeseiten nur per Cursor: opencode lehnt cursor + order mit HTTP 400 ab („Cursor
            // cannot be combined with order“); die Reihenfolge steckt bereits im Cursor.
            Map<String, String> params = new LinkedHashMap<>();
            params.put("limit", String.valueOf(page));
            if (cursor != null) {
                params.put("cursor", cursor);
            } else {
                params.put("order", "desc");
            }
            Http.Response resp;
            try {
                resp = http.send("GET", Http.uri(url, "/api/session/" + sessionId + "/message", params), h -> {
                    if (authorization != null) {
                        h.set("Authorization", authorization);
                    }
                }, null, null);
            } catch (IOException e) {
                throw new OpencodeException("Nachrichten von " + sessionId + ": " + e, e);
            }
            if (resp.status() == 404) {
                throw new SessionGoneException("Session " + sessionId + " existiert nicht");
            }
            if (resp.status() >= 400) {
                throw new OpencodeException("Nachrichten von " + sessionId + ": HTTP " + resp.status());
            }
            JsonNode body = Json.parse(resp.text());
            List<JsonNode> data = Json.list(body.get("data"));
            for (JsonNode msg : data) {
                if (Json.str(msg, "id").equals(userMsgId)) {
                    Collections.reverse(newer);
                    return newer;
                }
                newer.add(msg);
            }
            cursor = Json.str(body.path("cursor"), "next");
            if (cursor.isEmpty() || data.isEmpty()) {
                break;
            }
        }
        return null;
    }

    /** Stand der Ausführung nach einer User-Nachricht laut Historie. */
    record State(boolean found, String outcome, String text, String error) {
    }

    /**
     * Stand der Ausführung nach {@code userMsgId} laut Historie. {@code found} sagt, ob die eigene
     * User-Nachricht schon in der Historie steht (also zugestellt ist); {@code outcome} ist
     * {@code null}, solange danach keine {@code idle}-Nachricht existiert. Ausgewertet wird nur bis
     * zum <i>ersten</i> {@code idle} – spätere Nachrichten gehören zu Folge-Ausführungen.
     */
    State stateAfter(String sessionId, String userMsgId) {
        List<JsonNode> msgs = messagesSince(sessionId, userMsgId, 50, 10);
        if (msgs == null) {
            return new State(false, null, "", "");
        }
        List<String> texts = new ArrayList<>();
        String error = "";
        String outcome = null;
        for (JsonNode msg : msgs) {
            String type = Json.str(msg, "type");
            if (type.equals("assistant")) {
                for (JsonNode p : Json.list(msg.get("content"))) {
                    if (Json.str(p, "type").equals("text")) {
                        texts.add(Json.str(p, "text"));
                    }
                }
                if (Json.truthy(msg, "error")) {
                    error = errorText(msg);
                }
            } else if (type.equals("idle")) {
                outcome = Json.truthy(msg, "outcome") ? Json.str(msg, "outcome") : "succeeded";
                break;
            }
        }
        return new State(true, outcome, joinBlocks(texts), error);
    }

    // -- Prompt --------------------------------------------------------------

    /**
     * Schickt {@code text} an die Session und wartet auf das Ende der Ausführung.
     *
     * <p>{@code onDelta} bekommt jeweils den <b>gesamten</b> bisherigen Antworttext (mehrere
     * Textblöcke mit Leerzeile verbunden), {@code onStatus} einen Statustext pro Tool-Aufruf.
     * Rückgabe ist der finale Text. {@code model}/{@code variant} werden vorher per
     * {@link #setModel} gesetzt, falls sie vom aktuellen Modell der Session abweichen. Vorher wartet
     * {@link #ensureMcp} auf die MCP-Server des Verzeichnisses.
     */
    @Override
    public String prompt(String sessionId, String text, PromptOptions options) {
        ensureMcp(options.directory());
        if (options.model() != null && !options.model().isEmpty()) {
            JsonNode current = session(sessionId).path("model");
            ObjectNode wanted = splitModel(options.model(), options.variant());
            boolean differs = false;
            for (Map.Entry<String, JsonNode> e : wanted.properties()) {
                differs |= !Json.str(current, e.getKey()).equals(e.getValue().asString()) || !current.has(e.getKey());
            }
            if (differs) {
                setModel(sessionId, options.model(), options.variant());
            }
        }

        ObjectNode body = Json.object().put("text", text);
        if (options.files() != null && !options.files().isEmpty()) {
            ArrayNode files = body.putArray("files");
            options.files().forEach(f -> files.add(fileRef(f, options.directory())));
        }

        BlockingQueue<JsonNode> q = events.subscribe(sessionId); // vor dem Absenden!
        try {
            JsonNode data;
            try {
                data = request("POST", "/api/session/" + sessionId + "/prompt", sessionId, null, body);
            } catch (SessionGoneException e) {
                throw e;
            } catch (OpencodeException e) {
                if (String.valueOf(e.getMessage()).contains("ENOENT")) {
                    throw new OpencodeException(
                            "Arbeitsverzeichnis der Session fehlt im opencode-Container: " + e.getMessage(), e);
                }
                throw e;
            }
            String inboxId = data == null ? "" : Json.str(data, "id");
            return follow(sessionId, q, inboxId, options.onDelta(), options.onStatus(), options.idleTimeout());
        } finally {
            events.unsubscribe(sessionId, q);
        }
    }

    /** Dateipfad (Container-Sicht) → {@code PromptInput.FileAttachment}. */
    static ObjectNode fileRef(String path, String directory) {
        if (path.contains("://")) {
            return Json.object().put("uri", path).put("name", basename(path));
        }
        String full = path.startsWith("/") ? path : (directory.endsWith("/") ? directory : directory + "/") + path;
        return Json.object().put("uri", "file://" + full).put("name", basename(full));
    }

    private static String basename(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static void emit(Consumer<String> cb, String value) {
        if (cb == null) {
            return;
        }
        try {
            cb.accept(value);
        } catch (RuntimeException e) { // Callback-Fehler nicht durchreichen
            log.error("Callback-Fehler in opencode.prompt", e);
        }
    }

    /** Sammelt Textblöcke einer Ausführung in Reihenfolge ihres Auftretens. */
    private static final class Answer {
        private final Map<String, String> blocks = new LinkedHashMap<>();

        void delta(String key, String text) {
            blocks.merge(key, text, String::concat);
        }

        void set(String key, String text) {
            blocks.put(key, text);
        }

        void clear() {
            blocks.clear();
        }

        String text() {
            return joinBlocks(blocks.values());
        }
    }

    private static String blockKey(JsonNode data) {
        return Json.str(data, "assistantMessageID") + "\u0000" + Json.lng(data, "ordinal");
    }

    private String follow(String sessionId, BlockingQueue<JsonNode> q, String inboxId,
            Consumer<String> onDelta, Consumer<String> onStatus, double idleTimeout) {
        Answer answer = new Answer();
        boolean delivered = inboxId.isEmpty(); // ohne ID nicht auf Zustellung warten
        boolean gap = false; // Stream riss während der Ausführung → Text per REST
        double lastEvent = Clock.monotonic();
        double lastCheck = lastEvent;
        String lastError = "";
        String sent = ""; // zuletzt an onDelta gemeldeter Text

        while (true) {
            double now = Clock.monotonic();
            double wait = Math.min(idleTimeout - (now - lastEvent), reconcileInterval - (now - lastCheck));
            JsonNode event;
            try {
                event = q.poll((long) (Math.max(wait, 0.01) * 1000), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OpencodeException("Warten auf opencode unterbrochen", e);
            }

            if (event == null || EventHub.RECONNECTED.equals(Json.str(event, "type"))) {
                // Stille oder Stream-Abriss: Stand per REST abgleichen.
                if (event != null) {
                    gap = true;
                }
                lastCheck = Clock.monotonic();
                Reconciled r = reconcile(sessionId, inboxId);
                if (r.found() && !delivered) {
                    // Zustellungs-Event verpasst: die Nachricht steht schon in der Historie. Text
                    // notfalls per REST (gap).
                    delivered = true;
                    gap = true;
                    answer.clear();
                }
                if (r.done() != null) {
                    State done = r.done();
                    return result(done.outcome(), done.text().isEmpty() ? answer.text() : done.text(),
                            done.error().isEmpty() ? lastError : done.error());
                }
                if (event == null && Clock.monotonic() - lastEvent >= idleTimeout) {
                    abort(sessionId);
                    throw new OpencodeException(
                            "Keine Rückmeldung von opencode seit " + Math.round(idleTimeout) + " s – abgebrochen");
                }
                continue;
            }

            lastEvent = Clock.monotonic();
            String type = Json.str(event, "type");
            JsonNode data = event.path("data");

            switch (type) {
                case "session.inbox.delivered" -> {
                    if (Json.str(data, "inboxID").equals(inboxId)) {
                        delivered = true;
                        answer.clear(); // evtl. Text einer vorherigen Ausführung
                    }
                }
                case "session.text.delta" -> {
                    answer.delta(blockKey(data), Json.str(data, "delta"));
                    sent = emitText(answer, delivered, sent, onDelta);
                }
                case "session.text.ended" -> {
                    JsonNode t = data.get("text");
                    if (t != null && t.isString()) {
                        answer.set(blockKey(data), t.asString());
                        sent = emitText(answer, delivered, sent, onDelta);
                    }
                }
                case "session.tool.input.started" -> { // nur hier steht der Name
                    if (delivered && Json.truthy(data, "name")) {
                        emit(onStatus, toolStatus(Json.str(data, "name")));
                    }
                }
                case "session.error", "session.step.failed" -> {
                    lastError = errorText(data);
                    log.warn("opencode meldet Fehler in {}: {}", sessionId, lastError);
                }
                case "session.deleted" -> throw new SessionGoneException("Session " + sessionId + " wurde gelöscht");
                default -> {
                    if (!TERMINAL.contains(type)) {
                        break;
                    }
                    if (!delivered) {
                        // Ende einer älteren Ausführung; unsere steht noch aus.
                        answer.clear();
                        break;
                    }
                    String outcome = type.substring(type.lastIndexOf('.') + 1);
                    if (Json.truthy(data, "error")) {
                        lastError = errorText(data);
                    }
                    String result = answer.text();
                    if ((gap || result.isEmpty() || outcome.equals("failed")) && !inboxId.isEmpty()) {
                        // Deltas verpasst oder Fehlerdetails gesucht: Historie lesen.
                        try {
                            State rest = stateAfter(sessionId, inboxId);
                            result = rest.text().isEmpty() ? result : rest.text();
                            lastError = lastError.isEmpty() ? rest.error() : lastError;
                        } catch (OpencodeException e) {
                            log.warn("Antwort nicht nachladbar: {}", e.getMessage());
                        }
                    }
                    return result(outcome, result, lastError);
                }
            }
        }
    }

    private static String emitText(Answer answer, boolean delivered, String sent, Consumer<String> onDelta) {
        String text = answer.text();
        if (delivered && !text.equals(sent)) {
            emit(onDelta, text);
            return text;
        }
        return sent;
    }

    private record Reconciled(boolean found, State done) {
    }

    /** (zugestellt, Endstand oder {@code null}) laut REST. */
    private Reconciled reconcile(String sessionId, String inboxId) {
        if (inboxId.isEmpty()) {
            return new Reconciled(false, null);
        }
        State state;
        try {
            state = stateAfter(sessionId, inboxId);
        } catch (SessionGoneException e) {
            throw e;
        } catch (OpencodeException | JacksonException e) {
            log.warn("Abgleich für {} fehlgeschlagen: {}", sessionId, e.getMessage());
            return new Reconciled(false, null);
        }
        if (state.outcome() == null) {
            return new Reconciled(state.found(), null);
        }
        log.info("Ausführung in {} per REST als beendet erkannt ({})", sessionId, state.outcome());
        return new Reconciled(state.found(), state);
    }

    private static String result(String outcome, String text, String error) {
        if (outcome.equals("failed")) {
            throw new OpencodeException("opencode-Ausführung fehlgeschlagen: " + (error.isEmpty() ? "ohne Details" : error));
        }
        if (outcome.equals("interrupted")) {
            throw new PromptInterruptedException("opencode-Ausführung abgebrochen");
        }
        return text;
    }
}
