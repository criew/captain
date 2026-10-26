package com.ewerlin.captain.bot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ewerlin.captain.mattermost.Post;
import com.ewerlin.captain.util.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Wann antwortet Captain, wohin und in welcher Session? (reine Funktionen)
 *
 * <table>
 * <tr><th>Ort</th><th>Reaktion</th><th>Antwort</th></tr>
 * <tr><td>DM mit Captain ({@code D})</td><td>jede Nachricht</td><td>direkt im Chat</td></tr>
 * <tr><td>Kanal/Gruppe ({@code O}/{@code P}/{@code G}), Top-Level</td><td>nur bei {@code @captain}</td>
 * <td>Thread unter dem Post</td></tr>
 * <tr><td>Thread mit Captain-Beteiligung</td><td>jede Nachricht</td><td>im Thread</td></tr>
 * <tr><td>Thread ohne Captain-Beteiligung</td><td>nur bei {@code @captain}</td><td>im Thread</td></tr>
 * </table>
 *
 * <p>Alles hier ist ohne Netz testbar; ob Captain in einem Thread schon geschrieben hat, ermittelt
 * der Aufrufer ({@link Bot#addressed}).
 */
public final class Routing {

    /** Vorgeschichte einer neuen Kanal-Unterhaltung: höchstens so viele Posts … */
    public static final int HISTORY_POSTS = 50;
    /** … und so viele Zeichen (neueste gewinnen); Betrieb: HISTORY_MAX_*. */
    public static final int HISTORY_CHARS = 8000;
    /** Einzelner Post wird darüber gekürzt. */
    public static final int HISTORY_POST_CHARS = 2000;

    private static final Pattern CODE = Pattern.compile("```.*?(?:```|\\z)|`[^`\\n]*`", Pattern.DOTALL);
    private static final Pattern LEADING_PUNCT = Pattern.compile("[ \\t]*[,:;]?[ \\t]*");
    private static final Map<String, Pattern> MENTIONS = Collections.synchronizedMap(new HashMap<>());

    private Routing() {
    }

    private static Pattern mentionPattern(String username) {
        // @captain, @Captain, „@captain,“ – aber nicht @captain-x, @captain.x, mail@captain
        return MENTIONS.computeIfAbsent(username, u -> Pattern.compile(
                "(?<![\\w@.-])@" + Pattern.quote(u) + "(?![\\w-]|\\.\\w)",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS));
    }

    private record Span(int start, int end) {
    }

    /** {@code @name}-Treffer außerhalb von Code (```Block``` und `inline`). */
    private static List<Span> mentions(String text, String botUsername) {
        List<Span> out = new ArrayList<>();
        if (botUsername == null || botUsername.isEmpty()) {
            return out;
        }
        List<Span> code = new ArrayList<>();
        Matcher c = CODE.matcher(text);
        while (c.find()) {
            code.add(new Span(c.start(), c.end()));
        }
        Matcher m = mentionPattern(botUsername).matcher(text);
        while (m.find()) {
            int start = m.start();
            if (code.stream().noneMatch(s -> s.start() <= start && start < s.end())) {
                out.add(new Span(m.start(), m.end()));
            }
        }
        return out;
    }

    /**
     * Erwähnt der Post den Bot ausdrücklich mit {@code @name} (Groß-/Kleinschreibung egal)?
     *
     * <p>Nur der Text zählt, nicht {@code mentions} aus dem Event: In Gruppen-DMs enthält das alle
     * Mitglieder, in Kanälen auch die Empfänger von {@code @channel}/{@code @all}/{@code @here}.
     * {@code @name} in Code zählt nicht.
     */
    public static boolean isMention(Post post, String botId, String botUsername) {
        return !mentions(post.message(), botUsername).isEmpty();
    }

    public static String stripMention(String text, String botUsername) {
        return stripMention(text, botUsername, "Captain");
    }

    /**
     * Text für Befehle und Prompt: Anrede {@code @captain} am Anfang entfällt, weitere Erwähnungen
     * werden zu {@code display} („Frag @captain.“ → „Frag Captain.“).
     *
     * <p>Ohne Erwähnung bleibt der Text bis auf Leerraum an den Enden unverändert (Einrückungen,
     * Tabellen, Code).
     */
    public static String stripMention(String text, String botUsername, String display) {
        List<Span> found = mentions(text, botUsername);
        if (found.isEmpty()) {
            return text.strip();
        }
        StringBuilder out = new StringBuilder();
        int pos = 0;
        for (Span m : found) {
            out.append(text, pos, m.start());
            pos = m.end();
            if (out.toString().strip().isEmpty()) { // Anrede am Anfang: samt „,“/„:“ weg
                out.setLength(0);
                Matcher rest = LEADING_PUNCT.matcher(text).region(pos, text.length());
                if (rest.lookingAt()) {
                    pos = rest.end();
                }
            } else {
                out.append(display);
            }
        }
        out.append(text.substring(pos));
        return out.toString().strip();
    }

    /**
     * Soll Captain auf {@code post} reagieren?
     *
     * @param inThread Captain hat im Thread {@code post.rootId} schon geschrieben (nur relevant für
     *                 Thread-Antworten außerhalb von DMs). Eigene Posts und Posts von Bots/Webhooks
     *                 werden nie beantwortet.
     */
    public static boolean shouldAnswer(Post post, String botId, String botUsername, boolean inThread) {
        if (post.fromBot() || (botId != null && !botId.isEmpty() && post.userId().equals(botId))) {
            return false;
        }
        if (post.channelType().equals("D")) {
            return true;
        }
        if (isMention(post, botId, botUsername)) {
            return true;
        }
        return !post.rootId().isEmpty() && inThread;
    }

    /** Thread, in dem die Antwort erscheint; {@code null} = direkt im Chat (DM). */
    public static String replyRoot(Post post) {
        if (!post.rootId().isEmpty()) {
            return post.rootId();
        }
        if (post.channelType().equals("D")) {
            return null;
        }
        return post.id(); // Top-Level in Kanal/Gruppe → Thread unter dem Post öffnen
    }

    /** Session-Key: {@code dm:<channel>} für DMs, sonst {@code th:<root>}. */
    public static String conversationKey(Post post) {
        return SessionKeys.of(post.channelType(), post.channelId(), replyRoot(post));
    }

    /** Wo {@code !modell} gilt: Top-Level in Kanal/Gruppe → ganzer Kanal, sonst die Session. */
    public static String settingsKey(Post post) {
        if (!post.channelType().equals("D") && post.rootId().isEmpty()) {
            return "ch:" + post.channelId();
        }
        return conversationKey(post);
    }

    /**
     * Kanal-Posts seit Captains letzter Beteiligung, älteste zuerst.
     *
     * <p>{@code posts}: Kanal-Posts (Top-Level und Thread-Antworten, beliebige Reihenfolge) wie von
     * {@code GET /channels/{id}/posts}. Letzte Beteiligung ist der jüngste Thread-Root, in dem
     * Captain geschrieben hat (bzw. ein eigener Top-Level-Post): Alles bis dahin kannte Captain
     * schon. Liefert nur Top-Level-Posts von Menschen, strikt danach und vor {@code before}
     * ({@code create_at} in ms), ohne {@code exclude}. Gedeckelt auf {@code maxPosts} und
     * {@code maxChars} (Zeilen {@code Name: Text} inkl. Zeilenumbruch) – die neuesten gewinnen;
     * {@code message} ist ggf. gekürzt, {@code name} kommt aus {@code nameOf}.
     */
    public static List<ObjectNode> channelHistory(List<JsonNode> posts, String botId, long before, Set<String> exclude,
            Predicate<String> isBot, Function<String, String> nameOf, int maxPosts, int maxChars, int postChars) {
        Map<String, JsonNode> byId = new HashMap<>();
        posts.forEach(p -> byId.put(Json.str(p, "id"), p));
        long cutoff = 0;
        for (JsonNode p : posts) {
            if (!Json.str(p, "user_id").equals(botId)) {
                continue;
            }
            String root = Json.str(p, "root_id");
            if (!root.isEmpty()) {
                // Root außerhalb des Fensters ist älter als alles hier → 0
                cutoff = Math.max(cutoff, Json.lng(byId.get(root), "create_at"));
            } else {
                cutoff = Math.max(cutoff, Json.lng(p, "create_at"));
            }
        }
        long since = cutoff;
        List<JsonNode> candidates = new ArrayList<>(posts.stream().filter(p ->
                Json.str(p, "root_id").isEmpty()
                        && !Json.truthy(p, "type")
                        && !Json.truthy(p, "delete_at")
                        && !Json.str(p, "user_id").equals(botId)
                        && !exclude.contains(Json.str(p, "id"))
                        && since < Json.lng(p, "create_at") && Json.lng(p, "create_at") < before
                        && !Json.str(p, "message").strip().isEmpty()
                        && !Post.fromBotProps(p)
                        && !isBot.test(Json.str(p, "user_id"))).toList());
        candidates.sort(Comparator.comparingLong(p -> Json.lng(p, "create_at")));
        List<ObjectNode> picked = new ArrayList<>();
        int total = 0;
        for (int i = candidates.size() - 1; i >= 0; i--) {
            if (picked.size() >= maxPosts) {
                break;
            }
            JsonNode p = candidates.get(i);
            String message = Json.str(p, "message").strip();
            if (message.length() > postChars) {
                message = message.substring(0, postChars) + " […]";
            }
            String name = nameOf.apply(Json.str(p, "user_id"));
            if (name == null || name.isEmpty()) {
                name = "?";
            }
            int cost = name.length() + 2 + message.length() + 1; // „Name: Text\n“
            if (total + cost > maxChars) {
                break;
            }
            total += cost;
            ObjectNode copy = ((ObjectNode) p.deepCopy()).put("message", message).put("name", name);
            picked.add(copy);
        }
        Collections.reverse(picked);
        return picked;
    }

    public static List<ObjectNode> channelHistory(List<JsonNode> posts, String botId, long before, Set<String> exclude) {
        return channelHistory(posts, botId, before, exclude, uid -> false, uid -> uid,
                HISTORY_POSTS, HISTORY_CHARS, HISTORY_POST_CHARS);
    }
}
