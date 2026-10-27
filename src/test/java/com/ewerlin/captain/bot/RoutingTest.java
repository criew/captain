package com.ewerlin.captain.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.ewerlin.captain.mattermost.Post;
import com.ewerlin.captain.util.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Unit-Tests für {@link Routing} (reine Entscheidungslogik). */
class RoutingTest {

    static Post p(String text, String ctype, String root, String user, boolean fromBot, String pid) {
        return new Post(pid, "c1", ctype, root, user, "alice", text, List.of(), fromBot, 0);
    }

    static Post p(String text, String ctype) {
        return p(text, ctype, "", "u1", false, "p1");
    }

    static Post p(String text, String ctype, String root) {
        return p(text, ctype, root, "u1", false, "p1");
    }

    static Stream<Arguments> mentions() {
        return Stream.of(
                Arguments.of("@captain hilf mir", true),
                Arguments.of("Hey @Captain, was meinst du?", true),
                Arguments.of("frag mal @CAPTAIN.", true),
                Arguments.of("(@captain)", true),
                Arguments.of("@captain-bot hallo", false),
                Arguments.of("@captain.x hallo", false),
                Arguments.of("@captainhook", false),
                Arguments.of("mail@captain.de", false),
                Arguments.of("captain ohne at", false),
                Arguments.of("schreib `@captain !help` in den Kanal", false), // Code zählt nicht
                Arguments.of("```\n@captain\n```", false),
                Arguments.of("`code` und @captain", true));
    }

    @ParameterizedTest
    @MethodSource("mentions")
    void isMentionText(String text, boolean hit) {
        assertThat(Routing.isMention(p(text, "O"), "bot1", "captain")).isEqualTo(hit);
    }

    @Test
    void groupBroadcastIsNoMention() {
        assertThat(Routing.isMention(p("@channel Essen ist fertig", "O"), "bot1", "captain")).isFalse();
        assertThat(Routing.shouldAnswer(p("@all hallo", "G"), "bot1", "captain", false)).isFalse();
    }

    @Test
    void stripMention() {
        assertThat(Routing.stripMention("@captain !help", "captain")).isEqualTo("!help");
        assertThat(Routing.stripMention("@Captain, wie spät?", "captain")).isEqualTo("wie spät?");
        assertThat(Routing.stripMention("@captain: @captain hallo", "captain")).isEqualTo("hallo");
        assertThat(Routing.stripMention("Frag @captain.", "captain")).isEqualTo("Frag Captain.");
        assertThat(Routing.stripMention("Was meinst du, @captain?", "captain")).isEqualTo("Was meinst du, Captain?");
        assertThat(Routing.stripMention("  normal  ", "captain")).isEqualTo("normal");
        assertThat(Routing.stripMention("`@captain` bleibt", "captain")).isEqualTo("`@captain` bleibt");
        // Anderer Bot-Name: Erwähnung mitten im Satz wird zum Anzeigenamen
        assertThat(Routing.stripMention("Frag @superman mal.", "superman", "CaptainSuperman"))
                .isEqualTo("Frag CaptainSuperman mal.");
        assertThat(Routing.stripMention("@superman, wie spät?", "superman", "CaptainSuperman")).isEqualTo("wie spät?");
    }

    static final String CODE = "def f():\n    return  1\n\n| a  |  b |\n|----|----|\n\tx";

    @Test
    void stripMentionKeepsWhitespace() {
        assertThat(Routing.stripMention(CODE, "captain")).isEqualTo(CODE); // ohne Erwähnung unverändert
        assertThat(Routing.stripMention("@captain " + CODE, "captain")).isEqualTo(CODE);
        assertThat(Routing.stripMention("Schau @captain:\n" + CODE, "captain")).isEqualTo("Schau Captain:\n" + CODE);
    }

    static Stream<Arguments> answers() {
        return Stream.of(
                // DM: immer
                Arguments.of(p("hallo", "D"), false, true),
                Arguments.of(p("im DM-Thread", "D", "r1"), false, true),
                // Kanal/Gruppe Top-Level: nur mit Erwähnung
                Arguments.of(p("hallo", "O"), false, false),
                Arguments.of(p("hallo", "P"), false, false),
                Arguments.of(p("hallo", "G"), false, false),
                Arguments.of(p("@captain hallo", "O"), false, true),
                Arguments.of(p("@captain hallo", "G"), false, true),
                Arguments.of(p("!help", "O"), false, false),
                Arguments.of(p("@captain !help", "P"), false, true),
                // Top-Level: in_thread spielt keine Rolle
                Arguments.of(p("hallo", "O"), true, false),
                // Thread: mit Captain-Beteiligung ohne Erwähnung, sonst nur mit
                Arguments.of(p("weiter", "O", "r1"), true, true),
                Arguments.of(p("!neu", "G", "r1"), true, true),
                Arguments.of(p("weiter", "O", "r1"), false, false),
                Arguments.of(p("@captain weiter", "O", "r1"), false, true),
                // nie: eigene Posts, Bots/Webhooks
                Arguments.of(p("@captain", "O", "", "bot1", false, "p1"), false, false),
                Arguments.of(p("hallo", "D", "", "u1", true, "p1"), false, false),
                Arguments.of(p("@captain", "O", "r1", "u1", true, "p1"), true, false));
    }

    @ParameterizedTest
    @MethodSource("answers")
    void shouldAnswer(Post post, boolean inThread, boolean expected) {
        assertThat(Routing.shouldAnswer(post, "bot1", "captain", inThread)).isEqualTo(expected);
    }

    @Test
    void replyRootAndKeys() {
        Post dm = p("hallo", "D", "", "u1", false, "d1");
        assertThat(Routing.replyRoot(dm)).isNull();
        assertThat(Routing.conversationKey(dm)).isEqualTo("matter-dm:c1");
        assertThat(Routing.settingsKey(dm)).isEqualTo("matter-dm:c1");
        Post dmThread = p("hallo", "D", "r0");
        assertThat(Routing.replyRoot(dmThread)).isEqualTo("r0");
        assertThat(Routing.conversationKey(dmThread)).isEqualTo("matter-th:r0");

        Post top = p("@captain", "O", "", "u1", false, "t1");
        assertThat(Routing.replyRoot(top)).isEqualTo("t1"); // Thread unter dem erwähnenden Post
        assertThat(Routing.conversationKey(top)).isEqualTo("matter-th:t1");
        assertThat(Routing.settingsKey(top)).isEqualTo("matter-ch:c1"); // !modell oben gilt für den Kanal

        Post reply = p("weiter", "G", "t1", "u1", false, "t2");
        assertThat(Routing.replyRoot(reply)).isEqualTo("t1");
        assertThat(Routing.conversationKey(reply)).isEqualTo("matter-th:t1");
        assertThat(Routing.settingsKey(reply)).isEqualTo("matter-th:t1");
    }

    // ---------------------------------------------------------------- Vorgeschichte

    static ObjectNode rp(String pid, long at, String user, String msg, String root, Map<String, Object> extra) {
        ObjectNode node = Json.object().put("id", pid).put("create_at", at).put("user_id", user).put("root_id", root)
                .put("message", msg != null ? msg : "m" + pid);
        extra.forEach((k, v) -> node.set(k, Json.tree(v)));
        return node;
    }

    static ObjectNode rp(String pid, long at) {
        return rp(pid, at, "u1", null, "", Map.of());
    }

    static ObjectNode rp(String pid, long at, String user, String root) {
        return rp(pid, at, user, null, root, Map.of());
    }

    static List<String> ids(List<ObjectNode> posts) {
        return posts.stream().map(x -> Json.str(x, "id")).toList();
    }

    static List<ObjectNode> history(List<? extends JsonNode> posts, long before) {
        return Routing.channelHistory(new ArrayList<>(posts), "bot1", before, Set.of());
    }

    @Test
    void historyWithoutParticipationTakesRecentTopLevel() {
        List<ObjectNode> posts = List.of(rp("a", 1), rp("b", 2), rp("r", 3, "u1", "a"), rp("c", 4), rp("now", 5));
        List<ObjectNode> got = Routing.channelHistory(new ArrayList<>(posts), "bot1", 5, Set.of("now"));
        assertThat(ids(got)).containsExactly("a", "b", "c"); // nur Top-Level, vor dem Post
    }

    @Test
    void historySinceLastParticipation() {
        List<ObjectNode> posts = List.of(
                rp("old", 1), rp("t1", 2), rp("x", 3),
                rp("b1", 4, "bot1", "t1"), // Captain antwortet im Thread t1
                rp("y", 5), rp("t1r", 6, "u1", "t1"),
                rp("b2", 7, "bot1", "t1"), // später nochmal in t1
                rp("z", 8), rp("now", 9));
        // t1 (inkl. Kanal davor) kannte Captain schon → erst danach
        assertThat(ids(history(posts, 9))).containsExactly("x", "y", "z");
    }

    @Test
    void historyUsesNewestRootNotNewestReply() {
        List<ObjectNode> posts = List.of(
                rp("t1", 1), rp("t2", 3), rp("x", 4),
                rp("b2", 5, "bot1", "t2"),
                rp("b1", 6, "bot1", "t1"), // jüngste Antwort, aber alter Root
                rp("y", 7));
        assertThat(ids(history(posts, 100))).containsExactly("x", "y");
    }

    @Test
    void historyBotTopLevelPostCountsAndRootOutsideWindow() {
        List<ObjectNode> posts = List.of(rp("a", 1), rp("b", 2, "bot1", ""), rp("c", 3),
                rp("d", 4, "bot1", "weit-weg"), rp("e", 5));
        assertThat(ids(history(posts, 100))).containsExactly("c", "e");
    }

    @Test
    void historyFiltersBotsSystemEmptyDeleted() {
        List<ObjectNode> posts = List.of(
                rp("a", 1, "robot", ""),
                rp("b", 2, "u1", null, "", Map.of("props", Map.of("from_webhook", "true"))),
                rp("c", 3, "u1", null, "", Map.of("type", "system_join_channel")),
                rp("d", 4, "u1", "   ", "", Map.of()),
                rp("e", 5, "u1", null, "", Map.of("delete_at", 9)),
                rp("f", 6));
        List<ObjectNode> got = Routing.channelHistory(new ArrayList<>(posts), "bot1", 100, Set.of(),
                u -> u.equals("robot"), u -> u, 50, 8000, 2000);
        assertThat(ids(got)).containsExactly("f");
    }

    @Test
    void historyCapsNewestWin() {
        List<JsonNode> posts = new ArrayList<>(IntStream.rangeClosed(1, 100)
                .mapToObj(i -> (JsonNode) rp(String.valueOf(i), i, "u1", "x".repeat(10), "", Map.of())).toList());
        List<ObjectNode> got = Routing.channelHistory(posts, "bot1", 1000, Set.of());
        assertThat(got).hasSize(50);
        assertThat(Json.str(got.getLast(), "id")).isEqualTo("100");
        assertThat(Json.str(got.getFirst(), "id")).isEqualTo("51");
        // Budget zählt „Name: Text\n“: 3 + 2 + 10 + 1 = 16 je Zeile
        got = Routing.channelHistory(posts, "bot1", 1000, Set.of(), u -> false, u -> "Bob", 50, 48, 2000);
        assertThat(ids(got)).containsExactly("98", "99", "100");
        assertThat(Json.str(got.getFirst(), "name")).isEqualTo("Bob");
        assertThat(Routing.channelHistory(posts, "bot1", 1000, Set.of(), u -> false, u -> "Bob", 50, 47, 2000)).hasSize(2);
        List<JsonNode> longPosts = List.of(rp("a", 1, "u1", "kurz", "", Map.of()), rp("b", 2, "u1", "y".repeat(5000), "", Map.of()));
        got = Routing.channelHistory(longPosts, "bot1", 10, Set.of(), u -> false, u -> u, 50, 8000, 2000);
        assertThat(Json.str(got.getLast(), "message")).isEqualTo("y".repeat(2000) + " […]");
        assertThat(ids(got)).containsExactly("a", "b");
    }
}
