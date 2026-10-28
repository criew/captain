package com.ewerlin.captain.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.ewerlin.captain.opencode.SessionPermissions;
import com.ewerlin.captain.policy.SharedDir;
import com.ewerlin.captain.policy.Webfetch;
import com.ewerlin.captain.util.Json;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Lädt die Konfiguration.
 *
 * <p>Werte kommen aus der Umgebung (im Betrieb Spring {@code Environment}: Umgebungsvariablen,
 * Systemeigenschaften), optional ergänzt um eine JSON-Datei (Pfad in {@code CAPTAIN_CONFIG}). Die
 * Umgebung gewinnt immer gegen die Datei. Die JSON-Datei benutzt dieselben Schlüssel wie die
 * Umgebung, z. B. {@code {"MM_URL": "http://localhost:8065", "MAX_ATTACHMENT_MB": 50}}.
 *
 * <p>Persona/Systemnachricht: {@code CAPTAIN_SYSTEM_PROMPT_FILE} (Pfad zu einer Textdatei) hat
 * Vorrang vor {@code CAPTAIN_SYSTEM_PROMPT} (Text); ohne beides gilt {@link #DEFAULT_SYSTEM_PROMPT}.
 */
public final class ConfigLoader {

    public static final String CONFIG_ENV = "CAPTAIN_CONFIG";
    static final int API_TOKEN_MIN = 24;

    static final List<String> REQUIRED = List.of("MM_URL", "MM_BOT_TOKEN", "OPENCODE_URL");
    static final List<String> OPTIONAL = List.of(
            "OPENCODE_PASSWORD", "OPENCODE_MODEL", "OPENCODE_VARIANT",
            "CAPTAIN_SYSTEM_PROMPT", "CAPTAIN_SYSTEM_PROMPT_FILE", "CAPTAIN_WEBFETCH_ALLOW",
            "CAPTAIN_SHARED_DIR", "CAPTAIN_HOME", "CAPTAIN_API_TOKEN", "CAPTAIN_REST_AGENTS",
            "CAPTAIN_AGENT_TOOLS");
    public static final Map<String, String> DEFAULTS = defaults();
    public static final String DEFAULT_SYSTEM_PROMPT =
            "Du bist **Captain**, ein hilfsbereiter, sachlicher Assistent.";
    static final List<String> KEYS = Stream.of(REQUIRED, OPTIONAL, List.copyOf(DEFAULTS.keySet()))
            .flatMap(List::stream).toList();

    private static final Pattern DURATION = Pattern.compile("\\s*(\\d+(?:\\.\\d+)?)\\s*([smhd]?)\\s*");
    private static final Map<String, Integer> UNITS = Map.of("", 1, "s", 1, "m", 60, "h", 3600, "d", 86400);
    private static final Pattern FLOAT = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");
    private static final Pattern INT = Pattern.compile("[+-]?\\d+");
    /** Name eines opencode-Agenten ({@code CAPTAIN_REST_AGENTS}). */
    public static final Pattern AGENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    /** Tool-Name ({@code <server>_<tool>}), optional mit {@code *} am Ende. */
    private static final Pattern TOOL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\\*?");

    private ConfigLoader() {
    }

    private static Map<String, String> defaults() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("SESSIONS_DIR", "/tmp/captain");
        d.put("DATA_DIR", "data");
        // Verpasste Posts höchstens so alt nachholen: Sekunden oder mit s/m/h/d; 0 = nichts
        d.put("CATCHUP_MAX_AGE", "24h");
        // Sessions so lange unbenutzt → opencode-Session und Verzeichnis löschen; 0 = nie
        d.put("SESSION_MAX_IDLE", "14d");
        // Größere Anhänge werden nicht geladen (MB, Dezimalzahl erlaubt; 0 = keine Grenze)
        d.put("MAX_ATTACHMENT_MB", "20");
        // Vorgeschichte einer neuen Kanal-Unterhaltung (Top-Level-Erwähnung):
        // höchstens so viele Posts und Zeichen, die neuesten gewinnen.
        d.put("HISTORY_MAX_POSTS", "50");
        d.put("HISTORY_MAX_CHARS", "8000");
        return Map.copyOf(d);
    }

    /** Lädt aus {@code env}; Datei aus {@code path} bzw. {@code CAPTAIN_CONFIG}. */
    public static CaptainConfig load(String path, Function<String, String> env) {
        if (path == null || path.isEmpty()) {
            path = env.apply(CONFIG_ENV);
        }
        Map<String, Object> values = new LinkedHashMap<>(DEFAULTS);
        if (path != null && !path.isEmpty()) {
            values.putAll(readFile(path));
        }
        for (String key : KEYS) {
            String value = env.apply(key);
            if (value != null && !value.isEmpty()) {
                values.put(key, value);
            }
        }

        List<String> missing = REQUIRED.stream().filter(k -> !truthy(values.get(k))).toList();
        if (!missing.isEmpty()) {
            throw new ConfigException("Pflichtwerte fehlen: " + String.join(", ", missing));
        }

        String password = text(values.get("OPENCODE_PASSWORD"));
        if (password.isEmpty()) {
            // Fallback: dieselbe .env wie der opencode-Container (infra/opencode/.env)
            password = env.apply("OPENCODE_SERVER_PASSWORD");
        }
        return new CaptainConfig(
                rstrip(text(values.get("MM_URL"))),
                text(values.get("MM_BOT_TOKEN")),
                rstrip(text(values.get("OPENCODE_URL"))),
                emptyToNull(password),
                emptyToNull(text(values.get("OPENCODE_MODEL"))),
                emptyToNull(text(values.get("OPENCODE_VARIANT"))),
                sessionsDir(values.get("SESSIONS_DIR")),
                text(values.get("DATA_DIR")),
                systemPrompt(values),
                parseDuration(values.get("CATCHUP_MAX_AGE")),
                parseMb(values.get("MAX_ATTACHMENT_MB")),
                count(values, "HISTORY_MAX_POSTS"),
                count(values, "HISTORY_MAX_CHARS"),
                webfetch(values.get("CAPTAIN_WEBFETCH_ALLOW")),
                shared(values.get("CAPTAIN_SHARED_DIR"), values.get("CAPTAIN_HOME")),
                apiToken(values.get("CAPTAIN_API_TOKEN")),
                parseDuration(values.get("SESSION_MAX_IDLE")),
                restAgents(values.get("CAPTAIN_REST_AGENTS"), values.get("CAPTAIN_AGENT_TOOLS")));
    }

    public static CaptainConfig load(Function<String, String> env) {
        return load(null, env);
    }

    private static Map<String, Object> readFile(String path) {
        JsonNode data;
        try {
            data = Json.parse(Files.readString(Path.of(path), StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            throw new ConfigException("Konfigurationsdatei fehlt: " + path);
        } catch (IOException e) {
            throw new ConfigException("Konfigurationsdatei " + path + " nicht lesbar: " + e.getMessage());
        } catch (JacksonException e) {
            throw new ConfigException("Konfiguration " + path + " ist kein gültiges JSON: " + e.getOriginalMessage());
        }
        if (!data.isObject()) {
            throw new ConfigException("Konfiguration " + path + " muss ein JSON-Objekt sein.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> unknown = new TreeSet<>();
        for (Map.Entry<String, JsonNode> e : data.properties()) {
            if (!KEYS.contains(e.getKey())) {
                unknown.add(e.getKey());
            }
            JsonNode v = e.getValue();
            out.put(e.getKey(), v.isString() ? v.asString() : v);
        }
        if (!unknown.isEmpty()) {
            throw new ConfigException("Unbekannte Schlüssel in " + path + ": " + String.join(", ", unknown));
        }
        return out;
    }

    /** Wie Pythons {@code str(value)} für Konfigurationswerte. */
    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof JsonNode node) {
            if (node.isNull()) {
                return "";
            }
            if (node.isBoolean()) {
                return node.asBoolean() ? "True" : "False";
            }
            return node.isValueNode() ? node.asString() : node.toString();
        }
        return value.toString();
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof JsonNode node) {
            if (node.isNull()) {
                return false;
            }
            if (node.isBoolean()) {
                return node.asBoolean();
            }
            if (node.isNumber()) {
                return node.asDouble() != 0;
            }
            return !node.isEmpty();
        }
        return !value.toString().isEmpty();
    }

    private static boolean isBool(Object value) {
        return value instanceof JsonNode node && node.isBoolean();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String rstrip(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '/') {
            end--;
        }
        return text.substring(0, end);
    }

    /** {@code "24h"}, {@code "90m"}, {@code "3600"}, {@code 3600} → Dauer (≥ 0). */
    public static Duration parseDuration(Object value) {
        String text = isBool(value) ? "" : text(value).toLowerCase(Locale.ROOT);
        Matcher m = DURATION.matcher(text);
        if (!m.matches()) {
            throw new ConfigException("Ungültige Dauer: " + repr(value) + " (z. B. 3600, 90m, 24h, 2d)");
        }
        double seconds = Double.parseDouble(m.group(1)) * UNITS.get(m.group(2));
        return Duration.ofNanos(Math.round(seconds * 1e9));
    }

    private static double parseMb(Object value) {
        String text = isBool(value) ? "" : text(value).strip().replace(",", ".");
        double mb = FLOAT.matcher(text).matches() ? Double.parseDouble(text) : -1.0;
        if (!(mb >= 0) || Double.isInfinite(mb)) {
            throw new ConfigException("MAX_ATTACHMENT_MB muss eine Zahl ≥ 0 sein: " + repr(value));
        }
        return mb;
    }

    /** REST-Token: leer = REST aus; sonst mindestens {@value #API_TOKEN_MIN} Zeichen ohne Leerraum. */
    private static String apiToken(Object value) {
        String token = text(value).strip();
        if (token.isEmpty()) {
            return null;
        }
        if (token.length() < API_TOKEN_MIN || token.chars().anyMatch(Character::isWhitespace)) {
            throw new ConfigException("CAPTAIN_API_TOKEN muss mindestens " + API_TOKEN_MIN
                    + " Zeichen ohne Leerzeichen haben (z. B. openssl rand -hex 32)");
        }
        return token;
    }

    /**
     * {@code CAPTAIN_REST_AGENTS} (kommagetrennte Agent-Namen) und {@code CAPTAIN_AGENT_TOOLS}
     * ({@code agent: tool tool; agent2: tool}, Tools durch Komma oder Leerzeichen getrennt) →
     * Agent → vorbehaltene Tools.
     */
    static Map<String, List<String>> restAgents(Object agents, Object tools) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String name : text(agents).split(",")) {
            name = name.strip();
            if (name.isEmpty()) {
                continue;
            }
            if (!AGENT.matcher(name).matches()) {
                throw new ConfigException("CAPTAIN_REST_AGENTS: ungültiger Agent-Name " + repr(name)
                        + " (erlaubt: A-Z a-z 0-9 _ -)");
            }
            out.put(name, new ArrayList<>());
        }
        for (String part : text(tools).split(";")) {
            if (part.isBlank()) {
                continue;
            }
            int colon = part.indexOf(':');
            String name = colon < 0 ? "" : part.substring(0, colon).strip();
            if (!out.containsKey(name)) {
                throw new ConfigException("CAPTAIN_AGENT_TOOLS: " + repr(part.strip())
                        + " – erwartet \"<agent>: <tool> …\" mit einem Agenten aus CAPTAIN_REST_AGENTS");
            }
            for (String tool : part.substring(colon + 1).split("[,\\s]+")) {
                if (tool.isEmpty()) {
                    continue;
                }
                if (!TOOL.matcher(tool).matches() || SessionPermissions.reserved(tool)) {
                    throw new ConfigException("CAPTAIN_AGENT_TOOLS: Tool " + repr(tool) + " nicht erlaubt"
                            + " (nur MCP-Tools <server>_<tool>, keine eingebauten Aktionen)");
                }
                if (!out.get(name).contains(tool)) {
                    out.get(name).add(tool);
                }
            }
        }
        return out;
    }

    private static String systemPrompt(Map<String, Object> values) {
        String path = text(values.get("CAPTAIN_SYSTEM_PROMPT_FILE"));
        String text;
        if (!path.isEmpty()) {
            try {
                text = Files.readString(Path.of(path), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new ConfigException("CAPTAIN_SYSTEM_PROMPT_FILE nicht lesbar: " + e);
            }
        } else {
            text = text(values.get("CAPTAIN_SYSTEM_PROMPT")).strip();
        }
        return text.isEmpty() ? DEFAULT_SYSTEM_PROMPT : text;
    }

    /** Absoluter Pfad, nicht die Wurzel – der Bot löscht darunter Verzeichnisse. */
    private static String sessionsDir(Object value) {
        String raw = text(value).strip();
        String path = raw.isEmpty() ? "" : rstrip(normpath(raw.replace("\\", "/")));
        boolean absolute = raw.startsWith("/") || isNativeAbsolute(raw);
        if (!absolute || path.isEmpty() || path.endsWith(":") || dirname(path).equals(path)) {
            throw new ConfigException("SESSIONS_DIR muss ein absoluter Pfad unterhalb von / sein: " + repr(raw));
        }
        return path;
    }

    private static boolean isNativeAbsolute(String raw) {
        try {
            return Path.of(raw).isAbsolute();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@code posixpath.normpath}. */
    static String normpath(String path) {
        if (path.isEmpty()) {
            return ".";
        }
        int initial = path.startsWith("/") ? 1 : 0;
        // POSIX: genau zwei führende Schrägstriche bleiben erhalten
        if (initial == 1 && path.startsWith("//") && !path.startsWith("///")) {
            initial = 2;
        }
        List<String> out = new ArrayList<>();
        for (String comp : path.split("/")) {
            if (comp.isEmpty() || comp.equals(".")) {
                continue;
            }
            if (!comp.equals("..") || (initial == 0 && out.isEmpty()) || (!out.isEmpty() && out.getLast().equals(".."))) {
                out.add(comp);
            } else if (!out.isEmpty()) {
                out.removeLast();
            }
        }
        String joined = "/".repeat(initial) + String.join("/", out);
        return joined.isEmpty() ? "." : joined;
    }

    /** {@code posixpath.dirname}. */
    static String dirname(String path) {
        int i = path.lastIndexOf('/') + 1;
        String head = path.substring(0, i);
        if (!head.isEmpty() && !head.chars().allMatch(c -> c == '/')) {
            head = rstrip(head);
        }
        return head;
    }

    private static List<String> webfetch(Object value) {
        String text;
        if (value instanceof JsonNode node && node.isArray()) {
            List<String> parts = new ArrayList<>();
            node.forEach(n -> parts.add(text(n.isString() ? n.asString() : n)));
            text = String.join(",", parts);
        } else {
            text = value == null ? null : text(value);
        }
        try {
            return Webfetch.parse(text);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(e.getMessage());
        }
    }

    private static String shared(Object value, Object home) {
        try {
            return SharedDir.normalize(value == null ? null : text(value), home == null ? null : text(home));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(e.getMessage());
        }
    }

    /** Ganzzahl ≥ 0 (auch aus JSON als Zahl). */
    private static int count(Map<String, Object> values, String key) {
        Object raw = values.get(key);
        String text = text(raw).strip();
        if (!INT.matcher(text).matches()) {
            throw new ConfigException(key + " muss eine ganze Zahl sein: " + repr(raw));
        }
        long value = Long.parseLong(text);
        if (value < 0) {
            throw new ConfigException(key + " darf nicht negativ sein: " + repr(raw));
        }
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    private static String repr(Object value) {
        if (value instanceof String s) {
            return "'" + s + "'";
        }
        return text(value);
    }
}
