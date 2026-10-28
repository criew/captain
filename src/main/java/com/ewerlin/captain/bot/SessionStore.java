package com.ewerlin.captain.bot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ewerlin.captain.util.Clock;
import com.ewerlin.captain.util.Json;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Session-Store: Session-Key → opencode-Session plus Einstellungen.
 *
 * <p>Eintrag: {@code {opencode_session_id, directory, last_used, model, variant, expired_at}};
 * fehlende Felder werden weggelassen. Modell und Variante hängen am Key, nicht an der Session –
 * {@link #reset} behält sie. {@code last_used} (ms seit der Epoche) ist die letzte Nutzung der
 * Session, {@code expired_at} markiert eine vom Janitor aufgeräumte Session ({@link #expire}).
 * {@code directory} ist {@code <SESSIONS_DIR>/<opencode_session_id>} (z. B.
 * {@code /tmp/captain/ses_…}); die ID vergibt der Bot ({@link SessionKeys#newSessionId}).
 *
 * <p>Der Store hält die Daten im Speicher und schreibt nach jeder Änderung atomar (Temp-Datei +
 * Ersetzen) als JSON ({@code DATA_DIR/sessions.json}).
 */
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    public static final List<String> SESSION_FIELDS = List.of("opencode_session_id", "directory", "last_used");
    public static final String EXPIRED_AT = "expired_at";
    public static final List<String> SETTING_FIELDS = List.of("model", "variant");

    private final Path path;
    private final Object lock = new Object();
    private Map<String, Map<String, String>> data;
    /** Ersetzen der Datei (in Tests austauschbar). */
    AtomicFile.Mover mover = AtomicFile.REPLACE;

    public SessionStore(Path path) {
        this.path = path;
        this.data = load();
    }

    private Map<String, Map<String, String>> load() {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return new HashMap<>();
        } catch (IOException e) {
            throw new UncheckedIOException("Session-Datei " + path + " nicht lesbar", e);
        }
        JsonNode raw;
        try {
            raw = Json.parse(text);
        } catch (JacksonException e) {
            Path broken = Path.of(path + ".corrupt");
            log.warn("Session-Datei {} unlesbar ({}), verschoben nach {}", path, e.getOriginalMessage(), broken);
            try {
                Files.move(path, broken, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException io) {
                throw new UncheckedIOException(io);
            }
            return new HashMap<>();
        }
        Map<String, Map<String, String>> out = new HashMap<>();
        if (raw == null || !raw.isObject()) {
            return out;
        }
        int migrated = 0;
        for (Map.Entry<String, JsonNode> e : raw.properties()) {
            if (!e.getValue().isObject()) {
                continue;
            }
            Map<String, String> entry = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> f : e.getValue().properties()) {
                entry.put(f.getKey(), Json.scalar(f.getValue()));
            }
            String key = SessionKeys.migrate(e.getKey());
            if (!key.equals(e.getKey())) {
                migrated++;
                if (raw.has(key)) {
                    continue; // neuer Key schon vorhanden: gewinnt
                }
            }
            out.put(key, entry);
        }
        if (migrated > 0) {
            log.info("Session-Datei {}: {} alte Keys (dm:/ch:/th:) auf matter-… umgestellt", path, migrated);
            try {
                commit(out);
            } catch (UncheckedIOException e) { // spätere Änderungen schreiben es mit
                log.warn("Session-Datei {}: Umstellung nicht gespeichert: {}", path, e.getMessage());
            }
        }
        return out;
    }

    /** Schreibt {@code next} und übernimmt es erst danach in den Speicher. */
    private void commit(Map<String, Map<String, String>> next) {
        Map<String, Map<String, String>> sorted = new TreeMap<>();
        next.forEach((k, v) -> sorted.put(k, new TreeMap<>(v)));
        try {
            AtomicFile.write(path, Json.PRETTY.writeValueAsString(sorted), ".sessions-", mover);
        } catch (IOException e) {
            throw new UncheckedIOException("Session-Datei " + path + " nicht schreibbar", e);
        }
        data = next;
    }

    private Map<String, Map<String, String>> copy() {
        Map<String, Map<String, String>> out = new HashMap<>();
        data.forEach((k, v) -> out.put(k, new LinkedHashMap<>(v)));
        return out;
    }

    /** Kopie des Eintrags oder {@code null}. */
    public Map<String, String> get(String key) {
        synchronized (lock) {
            Map<String, String> entry = data.get(key);
            return entry == null ? null : new LinkedHashMap<>(entry);
        }
    }

    /** Alle Keys (Kopie). */
    public List<String> keys() {
        synchronized (lock) {
            return List.copyOf(data.keySet());
        }
    }

    /** Setzt die opencode-Session des Keys (zuletzt genutzt: jetzt); Einstellungen bleiben erhalten. */
    public Map<String, String> set(String key, String opencodeSessionId, String directory) {
        return set(key, opencodeSessionId, directory, Clock.nowMs());
    }

    public Map<String, String> set(String key, String opencodeSessionId, String directory, long nowMs) {
        synchronized (lock) {
            Map<String, Map<String, String>> next = copy();
            Map<String, String> entry = next.computeIfAbsent(key, k -> new LinkedHashMap<>());
            entry.put("opencode_session_id", opencodeSessionId);
            entry.put("directory", directory);
            entry.put("last_used", Long.toString(nowMs));
            entry.remove(EXPIRED_AT);
            commit(next);
            return new LinkedHashMap<>(entry);
        }
    }

    /** Merkt die Nutzung der Session des Keys; ohne Session nichts. */
    public void touch(String key, long nowMs) {
        synchronized (lock) {
            Map<String, String> entry = data.get(key);
            if (entry == null || entry.get("opencode_session_id") == null) {
                return;
            }
            Map<String, Map<String, String>> next = copy();
            next.get(key).put("last_used", Long.toString(nowMs));
            commit(next);
        }
    }

    /** Letzte Nutzung (ms) oder {@code null} (keine Session bzw. Alteintrag ohne Feld). */
    public static Long lastUsed(Map<String, String> entry) {
        String value = entry == null ? null : entry.get("last_used");
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Vergisst die Session des Keys (z. B. {@code !neu}), behält Modell und Variante. */
    public void reset(String key) {
        synchronized (lock) {
            if (!data.containsKey(key)) {
                return;
            }
            Map<String, Map<String, String>> next = copy();
            Map<String, String> entry = next.get(key);
            SESSION_FIELDS.forEach(entry::remove);
            entry.remove(EXPIRED_AT);
            if (entry.isEmpty()) {
                next.remove(key);
            }
            commit(next);
        }
    }

    /**
     * Wie {@link #reset}, aber durch den Janitor; mit {@code mark} bleibt {@code expired_at} stehen
     * (die nächste Session holt dann den bisherigen Verlauf als Kontext, siehe {@code Bot}).
     */
    public void expire(String key, long nowMs, boolean mark) {
        synchronized (lock) {
            if (!data.containsKey(key)) {
                return;
            }
            Map<String, Map<String, String>> next = copy();
            Map<String, String> entry = next.get(key);
            SESSION_FIELDS.forEach(entry::remove);
            entry.remove(EXPIRED_AT);
            if (mark) {
                entry.put(EXPIRED_AT, Long.toString(nowMs));
            }
            if (entry.isEmpty()) {
                next.remove(key);
            }
            commit(next);
        }
    }

    /** Setzt {@code model}/{@code variant}; {@code null} als Wert entfernt ihn. */
    public Map<String, String> setSetting(String key, Map<String, String> values) {
        for (String name : values.keySet()) {
            if (!SETTING_FIELDS.contains(name)) {
                throw new IllegalArgumentException("Unbekannte Einstellung: " + name);
            }
        }
        synchronized (lock) {
            Map<String, Map<String, String>> next = copy();
            Map<String, String> entry = next.computeIfAbsent(key, k -> new LinkedHashMap<>());
            values.forEach((name, value) -> {
                if (value == null) {
                    entry.remove(name);
                } else {
                    entry.put(name, value);
                }
            });
            Map<String, String> result = new LinkedHashMap<>(entry);
            if (entry.isEmpty()) {
                next.remove(key);
            }
            commit(next);
            return result;
        }
    }

    /** Modell und Variante setzen ({@code null} entfernt). */
    public Map<String, String> setModel(String key, String model, String variant) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("model", model);
        values.put("variant", variant);
        return setSetting(key, values);
    }
}
