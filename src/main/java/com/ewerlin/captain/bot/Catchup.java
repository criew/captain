package com.ewerlin.captain.bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ewerlin.captain.util.Json;

import tools.jackson.databind.JsonNode;

/**
 * Verpasste Posts nachholen – reine Hilfsfunktionen (ohne Netz testbar).
 *
 * <p>Ziel: Nach einem Neustart oder einer WebSocket-Lücke geht keine an Captain gerichtete Nachricht
 * verloren, und keine wird doppelt beantwortet.
 *
 * <h2>Ablauf (siehe {@link Bot#catchUp})</h2>
 * <ol>
 * <li>Der WebSocket verbindet zuerst; ab dem {@code hello} einer <i>neuen</i> Verbindung (Start oder
 * Reconnect ohne Replay) puffert der Bot alle Live-Posts.</li>
 * <li>Für jeden Kanal des Bots (Team-Kanäle, DMs, Gruppen-DMs; übersprungen, wenn
 * {@code last_post_at} nicht nach dem Cursor liegt) werden die Posts seit dem Cursor per REST
 * geladen – höchstens {@code CATCHUP_MAX_AGE} alt –, über alle Kanäle chronologisch sortiert und
 * durch dieselbe Entscheidungslogik geschickt wie Live-Posts ({@code Bot.addressed} →
 * {@code shouldAnswer}). Übersprungen wird, was schon beantwortet ist: gespeicherte Post-IDs, dazu
 * die IDs, die Captains fertige Antworten in {@code props.captain_answers} tragen
 * ({@link #answeredIn} – deckt auch einen Absturz ab, bevor der Zustand gespeichert war). Antworten
 * bekommen {@link #LATE_NOTE}.</li>
 * <li>Danach wird der Puffer abgearbeitet. Überschneidungen fallen über die Post-ID heraus. So
 * entsteht keine Lücke zwischen Nachholen und Live-Stream.</li>
 * <li>Scheitert das Nachholen (ganz oder für einzelne Kanäle), bleiben die betroffenen Cursor
 * eingefroren, Live-Posts laufen weiter, und das Nachholen wird mit Backoff wiederholt.</li>
 * </ol>
 *
 * <p>Erster Start (keine Cursor-Datei): alle Kanäle auf „jetzt“, nichts nachholen. Kanal ohne
 * Cursor (neu beigetreten): ab Beitritt, siehe {@link #startForNew}.
 */
public final class Catchup {

    public static final String LATE_NOTE = "_(verspätet – ich war kurz offline)_";
    /** Endstand einer Antwort, die ein Neustart abgebrochen hat – zählt nicht als Antwort. */
    public static final String INTERRUPTED_NOTE = "_(unterbrochen – Captain wurde neu gestartet, die Antwort folgt)_";
    /** Zwischenstände enden damit ({@link Bot#CURSOR}). */
    public static final String STREAM_CURSOR = "▌";
    /** Props einer fertigen Antwort: beantwortete Post-IDs. */
    public static final String ANSWERS_PROP = "captain_answers";

    private Catchup() {
    }

    /**
     * Startpunkt ({@code create_at > …}) für einen Kanal ohne Cursor.
     *
     * <p>{@code posts}: die Posts des Kanals seit {@code oldest} (= jetzt − Höchstalter).
     * DMs/Gruppen-DMs ({@code D}/{@code G}): Der Bot ist Mitglied seit Erstellung → ab deren
     * {@code create_at} (sonst ginge die erste DM an einen gerade offline Bot verloren). Kanäle: ab
     * dem jüngsten System-Post „beigetreten“/„hinzugefügt“ des Bots in {@code posts}; fehlt er, liegt
     * der Beitritt vor {@code oldest} → ab {@code oldest}. Nie vor {@code oldest}; nachgeholt werden
     * ohnehin nur Posts an Captain, also keine Flut.
     */
    public static long startForNew(JsonNode channel, List<JsonNode> posts, String botId, long oldest) {
        String type = Json.str(channel, "type");
        if (type.equals("D") || type.equals("G")) {
            return Math.max(Json.lng(channel, "create_at") - 1, oldest);
        }
        long joined = 0;
        for (JsonNode p : posts) {
            String kind = Json.str(p, "type");
            JsonNode props = p.path("props");
            if (kind.equals("system_join_channel") && Json.str(p, "user_id").equals(botId)) {
                joined = Math.max(joined, Json.lng(p, "create_at"));
            } else if (kind.equals("system_add_to_channel") && props.isObject()
                    && Json.str(props, "addedUserId").equals(botId)) {
                joined = Math.max(joined, Json.lng(p, "create_at"));
            }
        }
        return Math.max(joined, oldest);
    }

    /** Untergrenze für {@code create_at > …} und ob der Cursor älter als erlaubt war. */
    public record FetchAfter(long start, boolean tooOld) {
    }

    public static FetchAfter fetchAfter(long cursor, long now, long maxAgeMs) {
        long oldest = now - maxAgeMs;
        if (cursor < oldest) {
            return new FetchAfter(oldest, true);
        }
        return new FetchAfter(cursor, false);
    }

    /** Posts aller Kanäle ohne Doppelte, chronologisch ({@code create_at}, dann ID). */
    public static List<JsonNode> orderMissed(List<JsonNode> posts) {
        Map<String, JsonNode> unique = new LinkedHashMap<>();
        for (JsonNode p : posts) {
            String id = Json.str(p, "id");
            if (!id.isEmpty()) {
                unique.put(id, p);
            }
        }
        List<JsonNode> out = new ArrayList<>(unique.values());
        out.sort(Comparator.<JsonNode>comparingLong(p -> Json.lng(p, "create_at")).thenComparing(p -> Json.str(p, "id")));
        return out;
    }

    /** Zwischenstand oder durch Neustart abgebrochene Antwort? */
    public static boolean isUnfinished(String message) {
        String text = message == null ? "" : message.stripTrailing();
        return text.endsWith(STREAM_CURSOR) || text.contains(INTERRUPTED_NOTE);
    }

    /** Props für den (letzten) Post einer fertigen Antwort. */
    public static Map<String, Object> answersProps(List<String> postIds) {
        return Map.of(ANSWERS_PROP, postIds.stream().filter(id -> id != null && !id.isEmpty()).toList());
    }

    /**
     * Post-IDs, die Captain laut {@code props.captain_answers} fertig beantwortet hat.
     *
     * <p>Die Eigenschaft setzt Captain erst, wenn eine Antwort vollständig geschrieben ist (bei
     * mehrteiligen Antworten am letzten Teil); Zwischenstände und abgebrochene Antworten tragen sie
     * nie. Unabhängig von Zeitstempeln – ein wartender Post gilt also nicht als beantwortet, nur weil
     * danach eine Antwort auf einen früheren Post erschien.
     */
    public static Set<String> answeredIn(List<JsonNode> posts, String botId) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode p : posts) {
            if (!Json.str(p, "user_id").equals(botId) || Json.truthy(p, "delete_at")) {
                continue;
            }
            JsonNode value = p.path("props").path(ANSWERS_PROP);
            for (JsonNode v : Json.list(value)) {
                String id = Json.scalar(v);
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /** Captain-Zwischenstände ({@code ▌}) von vor {@code before} – Reste eines Abbruchs. */
    public static List<JsonNode> stalePartials(List<JsonNode> posts, String botId, long before) {
        return posts.stream().filter(p -> Json.str(p, "user_id").equals(botId)
                && !Json.truthy(p, "type") && !Json.truthy(p, "delete_at")
                && Json.str(p, "message").stripTrailing().endsWith(STREAM_CURSOR)
                && Json.lng(p, "create_at") < before).toList();
    }
}
