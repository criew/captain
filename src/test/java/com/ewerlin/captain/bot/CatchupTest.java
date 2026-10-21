package com.ewerlin.captain.bot;

import static com.ewerlin.captain.bot.BotTestSupport.slash;
import static com.ewerlin.captain.bot.BotTestSupport.waitFor;
import static com.ewerlin.captain.bot.BotTestSupport.waitIdle;
import static com.ewerlin.captain.bot.FakeMattermost.raw;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ewerlin.captain.config.CaptainConfig;
import com.ewerlin.captain.mattermost.Post;
import com.ewerlin.captain.opencode.PromptInterruptedException;
import com.ewerlin.captain.util.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Nachholen verpasster Posts: Zustand, Auswahl und Bot-Ablauf (ohne Netz).
 *
 * <p>Ziel: nach einem Neustart geht keine an Captain gerichtete Nachricht verloren, und keine wird
 * doppelt beantwortet.
 */
class CatchupTest {

    static final long H = 3600 * 1000;
    static final long NOW = 100 * H; // „jetzt“ in ms

    @TempDir
    Path tmp;
    final List<Bot> bots = new ArrayList<>();

    @AfterEach
    void tearDown() {
        bots.forEach(Bot::stop);
    }

    static JsonNode read(Path path) {
        try {
            return Json.parse(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- CursorStore

    CursorStore store(Path path) {
        return new CursorStore(path, 0, 24 * 3600, () -> NOW);
    }

    @Test
    void storeNotWrittenBeforeFirstStart() {
        Path path = tmp.resolve("d").resolve("cursors.json");
        CursorStore c = store(path);
        assertThat(c.initialized()).isFalse();
        assertThat(c.get("c1")).isNull();
        c.advance("c1", 5);
        c.release("c1", "p1", true);
        c.close();
        assertThat(path).doesNotExist(); // kein halber Zustand vor dem ersten erfolgreichen Start
    }

    @Test
    void storeRoundtrip() throws IOException {
        Path path = tmp.resolve("d").resolve("cursors.json");
        CursorStore c = store(path);
        c.setAll(List.of("c1", "c2"), 500);
        c.advance("c1", 700);
        c.advance("c1", 600); // nie zurück
        c.advance("", 900); // ohne Kanal: ignoriert
        c.markAnswered("p9");
        c.close();
        assertThat(read(path)).isEqualTo(Json.parse("{\"version\": 2, \"initialized\": true, "
                + "\"channels\": {\"c1\": 700, \"c2\": 500}, \"answered\": {\"p9\": " + NOW + "}}"));
        CursorStore again = store(path);
        assertThat(again.initialized()).isTrue();
        assertThat(again.get("c1")).isEqualTo(700);
        assertThat(again.isAnswered("p9")).isTrue();
        try (Stream<Path> files = Files.list(tmp.resolve("d"))) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp"));
        }
    }

    @Test
    void storeVersion1CountsAsInitialized() throws IOException {
        Path path = tmp.resolve("cursors.json");
        Files.writeString(path, "{\"version\": 1, \"channels\": {\"c1\": 5}}");
        CursorStore c = store(path);
        assertThat(c.initialized()).isTrue();
        assertThat(c.get("c1")).isEqualTo(5);
    }

    @Test
    void storeLowWatermark() {
        Path path = tmp.resolve("cursors.json");
        CursorStore c = store(path);
        c.setAll(List.of("c1"), 100);
        c.hold("c1", "p1", 200); // angenommen, noch nicht beantwortet
        c.advance("c1", 200);
        c.hold("c1", "p2", 300);
        c.advance("c1", 400);
        assertThat(c.snapshot()).isEqualTo(Map.of("c1", 199L));
        c.release("c1", "p2", true);
        assertThat(c.snapshot()).isEqualTo(Map.of("c1", 199L));
        c.release("c1", "p1", true);
        assertThat(c.snapshot()).isEqualTo(Map.of("c1", 400L));
        c.release("c1", "p3", false);
        c.close();
        assertThat(read(path).get("answered")).isEqualTo(Json.parse("{\"p1\": " + NOW + ", \"p2\": " + NOW + "}"));
    }

    @Test
    void storeFreeze() {
        CursorStore c = store(tmp.resolve("cursors.json"));
        c.setAll(List.of("c1", "c2"), 100);
        c.freeze("c1", 100);
        c.advance("c1", 500);
        c.advance("c2", 500);
        assertThat(c.snapshot()).isEqualTo(Map.of("c1", 100L, "c2", 500L));
        c.unfreeze("c1");
        assertThat(c.snapshot()).isEqualTo(Map.of("c1", 500L, "c2", 500L));
        c.flush();
        c.freezeAll(); // Kanalliste nicht ladbar: nichts Neues schreiben
        c.advance("c1", 900);
        c.markKnown("c3", 900L);
        assertThat(c.snapshot()).isEqualTo(Map.of("c1", 500L, "c2", 500L));
        c.unfreezeAll();
        assertThat(c.snapshot()).isEqualTo(Map.of("c1", 900L, "c2", 500L, "c3", 900L));
    }

    @Test
    void storeAnsweredExpire() {
        Path path = tmp.resolve("cursors.json");
        long[] now = {NOW};
        CursorStore c = new CursorStore(path, 0, 3600, () -> now[0]);
        c.setAll(List.of(), 0);
        c.markAnswered("alt");
        now[0] += 2 * H;
        c.markAnswered("neu");
        c.close();
        assertThat(read(path).get("answered")).isEqualTo(Json.parse("{\"neu\": " + (NOW + 2 * H) + "}"));
    }

    @Test
    void storeThrottled() {
        Path path = tmp.resolve("cursors.json");
        CursorStore c = new CursorStore(path, 1.0, 24 * 3600, System::currentTimeMillis);
        c.setAll(List.of("c1"), 1); // schreibt sofort
        c.advance("c1", 2);
        c.advance("c1", 3);
        assertThat(read(path).get("channels")).isEqualTo(Json.parse("{\"c1\": 1}")); // noch gedrosselt
        waitFor(() -> {
            try {
                return read(path).get("channels").equals(Json.parse("{\"c1\": 3}"));
            } catch (RuntimeException e) { // Windows: Datei wird gerade ersetzt
                return false;
            }
        }, "gedrosseltes Schreiben");
        c.close();
    }

    @Test
    void storeCorruptCountsAsFirstStart() throws IOException {
        Path path = tmp.resolve("cursors.json");
        Files.writeString(path, "{kaputt");
        CursorStore c = store(path);
        assertThat(c.initialized()).isFalse();
        assertThat(c.snapshot()).isEmpty();
        assertThat(tmp.resolve("cursors.json.corrupt")).exists();
    }

    // ---------------------------------------------------------------- Auswahl

    @Test
    void startForNew() {
        String bot = "bot1";
        long oldest = NOW - 24 * H;
        assertThat(Catchup.startForNew(Json.parse("{\"type\":\"D\",\"create_at\":" + (NOW - 5 * H) + "}"), List.of(), bot, oldest))
                .isEqualTo(NOW - 5 * H - 1);
        assertThat(Catchup.startForNew(Json.parse("{\"type\":\"G\",\"create_at\":1}"), List.of(), bot, oldest)).isEqualTo(oldest);
        List<JsonNode> posts = List.of(
                Json.parse("{\"type\":\"system_add_to_channel\",\"user_id\":\"adm\",\"props\":{\"addedUserId\":\"u2\"},\"create_at\":" + (NOW - H) + "}"),
                Json.parse("{\"type\":\"system_add_to_channel\",\"user_id\":\"adm\",\"props\":{\"addedUserId\":\"bot1\"},\"create_at\":" + (NOW - 5 * H) + "}"),
                Json.parse("{\"type\":\"system_join_channel\",\"user_id\":\"bot1\",\"create_at\":" + (NOW - 4 * H) + "}"));
        assertThat(Catchup.startForNew(Json.parse("{\"type\":\"O\"}"), posts, bot, oldest)).isEqualTo(NOW - 4 * H);
        assertThat(Catchup.startForNew(Json.parse("{\"type\":\"P\"}"), posts.subList(0, 1), bot, oldest)).isEqualTo(oldest);
    }

    @Test
    void fetchAfterCapsAge() {
        assertThat(Catchup.fetchAfter(NOW - H, NOW, 24 * H)).isEqualTo(new Catchup.FetchAfter(NOW - H, false));
        assertThat(Catchup.fetchAfter(NOW - 25 * H, NOW, 24 * H)).isEqualTo(new Catchup.FetchAfter(NOW - 24 * H, true));
        assertThat(Catchup.fetchAfter(NOW - H, NOW, 0)).isEqualTo(new Catchup.FetchAfter(NOW, true)); // 0 = nichts
    }

    @Test
    void orderMissedChronologicalAndDeduplicated() {
        List<JsonNode> posts = List.of(
                Json.parse("{\"id\":\"b\",\"create_at\":3}"), Json.parse("{\"id\":\"a\",\"create_at\":1}"),
                Json.parse("{\"id\":\"c\",\"create_at\":3}"), Json.parse("{\"id\":\"a\",\"create_at\":1}"),
                Json.parse("{\"create_at\":2}"));
        assertThat(Catchup.orderMissed(posts).stream().map(p -> Json.str(p, "id")).toList()).containsExactly("a", "b", "c");
    }

    @Test
    void answeredInUsesPropsNotTimestamps() {
        List<JsonNode> posts = List.of(
                Json.parse("{\"user_id\":\"bot1\",\"message\":\"fertig\",\"create_at\":10,"
                        + "\"props\":{\"from_bot\":\"true\",\"captain_answers\":[\"d1\",\"d2\"]}}"),
                Json.parse("{\"user_id\":\"bot1\",\"message\":\"x ▌\",\"create_at\":20,\"props\":{\"from_bot\":\"true\"}}"),
                Json.parse("{\"user_id\":\"bot1\",\"message\":\"gelöscht\",\"create_at\":30,\"delete_at\":31,"
                        + "\"props\":{\"captain_answers\":[\"d3\"]}}"),
                Json.parse("{\"user_id\":\"u_alice\",\"message\":\"gefälscht\",\"create_at\":40,"
                        + "\"props\":{\"captain_answers\":[\"d4\"]}}"));
        assertThat(Catchup.answeredIn(posts, "bot1")).containsExactlyInAnyOrder("d1", "d2");
        assertThat(Catchup.answersProps(List.of("a", "", "b"))).isEqualTo(Map.of("captain_answers", List.of("a", "b")));
        assertThat(Catchup.isUnfinished("a ▌ ")).isTrue();
        assertThat(Catchup.isUnfinished("a\n\n" + Catchup.INTERRUPTED_NOTE)).isTrue();
        assertThat(Catchup.isUnfinished("fertig")).isFalse();
        assertThat(Catchup.stalePartials(posts, "bot1", 25).stream().map(p -> Json.lng(p, "create_at")).toList())
                .containsExactly(20L);
    }

    // ---------------------------------------------------------------- Bot

    /**
     * Fake-Mattermost, dessen Kanalverlauf auch die eigenen Posts des Bots enthält. Überlebt
     * „Neustarts“ (mehrere Bots nacheinander auf demselben Objekt).
     */
    static class HistMattermost extends FakeMattermost {
        long t = NOW + 1000; // Bot-Posts nach den Nutzer-Posts der Tests
        final Set<String> failChannels = java.util.concurrent.ConcurrentHashMap.newKeySet();
        volatile boolean failList;
        final List<String[]> updateCalls = new ArrayList<>();

        @Override
        public String createPost(String channelId, String message, String rootId, List<String> fileIds,
                Map<String, Object> props) {
            String pid = super.createPost(channelId, message, rootId, fileIds, props);
            synchronized (lock) {
                t++;
                ObjectNode node = raw(pid, channelId, "bot1", message, t, rootId == null ? "" : rootId);
                node.set("props", Json.tree(props == null ? Map.of() : props));
                history.computeIfAbsent(channelId, k -> new ArrayList<>()).add(node);
            }
            return pid;
        }

        @Override
        public void updatePost(String postId, String message, Map<String, Object> props) {
            if (posts.containsKey(postId)) {
                super.updatePost(postId, message, props);
            } else {
                synchronized (lock) {
                    updateCalls.add(new String[] {postId, message});
                }
            }
            synchronized (lock) {
                for (List<JsonNode> list : history.values()) {
                    for (JsonNode p : list) {
                        if (Json.str(p, "id").equals(postId)) {
                            ((ObjectNode) p).put("message", message);
                            if (props != null) {
                                ((ObjectNode) p).set("props", Json.tree(props));
                            }
                        }
                    }
                }
            }
        }

        @Override
        List<JsonNode> channelList() {
            if (failList) {
                throw new IllegalStateException("Kanalliste weg");
            }
            return super.channelList();
        }

        @Override
        public List<JsonNode> getPostsAfter(String channelId, long after) {
            if (failChannels.contains(channelId)) {
                throw new IllegalStateException("Kanal weg");
            }
            return super.getPostsAfter(channelId, after);
        }

        Post userPost(String pid, String ch, String user, String text, long at, String root) {
            synchronized (lock) {
                history.computeIfAbsent(ch, k -> new ArrayList<>()).add(raw(pid, ch, user, text, at, root));
            }
            return new Post(pid, ch, ch.equals("dm1") ? "D" : "P", root, user,
                    Map.of("u_alice", "alice", "u_bob", "bob").get(user), text, List.of(), false, at);
        }

        Post userPost(String pid, String ch, String user, String text, long at) {
            return userPost(pid, ch, user, text, at, "");
        }

        List<JsonNode> botMessages(String ch) {
            synchronized (lock) {
                return history.getOrDefault(ch, List.of()).stream().filter(p -> Json.str(p, "user_id").equals("bot1")).toList();
            }
        }

        void put(String ch, JsonNode... posts) {
            synchronized (lock) {
                history.put(ch, new ArrayList<>(List.of(posts)));
            }
        }
    }

    static List<JsonNode> channels() {
        return new ArrayList<>(List.of(
                Json.parse("{\"id\":\"dm1\",\"type\":\"D\",\"create_at\":1}"),
                Json.parse("{\"id\":\"fam\",\"type\":\"P\",\"create_at\":1}")));
    }

    record Built(Bot bot, HistMattermost mm, FakeOpencode oc) {
    }

    Path dataDir() {
        return tmp.resolve("data");
    }

    Path cursorPath() {
        return dataDir().resolve("cursors.json");
    }

    /** Baut einen Bot; {@code cursors} = Inhalt einer vorhandenen Cursor-Datei, {@code mm} wiederverwenden = Neustart. */
    Built make(Map<String, Long> cursors, HistMattermost mm, double interval, UnaryOperator<CaptainConfig.Builder> cfgFn,
            Consumer<Post> spy) {
        try {
            if (cursors != null) {
                Files.createDirectories(dataDir());
                Files.writeString(cursorPath(), Json.write(Map.of(
                        "version", 2, "initialized", true, "channels", cursors, "answered", Map.of())));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        CaptainConfig cfg = cfgFn.apply(CaptainConfig.builder()
                .mmUrl("http://mm").mmBotToken("t").opencodeUrl("http://oc")
                .sessionsDir(slash(tmp.resolve("ws"))).dataDir(dataDir().toString())).build();
        if (mm == null) {
            mm = new HistMattermost();
            mm.channels = channels();
        }
        FakeOpencode oc = new FakeOpencode();
        CursorStore cur = new CursorStore(cursorPath(), interval, cfg.catchupMaxAge().toMillis() / 1000.0, () -> NOW);
        Bot b = spy == null
                ? new Bot(cfg, mm, oc, new SessionStore(dataDir().resolve("sessions.json")), cur, 8, Bot.PROMPT_IDLE_TIMEOUT)
                : new Bot(cfg, mm, oc, new SessionStore(dataDir().resolve("sessions.json")), cur, 8, Bot.PROMPT_IDLE_TIMEOUT) {
                    @Override
                    public void onPost(Post post) {
                        spy.accept(post);
                        super.onPost(post);
                    }
                };
        BotTestSupport.fifoSide(b);
        b.clock = () -> NOW;
        b.startedMs = mm.t + 1; // Zwischenstände bis hierher: früherer Prozess
        b.catchupBackoff = new double[] {0.05, 0.1};
        bots.add(b);
        return new Built(b, mm, oc);
    }

    Built make(Map<String, Long> cursors) {
        return make(cursors, null, 0.0, x -> x, null);
    }

    Built restart(HistMattermost mm) {
        return make(null, mm, 0.0, x -> x, null);
    }

    static List<FakeMattermost.Sent> replies(FakeMattermost mm) {
        return mm.sent();
    }

    static String prompted(FakeOpencode oc) {
        return String.join("\n", oc.texts());
    }

    static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void firstStartSetsCursorsToNowAndAnswersNothing() {
        Built x = make(null);
        x.mm().userPost("d1", "dm1", "u_alice", "alt", NOW - H);
        assertThat(x.bot().catchUp()).isTrue();
        waitIdle(x.bot());
        assertThat(x.oc().prompts).isEmpty();
        assertThat(x.mm().afterCalls).isEmpty();
        x.bot().cursors.close();
        assertThat(read(cursorPath()).get("channels")).isEqualTo(Json.parse("{\"dm1\": " + NOW + ", \"fam\": " + NOW + "}"));
    }

    @Test
    void restartAnswersMissedWithLateNote() {
        List<String> routed = java.util.Collections.synchronizedList(new ArrayList<>());
        Built x = make(Map.of("dm1", NOW - 2 * H, "fam", NOW - 2 * H), null, 0.0, c -> c, p -> routed.add(p.id()));
        ObjectNode join = raw("f3", "fam", "u_bob", "", NOW - 40_000);
        join.put("type", "system_join_channel");
        x.mm().put("dm1", raw("d0", "dm1", "u_alice", "schon gesehen", NOW - 3 * H),
                raw("d1", "dm1", "u_alice", "Hallo, bist du da?", NOW - 30_000));
        x.mm().put("fam", raw("f1", "fam", "u_bob", "ohne Erwähnung", NOW - 60_000),
                raw("f2", "fam", "u_bob", "@captain Frage im Kanal", NOW - 50_000), join);
        assertThat(x.bot().catchUp()).isTrue();
        waitIdle(x.bot());
        assertThat(routed).containsExactly("f2", "d1"); // chronologisch über alle Kanäle
        assertThat(x.mm().afterCalls).containsExactly(Map.entry("dm1", NOW - 2 * H), Map.entry("fam", NOW - 2 * H));
        assertThat(x.oc().texts()).containsExactlyInAnyOrder("Bobby: Frage im Kanal", "Hallo, bist du da?");
        List<FakeMattermost.Sent> posts = replies(x.mm());
        assertThat(posts).hasSize(2);
        assertThat(posts).allMatch(p -> p.message.startsWith(Catchup.LATE_NOTE + "\n\n"));
        Map<String, FakeMattermost.Sent> byChannel = new HashMap<>();
        posts.forEach(p -> byChannel.put(p.channelId, p));
        assertThat(byChannel.get("fam").rootId).isEqualTo("f2");
        assertThat(byChannel.get("dm1").rootId).isNull();
        // beantwortet → Cursor hinter allem Gesehenen, IDs gemerkt
        assertThat(x.bot().cursors.get("dm1")).isEqualTo(NOW - 30_000);
        assertThat(x.bot().cursors.get("fam")).isEqualTo(NOW - 40_000);
        assertThat(x.bot().cursors.isAnswered("d1")).isTrue();
        assertThat(x.bot().cursors.isAnswered("f2")).isTrue();
    }

    @Test
    void lastPostAtSkipsQuietChannels() {
        Built x = make(Map.of("dm1", NOW - H, "fam", NOW - H));
        ((ObjectNode) x.mm().channels.get(0)).put("last_post_at", NOW - H); // nichts Neues
        ((ObjectNode) x.mm().channels.get(1)).put("last_post_at", NOW - 10);
        x.mm().userPost("f1", "fam", "u_bob", "@captain hallo", NOW - 10);
        assertThat(x.bot().catchUp()).isTrue();
        assertThat(x.mm().afterCalls.stream().map(Map.Entry::getKey).toList()).containsExactly("fam");
    }

    @Test
    void olderThanMaxAgeSkipped() {
        Built x = make(Map.of("dm1", NOW - 25 * H, "fam", NOW));
        x.mm().userPost("d1", "dm1", "u_alice", "zu alt", NOW - 24 * H - 1);
        x.mm().userPost("d2", "dm1", "u_alice", "frisch", NOW - H);
        x.bot().catchUp();
        waitIdle(x.bot());
        assertThat(x.mm().afterCalls).contains(Map.entry("dm1", NOW - 24 * H));
        assertThat(x.oc().texts()).containsExactly("frisch");
    }

    @Test
    void configurableMaxAge() {
        Built x = make(Map.of("dm1", NOW - 5 * H, "fam", NOW), null, 0.0, c -> c.catchupMaxAge(Duration.ofHours(1)), null);
        x.mm().userPost("d1", "dm1", "u_alice", "2 h alt", NOW - 2 * H);
        x.bot().catchUp();
        waitIdle(x.bot());
        assertThat(x.mm().afterCalls).contains(Map.entry("dm1", NOW - H));
        assertThat(x.oc().prompts).isEmpty();
    }

    @Test
    void maxAgeZeroCatchesUpNothing() {
        Built x = make(Map.of("dm1", NOW - 5 * H, "fam", NOW), null, 0.0, c -> c.catchupMaxAge(Duration.ZERO), null);
        x.mm().userPost("d1", "dm1", "u_alice", "eben", NOW - 1);
        x.bot().catchUp();
        waitIdle(x.bot());
        assertThat(x.oc().prompts).isEmpty();
    }

    @Test
    void dedupeWithLivePost() {
        Built x = make(Map.of("dm1", NOW - H, "fam", NOW));
        x.bot().onPost(x.mm().userPost("d1", "dm1", "u_alice", "Hallo", NOW - 1000)); // kam schon live
        waitIdle(x.bot());
        assertThat(x.bot().catchUp()).isTrue();
        waitIdle(x.bot());
        assertThat(x.oc().prompts).hasSize(1);
        assertThat(replies(x.mm()).getFirst().message).doesNotStartWith(Catchup.LATE_NOTE);
    }

    @Test
    void channelWithoutCursor() {
        Built x = make(Map.of("fam", NOW)); // dm1, neu und alt fehlen
        ((ObjectNode) x.mm().channels.get(0)).put("create_at", NOW - 2 * H); // DM während der Pause entstanden
        x.mm().channels.add(Json.parse("{\"id\":\"neu\",\"type\":\"O\",\"create_at\":1}"));
        x.mm().channels.add(Json.parse("{\"id\":\"alt\",\"type\":\"O\",\"create_at\":1}"));
        x.mm().userPost("d1", "dm1", "u_alice", "erste DM", NOW - 2 * H + 10);
        ObjectNode added = Json.object().put("id", "j").put("channel_id", "neu").put("type", "system_add_to_channel")
                .put("user_id", "adm").put("create_at", NOW - H).put("message", "");
        added.set("props", Json.tree(Map.of("addedUserId", "bot1")));
        x.mm().put("neu", raw("n1", "neu", "u_bob", "@captain vor dem Beitritt", NOW - H - 10), added,
                raw("n2", "neu", "u_bob", "@captain nach dem Beitritt", NOW - H + 10));
        // Beitritt länger her: Höchstalter, nur Adressiertes
        x.mm().put("alt", raw("a1", "alt", "u_bob", "Plauderei", NOW - 3 * H),
                raw("a2", "alt", "u_bob", "@captain länger her", NOW - 2 * H));
        assertThat(x.bot().catchUp()).isTrue();
        waitIdle(x.bot());
        long oldest = NOW - 24 * H;
        assertThat(x.mm().afterCalls).contains(Map.entry("dm1", oldest), Map.entry("neu", oldest), Map.entry("alt", oldest));
        assertThat(x.oc().texts()).containsExactlyInAnyOrder("Bobby: länger her", "Bobby: nach dem Beitritt", "erste DM");
        assertThat(x.bot().cursors.get("neu")).isEqualTo(NOW - H + 10);
        assertThat(x.bot().cursors.get("alt")).isEqualTo(NOW - 2 * H);
    }

    @Test
    void threadReplyNeedsParticipation() {
        Built x = make(Map.of("dm1", NOW, "fam", NOW - H));
        x.mm().roots.put("r1", List.of(raw("r1", "fam", "u_bob", "Frage", 1), raw("x1", "fam", "bot1", "Antwort", 2)));
        x.mm().userPost("t1", "fam", "u_alice", "Nachfrage im Thread", NOW - 100, "r1");
        x.mm().userPost("t2", "fam", "u_alice", "fremder Thread", NOW - 90, "r2");
        x.bot().catchUp();
        waitIdle(x.bot());
        assertThat(x.oc().prompts).hasSize(1);
        assertThat(x.oc().prompts.getFirst().text()).endsWith("\n\nAlice Test: Nachfrage im Thread");
        assertThat(replies(x.mm())).hasSize(1);
        assertThat(replies(x.mm()).getFirst().rootId).isEqualTo("r1");
        assertThat(replies(x.mm()).getFirst().message).startsWith(Catchup.LATE_NOTE);
    }

    @Test
    void lateNoteOnCommandButNotOnError() {
        Built x = make(Map.of("dm1", NOW - H, "fam", NOW - H));
        x.mm().userPost("d1", "dm1", "u_alice", "!help", NOW - 100);
        x.mm().userPost("f1", "fam", "u_bob", "@captain kaputt", NOW - 90);
        x.oc().behaviour.add(call -> {
            throw new IllegalStateException("weg");
        });
        x.bot().catchUp();
        waitIdle(x.bot());
        Map<String, String> msgs = new HashMap<>();
        replies(x.mm()).forEach(p -> msgs.put(p.channelId, p.message));
        assertThat(msgs.get("dm1")).startsWith(Catchup.LATE_NOTE + "\n\n**Captain**");
        assertThat(msgs.get("fam")).startsWith("⚠️ Fehler"); // kein Hinweis vor der Fehlernotiz
    }

    @Test
    void livePostsBufferedUntilCatchUpDone() throws InterruptedException {
        List<String> routed = java.util.Collections.synchronizedList(new ArrayList<>());
        Built x = make(Map.of("dm1", NOW - H, "fam", NOW), null, 0.0, c -> c, p -> routed.add(p.id()));
        Post missed = x.mm().userPost("d1", "dm1", "u_alice", "verpasst", NOW - 100);
        CountDownLatch gate = new CountDownLatch(1);
        x.mm().channelsHook = () -> {
            BotTest.await(gate);
            return x.mm().channelList();
        };
        x.bot().onHello(); // neue Verbindung → Nachholen startet, Live wird gepuffert
        Post live = new Post("L1", "dm1", "D", "", "u_alice", "alice", "live", List.of(), false, NOW + 10);
        x.bot().onWsPost(live);
        x.bot().onWsPost(missed); // kam per REST und live
        assertThat(routed).isEmpty(); // noch gepuffert
        gate.countDown();
        assertThat(x.bot().catchupDone.await(5)).isTrue();
        waitIdle(x.bot());
        assertThat(routed.subList(0, 2)).containsExactly("d1", "L1"); // erst nachgeholt, dann Puffer
        assertThat(count(prompted(x.oc()), "verpasst")).isEqualTo(1);
        assertThat(prompted(x.oc())).contains("live");
        assertThat(replies(x.mm()).getFirst().message).startsWith(Catchup.LATE_NOTE); // Antwort auf d1 zuerst
        x.bot().onWsPost(new Post("L2", "dm1", "D", "", "u_alice", "alice", "direkt", List.of(), false, NOW + 20));
        assertThat(routed.getLast()).isEqualTo("L2"); // nach dem Nachholen wieder direkt
    }

    @Test
    void secondHelloDuringCatchUpRunsAgain() {
        Built x = make(Map.of("dm1", NOW - H, "fam", NOW));
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        x.mm().channelsHook = () -> {
            if (calls.incrementAndGet() == 1) {
                BotTest.await(gate);
            }
            return x.mm().channelList();
        };
        x.bot().onHello();
        waitFor(() -> calls.get() > 0, "erstes Nachholen");
        x.bot().onHello(); // Reconnect ohne Replay, während noch nachgeholt wird
        gate.countDown();
        waitFor(() -> calls.get() == 2 && x.bot().catchupOk.isSet(), "zweites Nachholen");
    }

    // -- Neustart: nichts verlieren, nichts doppelt ------------------------------

    /** (a) Stopp während eine Antwort streamt und eine weitere wartet. */
    @Test
    void stopWhileStreamingAndQueuedAnswersOnceAfterRestart() throws InterruptedException {
        Built x1 = make(Map.of("dm1", NOW - H, "fam", NOW - H));
        HistMattermost mm = x1.mm();
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch streaming = new CountDownLatch(1);
        x1.oc().behaviour.add(call -> {
            call.delta("Teil");
            streaming.countDown();
            BotTest.await(gate);
            throw new PromptInterruptedException("abgebrochen"); // opencode nach abort
        });
        x1.bot().onPost(mm.userPost("d1", "dm1", "u_alice", "Erste Frage", NOW + 1));
        assertThat(streaming.await(5, TimeUnit.SECONDS)).isTrue();
        waitFor(() -> !mm.botMessages("dm1").isEmpty(), "Zwischenstand");
        x1.bot().onPost(mm.userPost("d2", "dm1", "u_alice", "Zweite Frage", NOW + 2)); // wartet (⏳)
        x1.bot().stop(); // SIGTERM
        gate.countDown();
        x1.bot().waitQuiet(5);
        List<JsonNode> partial = mm.botMessages("dm1");
        assertThat(partial).hasSize(1);
        assertThat(Json.str(partial.getFirst(), "message")).endsWith(Catchup.INTERRUPTED_NOTE);
        assertThat(x1.oc().aborted()).isNotEmpty(); // laufende Antwort abgebrochen
        JsonNode saved = read(cursorPath());
        assertThat(saved.get("channels").get("dm1").asLong()).isEqualTo(NOW + 1 - 1);
        assertThat(saved.get("answered")).isEmpty();

        Built x2 = restart(mm); // Neustart, gleicher Server
        assertThat(x2.bot().catchUp()).isTrue();
        waitIdle(x2.bot());
        String text = prompted(x2.oc());
        assertThat(count(text, "Erste Frage")).isEqualTo(1);
        assertThat(count(text, "Zweite Frage")).isEqualTo(1);
        List<JsonNode> finals = mm.botMessages("dm1").stream().filter(p -> !Catchup.isUnfinished(Json.str(p, "message"))).toList();
        assertThat(finals).hasSize(x2.oc().prompts.size());
        assertThat(Json.str(finals.getFirst(), "message")).startsWith(Catchup.LATE_NOTE);
        x2.bot().cursors.flush();
        assertThat(read(cursorPath()).get("channels").get("dm1").asLong()).isGreaterThan(NOW + 2);
        assertThat(x2.bot().catchUp()).isTrue(); // nochmal: nichts doppelt
        waitIdle(x2.bot());
        assertThat(x2.oc().prompts).hasSize(finals.size());
    }

    /** (b) Antwort steht, Cursor/IDs noch nicht geschrieben → kein zweites Mal. */
    @Test
    void crashAfterAnswerBeforeCursorSavedNoDouble() {
        Built x1 = make(Map.of("dm1", NOW - H, "fam", NOW - H), null, 60, c -> c, null);
        HistMattermost mm = x1.mm();
        x1.bot().onPost(mm.userPost("d1", "dm1", "u_alice", "Frage", NOW + 1));
        x1.bot().onPost(mm.userPost("f1", "fam", "u_bob", "@captain Frage", NOW + 2));
        waitIdle(x1.bot());
        assertThat(x1.oc().prompts).hasSize(2);
        // Absturz: nichts gesichert
        assertThat(read(cursorPath()).get("channels")).isEqualTo(Json.parse(Json.write(Map.of("dm1", NOW - H, "fam", NOW - H))));
        Built x2 = restart(mm);
        assertThat(x2.bot().catchUp()).isTrue();
        waitIdle(x2.bot());
        assertThat(x2.oc().prompts).isEmpty();
        assertThat(x2.bot().cursors.isAnswered("d1")).isTrue();
        assertThat(x2.bot().cursors.isAnswered("f1")).isTrue();
    }

    @Test
    void answeredIdsSkipOnCatchUp() {
        Built x = make(Map.of("dm1", NOW - H, "fam", NOW));
        x.mm().userPost("d1", "dm1", "u_alice", "Frage", NOW - 100);
        x.bot().cursors.markAnswered("d1");
        x.bot().catchUp();
        waitIdle(x.bot());
        assertThat(x.oc().prompts).isEmpty();
    }

    /** (c) Nachholen scheitert für einen Kanal → Cursor bleibt, Wiederholung holt nach. */
    @Test
    void failedChannelIsFrozenAndRetried() {
        Built x = make(Map.of("dm1", NOW - H, "fam", NOW - H));
        x.mm().userPost("f1", "fam", "u_bob", "@captain verpasst", NOW - 100);
        x.mm().userPost("d1", "dm1", "u_alice", "verpasst", NOW - 90);
        x.mm().failChannels.add("fam");
        x.bot().onHello();
        assertThat(x.bot().catchupDone.await(5)).isTrue();
        waitIdle(x.bot());
        assertThat(prompted(x.oc())).isEqualTo("verpasst"); // DM ging
        // live geht weiter, der Cursor von fam wandert trotzdem nicht über die Lücke
        x.bot().onWsPost(x.mm().userPost("f2", "fam", "u_bob", "nur Plauderei", NOW + 5));
        com.ewerlin.captain.util.Clock.sleep(0.2); // mehrere erfolglose Wiederholungen
        assertThat(x.bot().cursors.snapshot().get("fam")).isEqualTo(NOW - H);
        assertThat(x.bot().catchupOk.isSet()).isFalse();
        x.mm().failChannels.clear();
        assertThat(x.bot().catchupOk.await(5)).isTrue();
        waitIdle(x.bot());
        assertThat(count(prompted(x.oc()), "Bobby: verpasst")).isEqualTo(1);
        assertThat(x.bot().cursors.snapshot().get("fam")).isEqualTo(NOW + 5);
    }

    @Test
    void failedChannelListFreezesAll() {
        Built x = make(Map.of("dm1", NOW - H, "fam", NOW - H));
        x.mm().failList = true;
        x.mm().userPost("d1", "dm1", "u_alice", "verpasst", NOW - 90);
        x.bot().onHello();
        assertThat(x.bot().catchupDone.await(5)).isTrue();
        x.bot().onWsPost(x.mm().userPost("d2", "dm1", "u_alice", "live", NOW + 5));
        waitIdle(x.bot());
        x.bot().cursors.flush();
        assertThat(read(cursorPath()).get("channels")).isEqualTo(Json.parse(Json.write(Map.of("dm1", NOW - H, "fam", NOW - H))));
        x.mm().failList = false;
        assertThat(x.bot().catchupOk.await(5)).isTrue();
        waitIdle(x.bot());
        assertThat(count(prompted(x.oc()), "verpasst")).isEqualTo(1);
        assertThat(count(prompted(x.oc()), "live")).isEqualTo(1);
    }

    /** (d) Erster Start scheitert → keine Datei → nächster Start wieder „erster Start“. */
    @Test
    void firstStartWithFailingCatchUpNoFloodLater() {
        Built x1 = make(null);
        HistMattermost mm = x1.mm();
        mm.failList = true;
        mm.userPost("d0", "dm1", "u_alice", "uralt", NOW - 20 * H);
        x1.bot().onHello();
        assertThat(x1.bot().catchupDone.await(5)).isTrue();
        x1.bot().onWsPost(mm.userPost("d1", "dm1", "u_alice", "live", NOW + 1));
        waitIdle(x1.bot());
        x1.bot().stop();
        assertThat(cursorPath()).doesNotExist();
        mm.failList = false;
        Built x2 = restart(mm);
        assertThat(x2.bot().catchUp()).isTrue();
        waitIdle(x2.bot());
        assertThat(x2.oc().prompts).isEmpty(); // keine Flut aus 24 h DMs
        assertThat(read(cursorPath()).get("initialized").asBoolean()).isTrue();
    }

    /**
     * Nutzer schickt d1, dann d2, bevor die Antwort auf d1 angelegt ist; d1 wird fertig beantwortet
     * (Antwort-Post jünger als d2), d2 streamt, SIGTERM → nach dem Neustart wird d2 beantwortet
     * (nicht wegen der jüngeren Antwort auf d1 übersprungen).
     */
    @Test
    void queuedPostNotLostWhenEarlierAnswerIsNewer() throws InterruptedException {
        Built x1 = make(Map.of("dm1", NOW - H, "fam", NOW - H));
        HistMattermost mm = x1.mm();
        CountDownLatch gate1 = new CountDownLatch(1);
        CountDownLatch started1 = new CountDownLatch(1);
        CountDownLatch gate2 = new CountDownLatch(1);
        CountDownLatch streaming2 = new CountDownLatch(1);
        x1.oc().behaviour.add(call -> {
            started1.countDown();
            BotTest.await(gate1);
            call.delta("Antwort 1");
            return "Antwort 1";
        });
        x1.oc().behaviour.add(call -> {
            call.delta("Teil 2");
            streaming2.countDown();
            BotTest.await(gate2);
            throw new PromptInterruptedException("abgebrochen");
        });
        x1.bot().onPost(mm.userPost("d1", "dm1", "u_alice", "Erste Frage", NOW + 1));
        assertThat(started1.await(5, TimeUnit.SECONDS)).isTrue();
        x1.bot().onPost(mm.userPost("d2", "dm1", "u_alice", "Zweite Frage", NOW + 2));
        gate1.countDown();
        assertThat(streaming2.await(5, TimeUnit.SECONDS)).isTrue();
        x1.bot().stop();
        gate2.countDown();
        x1.bot().waitQuiet(5);
        Built x2 = restart(mm);
        assertThat(x2.bot().catchUp()).isTrue();
        waitIdle(x2.bot());
        assertThat(prompted(x2.oc())).isEqualTo("Zweite Frage"); // d1 nicht noch einmal, d2 nicht verloren
    }

    /** Wie oben, aber die frühere Antwort ist eine Befehlsantwort (!help). */
    @Test
    void commandReplyDoesNotCoverLaterPost() throws InterruptedException {
        Built x1 = make(Map.of("dm1", NOW - H, "fam", NOW - H), null, 60, c -> c, null);
        HistMattermost mm = x1.mm();
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch streaming = new CountDownLatch(1);
        x1.oc().behaviour.add(call -> {
            call.delta("Teil");
            streaming.countDown();
            BotTest.await(gate);
            throw new PromptInterruptedException("abgebrochen");
        });
        x1.bot().onPost(mm.userPost("d1", "dm1", "u_alice", "Frage", NOW + 1));
        assertThat(streaming.await(5, TimeUnit.SECONDS)).isTrue();
        x1.bot().onPost(mm.userPost("d2", "dm1", "u_alice", "!help", NOW + 2)); // sofort beantwortet
        waitFor(() -> mm.botMessages("dm1").size() == 2, "Hilfe");
        // Absturz: nichts gespeichert (kein close), laufende Antwort endet ohne Abschluss
        x1.bot().stopping.set();
        gate.countDown();
        x1.bot().waitQuiet(5);
        assertThat(read(cursorPath()).get("channels").get("dm1").asLong()).isEqualTo(NOW - H);
        Built x2 = restart(mm);
        assertThat(x2.bot().catchUp()).isTrue();
        waitIdle(x2.bot());
        assertThat(prompted(x2.oc())).isEqualTo("Frage"); // !help nicht doppelt, d1 nicht verloren
    }

    /** Antwort über mehrere Posts: nur der letzte Teil trägt die IDs, erst am Ende. */
    @Test
    void multipartAnswerMarkedOnlyWhenComplete() {
        Built x = make(Map.of("dm1", NOW - H, "fam", NOW - H));
        String longText = "x".repeat(16000) + "\n" + "y".repeat(100);
        x.oc().behaviour.add(call -> longText);
        x.bot().onPost(x.mm().userPost("d1", "dm1", "u_alice", "Lang bitte", NOW + 1));
        waitIdle(x.bot());
        List<JsonNode> parts = x.mm().botMessages("dm1");
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).get("props")).isEmpty();
        assertThat(parts.get(1).get("props")).isEqualTo(Json.parse("{\"captain_answers\": [\"d1\"]}"));
        assertThat(Catchup.answeredIn(List.of(parts.get(0)), "bot1")).isEmpty(); // ein Teil allein zählt nicht
        assertThat(new HashSet<>(Catchup.answeredIn(parts, "bot1"))).containsExactly("d1");
    }
}
