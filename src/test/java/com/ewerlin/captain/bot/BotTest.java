package com.ewerlin.captain.bot;

import static com.ewerlin.captain.bot.BotTestSupport.post;
import static com.ewerlin.captain.bot.BotTestSupport.slash;
import static com.ewerlin.captain.bot.BotTestSupport.waitFor;
import static com.ewerlin.captain.bot.BotTestSupport.waitIdle;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.ewerlin.captain.api.AgentRejectedException;
import com.ewerlin.captain.config.CaptainConfig;
import com.ewerlin.captain.mattermost.Post;
import com.ewerlin.captain.opencode.OpencodeException;
import com.ewerlin.captain.opencode.PromptInterruptedException;
import com.ewerlin.captain.opencode.Rule;
import com.ewerlin.captain.opencode.SessionGoneException;
import com.ewerlin.captain.policy.RuleEval;
import com.ewerlin.captain.util.Json;

import tools.jackson.databind.JsonNode;

/** Unit-Tests für {@link Bot} mit Fake-Clients (ohne Netz). */
class BotTest {

    @TempDir
    Path tmp;
    Bot b;
    FakeMattermost mm;
    FakeOpencode oc;
    SessionStore store;

    @BeforeEach
    void setUp() {
        CaptainConfig cfg = CaptainConfig.builder()
                .mmUrl("http://mm").mmBotToken("t").opencodeUrl("http://oc")
                .opencodeModel("llm/modell-a")
                .sessionsDir(slash(tmp.resolve("ws")))
                .dataDir(tmp.resolve("data").toString())
                .systemPrompt("Du bist Testkapitän.")
                .build();
        mm = new FakeMattermost();
        oc = new FakeOpencode();
        store = new SessionStore(Path.of(cfg.dataDir(), "sessions.json"));
        b = new Bot(cfg, mm, oc, store);
        BotTestSupport.countingIds(b);
        BotTestSupport.fifoSide(b);
    }

    @AfterEach
    void tearDown() {
        b.stop();
        b.awaitStopped(5);
    }

    static JsonNode raw(String json) {
        return Json.parse(json);
    }

    // ---------------------------------------------------------------- Hilfen

    @Test
    void parseCommand() {
        assertThat(Bot.parseCommand("!neu")).isEqualTo(new Bot.Command("neu", ""));
        assertThat(Bot.parseCommand("!Modell opencode/x high")).isEqualTo(new Bot.Command("modell", "opencode/x high"));
        assertThat(Bot.parseCommand("!!!")).isNull();
        assertThat(Bot.parseCommand("hallo !neu")).isNull();
    }

    @Test
    void displayName() {
        assertThat(Bot.displayName(FakeMattermost.USERS.get("u_alice"))).isEqualTo("Alice Test");
        assertThat(Bot.displayName(FakeMattermost.USERS.get("u_bob"))).isEqualTo("Bobby");
        assertThat(Bot.displayName(raw("{\"username\":\"x\"}"))).isEqualTo("x");
    }

    // ---------------------------------------------------------------- Routing

    @Test
    void dmAnswerFlatAndFinalFromReturnValue() {
        b.onPost(post("Hallo").build());
        waitIdle(b);
        assertThat(oc.prompts.getFirst().text()).isEqualTo("Hallo"); // DM: Text direkt
        assertThat(oc.prompts.getFirst().options().model()).isEqualTo("llm/modell-a");
        // Endstand kommt aus dem Rückgabewert, nicht aus dem letzten Delta
        assertThat(mm.finalMessages()).containsExactly("Antwort auf: Hallo");
        FakeMattermost.Sent reply = mm.sent().getFirst();
        assertThat(reply.rootId).isNull();
        assertThat(reply.channelId).isEqualTo("c1");
        Map<String, String> entry = store.get("matter-dm:c1");
        assertThat(entry.get("opencode_session_id")).isEqualTo("ses_1");
        assertThat(entry.get("directory")).isEqualTo(b.cfg.sessionsDir() + "/ses_1");
        assertThat(Path.of(entry.get("directory"))).isDirectory();
        assertThat(oc.prompts.getFirst().options().directory()).isEqualTo(entry.get("directory"));
    }

    @Test
    void groupMentionOpensThreadWithNamePrefix() {
        Post p = post("@captain Hi").type("G").ch("g1").user("u_bob").build();
        b.onPost(p);
        waitIdle(b);
        assertThat(oc.prompts.getFirst().text()).isEqualTo("Bobby: Hi"); // Erwähnung entfernt
        assertThat(store.get("matter-th:" + p.id())).isNotNull();
        assertThat(oc.created.getFirst().directory()).endsWith("/ws/ses_1");
        FakeMattermost.Sent reply = mm.sent().getFirst();
        assertThat(reply.rootId).isEqualTo(p.id());
        assertThat(reply.channelId).isEqualTo("g1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"O", "P", "G"})
    void channelWithoutMentionIgnored(String ctype) {
        b.onPost(post("Hallo zusammen").type(ctype).ch("c5").build());
        b.onPost(post("!help").type(ctype).ch("c5").build());
        b.onPost(post("Antwort im fremden Thread").type(ctype).ch("c5").root("fremd").build());
        waitIdle(b);
        assertThat(oc.prompts).isEmpty();
        assertThat(mm.posts).isEmpty();
    }

    @Test
    void channelMentionGetsHistoryAndFollowupsNeedNoMention() {
        mm.channel = new java.util.ArrayList<>(List.of(
                raw("{\"id\":\"o1\",\"user_id\":\"u_alice\",\"message\":\"alt, schon bekannt\",\"create_at\":1}"),
                raw("{\"id\":\"t0\",\"user_id\":\"u_bob\",\"message\":\"@captain frühere Frage\",\"create_at\":2}"),
                raw("{\"id\":\"b0\",\"user_id\":\"bot1\",\"root_id\":\"t0\",\"message\":\"frühere Antwort\",\"create_at\":3}"),
                raw("{\"id\":\"n1\",\"user_id\":\"u_alice\",\"message\":\"Ich mag Mangos.\",\"create_at\":4}"),
                raw("{\"id\":\"n2\",\"user_id\":\"u_robot\",\"message\":\"Bot-Rauschen\",\"create_at\":5}"),
                raw("{\"id\":\"n3\",\"user_id\":\"u_bob\",\"message\":\"Ich Birnen.\",\"create_at\":6}"),
                raw("{\"id\":\"n4\",\"user_id\":\"u_bob\",\"root_id\":\"t0\",\"message\":\"Thread-Kram\",\"create_at\":7}")));
        Post p = post("@captain Wer mag was?").type("O").ch("c5").user("u_bob").at(8).build();
        mm.channel.add(Json.object().put("id", p.id()).put("user_id", "u_bob").put("message", p.message()).put("create_at", 8));
        b.onPost(p);
        waitIdle(b);
        assertThat(oc.prompts.getFirst().text()).isEqualTo(
                "[Bisherige Unterhaltung im Kanal seit deiner letzten Antwort hier – nur Vorgeschichte]\n"
                        + "Alice Test: Ich mag Mangos.\nBobby: Ich Birnen.\n[Ende der Vorgeschichte]\n\n"
                        + "Bobby: Wer mag was?");
        // Folgeantwort im Thread ohne Erwähnung: gleiche Session, keine Vorgeschichte
        int calls = mm.threadCalls.get();
        b.onPost(post("Und du?").type("O").ch("c5").root(p.id()).build());
        waitIdle(b);
        assertThat(mm.threadCalls.get()).isEqualTo(calls); // Beteiligung bekannt, kein REST
        assertThat(oc.prompts.get(1).sid()).isEqualTo(oc.prompts.getFirst().sid());
        assertThat(oc.prompts.get(1).text()).isEqualTo("Alice Test: Und du?");
        assertThat(mm.sent().stream().map(s -> s.rootId).toList()).containsExactly(p.id(), p.id());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "-", value = {"1|8000|Bobby: Ich Birnen.", "50|19|Bobby: Ich Birnen.", "0|8000|-"})
    void channelHistoryLimitsFromConfig(int posts, int chars, String expected) {
        b.cfg = b.cfg.toBuilder().historyMaxPosts(posts).historyMaxChars(chars).build();
        mm.channel = List.of(
                raw("{\"id\":\"n1\",\"user_id\":\"u_alice\",\"message\":\"Ich mag Mangos.\",\"create_at\":4}"),
                raw("{\"id\":\"n3\",\"user_id\":\"u_bob\",\"message\":\"Ich Birnen.\",\"create_at\":6}"));
        b.onPost(post("@captain Wer mag was?").type("O").ch("c5").user("u_bob").at(8).build());
        waitIdle(b);
        String text = oc.prompts.getFirst().text();
        if (expected == null) {
            assertThat(text).isEqualTo("Bobby: Wer mag was?");
        } else {
            assertThat(text).contains("nur Vorgeschichte]\n" + expected + "\n[Ende der Vorgeschichte]");
        }
    }

    @Test
    void threadParticipationLookedUpAfterRestart() {
        mm.roots.put("r7", List.of(
                raw("{\"id\":\"r7\",\"user_id\":\"u_bob\",\"message\":\"Frage\",\"create_at\":1}"),
                raw("{\"id\":\"x7\",\"user_id\":\"bot1\",\"message\":\"Antwort\",\"create_at\":2}")));
        b.onPost(post("weiter").type("P").ch("c5").root("r7").build());
        b.onPost(post("noch was").type("P").ch("c5").root("r8").build()); // ohne Captain
        waitIdle(b);
        assertThat(oc.prompts).hasSize(1);
        assertThat(oc.prompts.getFirst().text()).endsWith("Alice Test: weiter");
    }

    @Test
    void helpInChannelWithMentionRepliesInThread() {
        Post p = post("@captain !help").type("O").ch("c5").build();
        b.onPost(p);
        waitIdle(b);
        FakeMattermost.Sent reply = mm.sent().getFirst();
        assertThat(mm.posts).hasSize(1);
        assertThat(reply.rootId).isEqualTo(p.id());
        assertThat(reply.message).contains("!neu", "@captain");
        // im Thread geht !hilfe danach ohne Erwähnung
        b.onPost(post("!hilfe").type("O").ch("c5").root(p.id()).build());
        waitIdle(b);
        assertThat(mm.posts).hasSize(2);
        assertThat(oc.prompts).isEmpty();
    }

    @Test
    void neuInThreadResetsThreadSession() {
        Post p = post("@captain los").type("O").ch("c5").build();
        b.onPost(p);
        waitIdle(b);
        assertThat(store.get("matter-th:" + p.id()).get("opencode_session_id")).isEqualTo("ses_1");
        b.onPost(post("!neu").type("O").ch("c5").root(p.id()).build());
        waitIdle(b);
        assertThat(store.get("matter-th:" + p.id())).isNull();
        b.onPost(post("weiter").type("O").ch("c5").root(p.id()).build()); // ohne Erwähnung
        waitIdle(b);
        assertThat(oc.prompts.getLast().sid()).isEqualTo("ses_2");
    }

    @Test
    void channelModelAppliesToNewThreads() {
        b.onPost(post("@captain !modell opencode/x high").type("O").ch("c5").build());
        waitIdle(b);
        assertThat(store.get("matter-ch:c5")).isEqualTo(Map.of("model", "opencode/x", "variant", "high"));
        Post p = post("@captain Hallo").type("O").ch("c5").build();
        b.onPost(p);
        waitIdle(b);
        assertThat(oc.prompts.getLast().options().model()).isEqualTo("opencode/x");
        assertThat(oc.prompts.getLast().options().variant()).isEqualTo("high");
        b.onPost(post("!modell llm/modell-a").type("O").ch("c5").root(p.id()).build());
        waitIdle(b);
        b.onPost(post("weiter").type("O").ch("c5").root(p.id()).build());
        waitIdle(b);
        assertThat(oc.prompts.getLast().options().model()).isEqualTo("llm/modell-a");
        assertThat(oc.prompts.getLast().options().variant()).isNull();
    }

    @Test
    void threadReplyUsesRootAndContext() {
        Post p = post("Und morgen?").type("P").ch("c2").root("r1").build();
        mm.roots.put("r1", List.of(
                raw("{\"id\":\"r1\",\"user_id\":\"u_bob\",\"message\":\"Wie wird das Wetter?\",\"create_at\":1}"),
                raw("{\"id\":\"x1\",\"user_id\":\"bot1\",\"message\":\"Sonnig.\",\"create_at\":2}"),
                raw("{\"id\":\"x2\",\"user_id\":\"u_bob\",\"message\":\"joined\",\"type\":\"system_x\",\"create_at\":3}"),
                Json.object().put("id", p.id()).put("user_id", "u_alice").put("message", p.message()).put("create_at", 4)));
        b.onPost(p);
        waitIdle(b);
        assertThat(oc.prompts.getFirst().text()).isEqualTo(
                "[Bisheriger Verlauf dieses Threads]\n"
                        + "Bobby: Wie wird das Wetter?\nCaptain (du): Sonnig.\n"
                        + "[Ende des Verlaufs]\n\nAlice Test: Und morgen?");
        assertThat(store.get("matter-th:r1")).isNotNull();
        assertThat(mm.sent().getFirst().rootId).isEqualTo("r1");
        // zweite Nachricht im Thread: gleiche Session, kein erneuter Kontext
        b.onPost(post("Danke").type("P").ch("c2").root("r1").build());
        waitIdle(b);
        assertThat(oc.prompts.get(1).sid()).isEqualTo(oc.prompts.getFirst().sid());
        assertThat(oc.prompts.get(1).text()).isEqualTo("Alice Test: Danke");
    }

    @Test
    void filters() {
        b.onPost(post("von Bot-Prop").fromBot().build());
        b.onPost(post("von Bot-User").user("u_robot").build());
        b.onPost(post("   ").build());
        Post p = post("einmal").build();
        b.onPost(p);
        b.onPost(p); // doppelt zugestellt
        waitIdle(b);
        assertThat(oc.texts()).containsExactly("einmal");
    }

    @Test
    void attachmentsDownloadedIntoSessionDir() {
        b.onPost(post("Schau mal").files("f1").build());
        waitIdle(b);
        FakeOpencode.Call call = oc.prompts.getFirst();
        assertThat(call.options().files()).containsExactly(call.options().directory() + "/f1.txt");
        assertThat(call.text()).contains("f1.txt");
        assertThat(mm.maxBytes).isEqualTo(20L * 1024 * 1024); // Default MAX_ATTACHMENT_MB
    }

    @Test
    void attachmentTooLargeSkippedAndReported() {
        b.cfg = b.cfg.toBuilder().maxAttachmentMb(2.5).build();
        b.onPost(post("Lies das").files("big1", "f2").build());
        waitIdle(b);
        assertThat(mm.maxBytes).isEqualTo((long) (2.5 * 1024 * 1024));
        FakeOpencode.Call call = oc.prompts.getFirst();
        assertThat(call.options().files()).containsExactly(call.options().directory() + "/f2.txt"); // nur der kleine
        assertThat(call.text()).contains("big1.pdf", "nicht geladen");
        assertThat(mm.finalMessages()).singleElement().asString()
                .endsWith("_(Anhang „big1.pdf“ nicht geladen: größer als 2,5 MB)_");
        assertThat(Path.of(call.options().directory(), "big1.pdf")).doesNotExist();
    }

    // ---------------------------------------------------------------- Warteschlange

    @Test
    void serializedAndBundled() throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        oc.behaviour.add(call -> {
            running.countDown();
            await(gate);
            return "erste";
        });
        Post first = post("@captain eins").type("G").ch("g1").build();
        b.onPost(first);
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        Post p2 = post("zwei").type("G").ch("g1").user("u_bob").root(first.id()).build();
        Post p3 = post("drei").type("G").ch("g1").root(first.id()).build();
        b.onPost(p2);
        b.onPost(p3);
        b.onPost(post("anderswo").ch("c9").build()); // andere Session läuft parallel
        waitFor(() -> oc.prompts().size() >= 2, "zweiter Prompt");
        assertThat(oc.prompts().get(1).text()).isEqualTo("anderswo");
        assertThat(oc.prompts()).hasSize(2); // g1 wartet
        gate.countDown();
        waitIdle(b);
        List<String> texts = oc.texts();
        assertThat(texts.get(2)).isEqualTo("Bobby: zwei\nAlice Test: drei"); // gebündelt, ein Prompt
        assertThat(texts).hasSize(3);
        assertThat(mm.reactions).contains(List.of("add", p2.id(), Bot.QUEUED_EMOJI));
        assertThat(mm.reactions).contains(List.of("remove", p3.id(), Bot.QUEUED_EMOJI));
    }

    @Test
    void neuKeepsOrder() throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        oc.behaviour.add(call -> {
            running.countDown();
            await(gate);
            return "ok";
        });
        b.onPost(post("a").build());
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        b.onPost(post("b").build());
        b.onPost(post("!neu").build());
        b.onPost(post("c").build());
        gate.countDown();
        waitIdle(b);
        assertThat(oc.prompts().stream().map(c -> c.sid() + ":" + c.text()).toList())
                .containsExactly("ses_1:a", "ses_1:b", "ses_2:c");
        assertThat(mm.finalMessages()).anyMatch(m -> m.contains("Neue Unterhaltung"));
    }

    // ---------------------------------------------------------------- Fehler

    @Test
    void sessionGoneRetriesOnceWithNewSession() throws IOException {
        String old = b.sessionDir("ses_alt");
        Files.createDirectories(Path.of(old));
        store.set("matter-dm:c1", "ses_alt", old);
        oc.behaviour.add(call -> {
            call.delta("halb");
            throw new SessionGoneException("weg");
        });
        b.onPost(post("Hallo").build());
        waitIdle(b);
        assertThat(oc.prompts().stream().map(FakeOpencode.Call::sid).toList()).containsExactly("ses_alt", "ses_1");
        assertThat(store.get("matter-dm:c1").get("opencode_session_id")).isEqualTo("ses_1");
        assertThat(mm.finalMessages().getLast()).isEqualTo("Antwort auf: Hallo");
        assertThat(Path.of(old)).doesNotExist(); // Selbstheilung räumt das alte Verzeichnis ab
        assertThat(oc.prompts.get(1).options().directory()).isEqualTo(b.sessionDir("ses_1"));
    }

    @Test
    void sessionGoneTwiceGivesUp() {
        oc.behaviour.add(call -> {
            throw new SessionGoneException("weg");
        });
        oc.behaviour.add(call -> {
            throw new SessionGoneException("weg");
        });
        b.onPost(post("Hallo").build());
        waitIdle(b);
        assertThat(oc.prompts).hasSize(2);
        assertThat(mm.finalMessages().getLast()).contains("opencode-Fehler");
    }

    @Test
    void timeoutNotRetriedAndPartialKept() {
        oc.behaviour.add(call -> {
            call.delta("Teilantwort");
            throw new OpencodeException("Keine Rückmeldung von opencode seit 600 s – abgebrochen");
        });
        b.onPost(post("x").build());
        waitIdle(b);
        assertThat(oc.prompts).hasSize(1);
        String msg = mm.finalMessages().getFirst();
        assertThat(mm.finalMessages()).hasSize(1);
        assertThat(msg).startsWith("Teilantwort\n\n⚠️ opencode-Fehler: Keine Rückmeldung").doesNotContain(Bot.CURSOR);
    }

    @Test
    void interruptedIsNotAnError() {
        oc.behaviour.add(call -> {
            call.delta("Angefangen");
            throw new PromptInterruptedException("abgebrochen");
        });
        b.onPost(post("x").build());
        waitIdle(b);
        assertThat(mm.finalMessages()).containsExactly("Angefangen\n\n_(abgebrochen)_");
    }

    @Test
    void statusAndCursorWhileRunning() {
        List<List<String>> seen = new java.util.ArrayList<>();
        oc.behaviour.add(call -> {
            call.status("⚙️ Ich führe Befehle aus…");
            seen.add(mm.finalMessages());
            call.delta("Ergebnis");
            call.status("📄 Ich sehe mir Dateien an…");
            return "Ergebnis fertig";
        });
        b.onPost(post("x").build());
        waitIdle(b);
        assertThat(seen.getFirst()).containsExactly("_⚙️ Ich führe Befehle aus…_" + Bot.CURSOR);
        assertThat(mm.finalMessages()).containsExactly("Ergebnis fertig");
    }

    // ---------------------------------------------------------------- Befehle

    @Test
    void hilfe() {
        b.onPost(post("!hilfe").build());
        waitIdle(b);
        assertThat(mm.finalMessages().getFirst()).contains("!neu");
        assertThat(oc.prompts).isEmpty();
    }

    @Test
    void modell() {
        b.onPost(post("!modell").build());
        waitIdle(b);
        assertThat(mm.finalMessages().getLast()).contains("`opencode/x` – Varianten: low, high");
        b.onPost(post("!modell opencode/x high").build());
        waitIdle(b);
        assertThat(store.get("matter-dm:c1")).isEqualTo(Map.of("model", "opencode/x", "variant", "high"));
        b.onPost(post("!modell gibtsnicht/y").build());
        waitIdle(b);
        assertThat(mm.finalMessages().getLast()).contains("Unbekanntes Modell");
        b.onPost(post("Hallo").build());
        waitIdle(b);
        assertThat(oc.prompts.getLast().options().model()).isEqualTo("opencode/x");
        assertThat(oc.prompts.getLast().options().variant()).isEqualTo("high");
        b.onPost(post("!modell standard").build());
        waitIdle(b);
        assertThat(store.get("matter-dm:c1").get("opencode_session_id")).isNotEmpty();
        assertThat(store.get("matter-dm:c1")).doesNotContainKey("model");
    }

    @Test
    void stoppAbortsAndDropsQueue() throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        oc.behaviour.add(call -> {
            running.countDown();
            await(gate);
            throw new PromptInterruptedException("abgebrochen");
        });
        b.onPost(post("lang").build());
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        b.onPost(post("wartet").build());
        b.onPost(post("!stopp").build());
        waitFor(() -> !oc.aborted().isEmpty(), "Abbruch");
        gate.countDown();
        waitIdle(b);
        assertThat(oc.aborted()).containsExactly("ses_1");
        assertThat(oc.prompts).hasSize(1); // „wartet“ verworfen
        List<String> msgs = mm.finalMessages();
        assertThat(msgs).anyMatch(m -> m.startsWith("⏹️ Abgebrochen. 1 wartende"));
        assertThat(msgs).contains("_(abgebrochen)_");
    }

    @Test
    void stoppWhileAttachmentLoads() throws InterruptedException {
        mm.downloadStarted = new CountDownLatch(1);
        mm.downloadGate = new CountDownLatch(1);
        b.onPost(post("mit Anhang").files("f1").build());
        assertThat(mm.downloadStarted.await(5, TimeUnit.SECONDS)).isTrue();
        b.onPost(post("!stopp").build());
        waitFor(() -> mm.finalMessages().stream().anyMatch(m -> m.startsWith("⏹️")), "Stopp-Antwort");
        mm.downloadGate.countDown();
        waitIdle(b);
        assertThat(oc.prompts).isEmpty(); // Prompt startet gar nicht erst
        assertThat(mm.finalMessages()).contains("⏹️ Abgebrochen.", "_(abgebrochen)_");
    }

    @Test
    void stoppDropsQueuedBatchesBehindCommand() throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        oc.behaviour.add(call -> {
            running.countDown();
            await(gate);
            throw new PromptInterruptedException("abgebrochen");
        });
        b.onPost(post("lang").build());
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        // wartet: Bündel „a“, !neu, Bündel „b“ – alle noch in der Spur
        b.onPost(post("a").build());
        b.onPost(post("!neu").build());
        b.onPost(post("b").build());
        b.onPost(post("!stopp").build());
        waitFor(() -> !oc.aborted().isEmpty(), "Abbruch");
        gate.countDown();
        waitIdle(b);
        assertThat(oc.prompts).hasSize(1);
        assertThat(mm.finalMessages()).anyMatch(m -> m.startsWith("⏹️ Abgebrochen. 3 wartende"));
    }

    @Test
    void usersAreCached() {
        for (int i = 0; i < 3; i++) {
            b.onPost(post("@captain Runde " + i).type("O").ch("c5").build());
        }
        waitIdle(b);
        assertThat(oc.prompts).hasSize(3);
        assertThat(mm.userCalls.get()).isEqualTo(1); // nur Alice, einmal
    }

    // ---------------------------------------------------------------- REST-Eingabekanal

    @Test
    void askUsesOneSessionPerConversation() {
        var first = b.ask("gitlab:mr-1", "starte ein Review", "n8n");
        var second = b.ask("gitlab:mr-1", "neuer Kommentar", null);
        var other = b.ask("gitlab:mr-2", "anderes MR", null);
        assertThat(first.session()).isEqualTo(second.session()).isNotEqualTo(other.session());
        assertThat(first.text()).isEqualTo("Antwort auf: n8n: starte ein Review");
        assertThat(oc.texts()).containsExactly("n8n: starte ein Review", "neuer Kommentar", "anderes MR");
        assertThat(store.get("rest:gitlab:mr-1").get("opencode_session_id")).isEqualTo(first.session());
        assertThat(oc.created.getFirst().title()).isEqualTo("REST rest:gitlab:mr-1");
        assertThat(oc.instructions.get(first.session())).containsKey("captain-kanal");
        assertThat(oc.prompts.getFirst().options().model()).isEqualTo("llm/modell-a");
        assertThat(mm.finalMessages()).isEmpty(); // nichts in Mattermost
    }

    @Test
    void askSurvivesRestartViaStore() {
        String sid = b.ask("x", "eins", null).session();
        Bot again = new Bot(b.cfg, mm, oc, new SessionStore(Path.of(b.cfg.dataDir(), "sessions.json")));
        try {
            assertThat(again.ask("x", "zwei", null).session()).isEqualTo(sid);
        } finally {
            again.stop();
        }
    }

    @Test
    void forgetStartsNewSession() {
        String sid = b.ask("x", "eins", null).session();
        String dir = store.get("rest:x").get("directory");
        assertThat(b.forget("x")).isTrue();
        assertThat(Path.of(dir)).doesNotExist();
        assertThat(b.forget("x")).isFalse();
        assertThat(b.ask("x", "zwei", null).session()).isNotEqualTo(sid);
    }

    /** Bot mit REST-Agent {@code reviewer} (Tools {@code gitlab_note}, {@code gitlab_diff}). */
    private Bot reviewerBot() {
        return new Bot(b.cfg.toBuilder().restAgents(Map.of("reviewer", List.of("gitlab_note", "gitlab_diff"))).build(),
                mm, oc, store);
    }

    @Test
    void askWithAgentCreatesAgentSessionAndRemembersIt() {
        Bot r = reviewerBot();
        try {
            String sid = r.ask("mr-1", "review", null, "reviewer").session();
            assertThat(oc.created.getFirst().agent()).isEqualTo("reviewer");
            assertThat(store.get("rest:mr-1").get("agent")).isEqualTo("reviewer");
            List<Rule> rules = oc.created.getFirst().permissions();
            assertThat(RuleEval.evaluate(rules, "gitlab_note", "*")).isEqualTo("allow");
            assertThat(RuleEval.evaluate(rules, "shell", "git clone x")).isEqualTo("deny");

            // Folge-POST ohne agent: gemerkter Agent, gleiche Session
            assertThat(r.ask("mr-1", "Kommentar", null, null).session()).isEqualTo(sid);
            assertThat(r.ask("mr-1", "noch einer", null, "reviewer").session()).isEqualTo(sid);
            assertThat(oc.created).hasSize(1);

            // Unterhaltung ohne Agent: Agent-Tools verboten, kein Agent
            r.ask("chat", "hallo", null, null);
            assertThat(oc.created.get(1).agent()).isNull();
            assertThat(RuleEval.evaluate(oc.created.get(1).permissions(), "gitlab_note", "*")).isEqualTo("deny");
        } finally {
            r.stop();
        }
    }

    @Test
    void askRejectsUnknownOrChangedAgent() {
        Bot r = reviewerBot();
        try {
            assertThatThrownBy(() -> r.ask("mr-1", "x", null, "build"))
                    .isInstanceOfSatisfying(AgentRejectedException.class, e -> assertThat(e.status()).isEqualTo(400));
            assertThat(store.get("rest:mr-1")).isNull();

            r.ask("plain", "x", null, null); // läuft ohne Agent
            assertThatThrownBy(() -> r.checkAgent("plain", "reviewer"))
                    .isInstanceOfSatisfying(AgentRejectedException.class, e -> assertThat(e.status()).isEqualTo(409));

            r.ask("mr-1", "x", null, "reviewer");
            store.setSetting("rest:mr-1", Map.of("agent", "alt")); // z. B. aus CAPTAIN_REST_AGENTS entfernt
            assertThatThrownBy(() -> r.ask("mr-1", "y", null, null))
                    .isInstanceOfSatisfying(AgentRejectedException.class, e -> assertThat(e.status()).isEqualTo(400));
            assertThat(oc.prompts).hasSize(2);
        } finally {
            r.stop();
        }
        // ohne CAPTAIN_REST_AGENTS ist jeder Agent abgelehnt
        assertThatThrownBy(() -> b.checkAgent("neu", "reviewer")).isInstanceOf(AgentRejectedException.class);
    }

    @Test
    void forgetDropsAgent() {
        Bot r = reviewerBot();
        try {
            r.ask("mr-1", "review", null, "reviewer");
            assertThat(r.forget("mr-1")).isTrue();
            assertThat(store.get("rest:mr-1")).isNull();
            r.checkAgent("mr-1", "reviewer"); // wieder frei
            r.ask("mr-1", "hallo", null, null);
            assertThat(oc.created.get(1).agent()).isNull();
        } finally {
            r.stop();
        }
    }

    @Test
    void askSerializesSameConversation() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        oc.behaviour.add(call -> {
            running.countDown();
            await(gate);
            return "eins fertig";
        });
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> b.ask("x", "eins", null).text());
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> b.ask("x", "zwei", null).text());
        Thread.sleep(100);
        assertThat(oc.prompts).hasSize(1); // zweite wartet
        gate.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("eins fertig");
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("Antwort auf: zwei");
    }

    // ---------------------------------------------------------------- Review #18

    static final String CODE = "def f():\n    return  1\n\n| a  |  b |\n|----|----|";

    @Test
    void indentedCodeUnchangedInDmAndChannel() {
        b.onPost(post(CODE).build());
        waitIdle(b);
        assertThat(oc.prompts.getFirst().text()).isEqualTo(CODE);
        b.onPost(post("@captain\n" + CODE).type("O").ch("c5").build());
        waitIdle(b);
        assertThat(oc.prompts.get(1).text()).endsWith("Alice Test: " + CODE);
    }

    @Test
    void mentionInCodeIsIgnored() {
        b.onPost(post("Schreib `@captain !help`").type("O").ch("c5").build());
        waitIdle(b);
        assertThat(oc.prompts).isEmpty();
        assertThat(mm.posts).isEmpty();
    }

    @Test
    void negativeThreadCacheAndInvalidation() {
        mm.roots.put("r9", List.of(raw("{\"id\":\"r9\",\"user_id\":\"u_bob\",\"message\":\"x\",\"create_at\":1}")));
        for (String text : List.of("eins", "zwei", "drei")) {
            b.onPost(post(text).type("O").ch("c5").root("r9").build());
        }
        waitIdle(b);
        assertThat(mm.threadCalls.get()).isEqualTo(1); // nur einmal per REST
        assertThat(oc.prompts).isEmpty();
        b.onPost(post("@captain jetzt du").type("O").ch("c5").root("r9").build());
        waitIdle(b);
        b.onPost(post("weiter ohne").type("O").ch("c5").root("r9").build());
        waitIdle(b);
        assertThat(oc.texts().stream().map(t -> t.substring(t.lastIndexOf('\n') + 1)).toList())
                .containsExactly("Alice Test: jetzt du", "Alice Test: weiter ohne");
        assertThat(mm.threadCalls.get()).isEqualTo(2); // Kontext der neuen Session; kein weiteres Nachschlagen
    }

    @Test
    void negativeCacheExpires() {
        b.noThreadTtl = 0.0;
        b.onPost(post("a").type("O").ch("c5").root("r9").build());
        waitIdle(b);
        b.onPost(post("b").type("O").ch("c5").root("r9").build());
        waitIdle(b);
        assertThat(mm.threadCalls.get()).isEqualTo(2);
    }

    @Test
    void lookupKeepsOrderWithinThread() {
        CountDownLatch gate = new CountDownLatch(1);
        mm.roots.put("r9", List.of(raw("{\"id\":\"x\",\"user_id\":\"bot1\",\"message\":\"hi\",\"create_at\":1}")));
        mm.threadHook = root -> {
            await(gate);
            return mm.thread(root);
        };
        b.onPost(post("erst").type("O").ch("c5").root("r9").build());
        b.onPost(post("@captain dann").type("O").ch("c5").root("r9").build()); // wartet hinter „erst“
        assertThat(oc.prompts()).isEmpty();
        gate.countDown();
        waitIdle(b);
        List<String> texts = oc.texts();
        assertThat(texts.getFirst()).contains("Alice Test: erst");
        assertThat(texts.getLast()).endsWith("Alice Test: dann");
    }

    @Test
    void threadsCapped() {
        b.threadsMax = 3;
        for (int i = 0; i < 5; i++) {
            b.markThread("r" + i);
        }
        assertThat(b.threads.keySet()).containsExactly("r2", "r3", "r4");
    }

    @Test
    void topLevelNeuExplains() {
        Post p = post("@captain !neu").type("O").ch("c5").build();
        b.onPost(p);
        waitIdle(b);
        assertThat(mm.posts).hasSize(1);
        FakeMattermost.Sent reply = mm.sent().getFirst();
        assertThat(reply.rootId).isEqualTo(p.id());
        assertThat(reply.message).contains("jede Erwähnung");
        assertThat(oc.prompts).isEmpty();
    }

    @Test
    void topLevelStoppStopsChannel() throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        oc.behaviour.add(call -> {
            running.countDown();
            await(gate);
            throw new PromptInterruptedException("abgebrochen");
        });
        Post first = post("@captain lang").type("O").ch("c5").build();
        b.onPost(first);
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        b.onPost(post("wartet").type("O").ch("c5").root(first.id()).build());
        b.onPost(post("anderswo").type("O").ch("c6").root("zz").build()); // anderer Kanal, ignoriert
        Post stop = post("@captain !stopp").type("O").ch("c5").build();
        b.onPost(stop);
        waitFor(() -> !oc.aborted().isEmpty(), "Abbruch");
        gate.countDown();
        waitIdle(b);
        assertThat(oc.aborted()).containsExactly("ses_1");
        assertThat(oc.prompts).hasSize(1);
        List<String> replies = mm.sent().stream().filter(s -> stop.id().equals(s.rootId)).map(s -> s.message).toList();
        assertThat(replies.getFirst()).isEqualTo(
                "⏹️ 1 laufende Antwort(en) im Kanal abgebrochen. 1 wartende Nachricht(en) verworfen.");
    }

    @Test
    void modellStandardInThreadNamesChannelModel() {
        store.setModel("matter-ch:c5", "opencode/x", "low");
        b.onPost(post("@captain !modell llm/modell-a").type("O").ch("c5").root("r1").build());
        waitIdle(b);
        b.onPost(post("!modell standard").type("O").ch("c5").root("r1").build());
        waitIdle(b);
        assertThat(mm.finalMessages().getLast())
                .isEqualTo("Modell zurückgesetzt – jetzt gilt `opencode/x`, Variante `low` (Kanal).");
        b.onPost(post("!modell").type("O").ch("c5").root("r1").build());
        waitIdle(b);
        assertThat(mm.finalMessages().getLast()).startsWith("**Modell hier:** `opencode/x`, Variante `low` (Kanal)");
    }

    // ---------------------------------------------------------------- Session-Verzeichnis

    @Test
    void newSessionGetsDirPermissionsAndInstructions() {
        b.onPost(post("Hallo").build());
        waitIdle(b);
        assertThat(oc.created).hasSize(1);
        FakeOpencode.Created call = oc.created.getFirst();
        String directory = b.cfg.sessionsDir() + "/ses_1";
        assertThat(call.sessionId()).isEqualTo("ses_1");
        assertThat(call.directory()).isEqualTo(directory);
        List<Rule> rules = call.permissions();
        assertThat(rules).contains(Rule.allow("read", directory + "/*"), Rule.deny("external_directory", "*"));
        // Shell & Co. nie freigegeben, sondern zum Schluss ausdrücklich verboten
        assertThat(rules).noneMatch(r -> r.effect().equals("allow")
                && List.of("shell", "webfetch", "execute", "*").contains(r.action()));
        assertThat(rules.getLast().effect()).isEqualTo("deny");
        Map<String, String> entries = oc.instructions.get("ses_1");
        assertThat(entries.keySet()).containsExactly("captain-persona", "captain-umgebung", "captain-kanal"); // Reihenfolge
        assertThat(entries.get("captain-persona")).isEqualTo("Du bist Testkapitän.");
        assertThat(entries.get("captain-umgebung")).contains(directory);
    }

    /** CAPTAIN_SHARED_DIR: /shared lesbar (Regeln hinten), Hinweis im Systemprompt. */
    @Test
    void sharedDirRulesAndHint() {
        b.onPost(post("Hallo").build());
        waitIdle(b);
        List<Rule> off = oc.created.getFirst().permissions();
        assertThat(off).noneMatch(r -> r.resource().contains("/shared"));
        assertThat(oc.instructions.get("ses_1").get("captain-umgebung")).doesNotContain("/shared");
        b.cfg = b.cfg.toBuilder().sharedDir("/srv/captain-shared").build();
        b.onPost(post("Hallo").ch("c2").build());
        waitIdle(b);
        List<Rule> rules = oc.created.get(1).permissions();
        List<Rule> shared = rules.subList(off.size(), rules.size());
        assertThat(shared.getFirst()).isEqualTo(Rule.allow("external_directory", "/shared/*"));
        assertThat(shared).allMatch(r -> List.of("external_directory", "read", "edit").contains(r.action()));
        assertThat(rules.getLast()).isEqualTo(Rule.deny("edit", "/shared/*"));
        assertThat(oc.instructions.get("ses_2").get("captain-umgebung"))
                .contains("`/shared`").doesNotContain("/srv/captain-shared"); // Host-Pfad bleibt intern
    }

    @Test
    void instructionsFailureDoesNotBlock() {
        oc.instructionsError = new OpencodeException("HTTP 404");
        b.onPost(post("Hallo").build());
        waitIdle(b);
        assertThat(store.get("matter-dm:c1").get("opencode_session_id")).isEqualTo("ses_1");
        String first = oc.prompts.getFirst().text();
        // Ersatz: markierter Vorspann mit Persona + Arbeitsumgebung im ersten Prompt
        assertThat(first).startsWith("[Systemhinweise für diese Unterhaltung]\nDu bist Testkapitän.")
                .contains(b.sessionDir("ses_1"))
                .endsWith("[Ende der Systemhinweise]\n\nHallo");
        // nächster Prompt: einmaliger Neuversuch über die API (klappt jetzt)
        oc.instructionsError = null;
        b.onPost(post("Weiter").build());
        waitIdle(b);
        assertThat(oc.prompts.get(1).text()).isEqualTo("Weiter");
        assertThat(oc.instructions.get("ses_1").keySet()).containsExactly("captain-persona", "captain-umgebung", "captain-kanal");
    }

    @Test
    void instructionsRetryOnlyOnce() {
        oc.instructionsError = new OpencodeException("HTTP 500");
        for (String text : List.of("eins", "zwei", "drei")) {
            b.onPost(post(text).build());
            waitIdle(b);
        }
        assertThat(oc.instructionCalls).hasSize(2); // beim Anlegen + ein Neuversuch
        List<String> texts = oc.texts();
        assertThat(texts.get(0)).startsWith("[Systemhinweise");
        assertThat(texts.get(1)).startsWith("[Systemhinweise");
        assertThat(texts.get(2)).isEqualTo("drei");
    }

    @Test
    void neuRemovesSessionDirAndNextGetsNewOne() {
        b.onPost(post("Schau mal").files("f1").build());
        waitIdle(b);
        String first = store.get("matter-dm:c1").get("directory");
        assertThat(Path.of(first, "f1.txt")).isRegularFile();
        b.onPost(post("!neu").build());
        waitIdle(b);
        assertThat(Path.of(first)).doesNotExist();
        assertThat(store.get("matter-dm:c1") == null ? Map.of() : store.get("matter-dm:c1")).doesNotContainKey("directory");
        b.onPost(post("weiter").build());
        waitIdle(b);
        String second = store.get("matter-dm:c1").get("directory");
        assertThat(second).isNotEqualTo(first).endsWith("/ses_2");
        assertThat(Path.of(second)).isDirectory();
    }

    @Test
    void existingSessionKeepsDirAndRecreatesMissing() throws IOException {
        b.onPost(post("eins").build());
        waitIdle(b);
        String directory = store.get("matter-dm:c1").get("directory");
        Files.delete(Path.of(directory)); // z. B. Volume geleert
        b.onPost(post("zwei").build());
        waitIdle(b);
        assertThat(oc.prompts().stream().map(FakeOpencode.Call::sid).toList()).containsExactly("ses_1", "ses_1");
        assertThat(Path.of(directory)).isDirectory();
    }

    @Test
    void legacyWorkspaceEntryGetsNewSessionWithoutDeleting() throws IOException {
        Path legacy = tmp.resolve("workspaces").resolve("dm_c1");
        Files.createDirectories(legacy);
        store.set("matter-dm:c1", "ses_alt", slash(legacy));
        b.onPost(post("Hallo").build());
        waitIdle(b);
        assertThat(oc.prompts.getFirst().sid()).isEqualTo("ses_1");
        assertThat(legacy).exists(); // außerhalb von SESSIONS_DIR wird nichts gelöscht
    }

    @Test
    void removeDirOnlyBelowSessionsDir() throws IOException {
        Path outside = tmp.resolve("fremd");
        Files.createDirectories(outside);
        b.removeDir(outside.toString());
        b.removeDir(b.cfg.sessionsDir());
        b.removeDir(b.cfg.sessionsDir() + "/../fremd");
        assertThat(outside).exists();
    }

    // ---------------------------------------------------------------- Aufräumen (#17)

    static final long DAY = 86_400_000L;

    /** Uhr des Bots auf {@code now} umstellbar. */
    java.util.concurrent.atomic.AtomicLong fakeClock() {
        var now = new java.util.concurrent.atomic.AtomicLong(1_000 * DAY);
        b.clock = now::get;
        return now;
    }

    @Test
    void forgetDeletesOpencodeSession() {
        String sid = b.ask("x", "eins", null).session();
        assertThat(b.forget("x")).isTrue();
        assertThat(oc.deleted()).containsExactly(sid);
    }

    @Test
    void neuDeletesOpencodeSession() {
        b.onPost(post("Hallo").build());
        waitIdle(b);
        b.onPost(post("!neu").build());
        waitIdle(b);
        assertThat(oc.deleted()).containsExactly("ses_1");
        assertThat(store.get("matter-dm:c1")).isNull();
    }

    @Test
    void deleteErrorDoesNotBreakForget() {
        b.ask("x", "eins", null);
        oc.deleteError = new OpencodeException("HTTP 500");
        assertThat(b.forget("x")).isTrue();
        assertThat(store.get("rest:x")).isNull();
    }

    @Test
    void sweepRemovesIdleSessionsOfAllChannels() {
        var now = fakeClock();
        b.onPost(post("Hallo").build()); // matter-dm:c1
        Post p = post("@captain los").type("O").ch("c5").build(); // matter-th:<p>
        b.onPost(p);
        waitIdle(b);
        b.ask("mr-1", "Review", null); // rest:mr-1
        store.setModel("matter-dm:c1", "opencode/x", null);
        Map<String, String> dm = store.get("matter-dm:c1");
        now.addAndGet(13 * DAY);
        b.ask("mr-2", "frisch", null); // erst 1 Tag alt, wenn die anderen fällig sind
        assertThat(b.sweep()).isZero();
        now.addAndGet(DAY + 1);
        assertThat(b.sweep()).isEqualTo(3);
        assertThat(oc.deleted()).containsExactlyInAnyOrder("ses_1", "ses_2", "ses_3");
        assertThat(Path.of(dm.get("directory"))).doesNotExist();
        assertThat(store.get("matter-dm:c1")).isEqualTo(Map.of("model", "opencode/x",
                "expired_at", Long.toString(now.get())));
        assertThat(store.get("matter-th:" + p.id())).isNull();
        assertThat(store.get("rest:mr-1")).isNull();
        assertThat(store.get("rest:mr-2").get("opencode_session_id")).isEqualTo("ses_4");
        // neue Nachricht im alten Thread → neue Session (Thread-Kontext wie gehabt)
        b.onPost(post("noch da?").type("O").ch("c5").root(p.id()).build());
        waitIdle(b);
        assertThat(oc.prompts.getLast().sid()).isEqualTo("ses_5");
    }

    @Test
    void sweepTouchesLegacyEntries() throws IOException {
        var now = fakeClock();
        Path dir = Path.of(b.cfg.sessionsDir(), "ses_alt");
        Files.createDirectories(dir);
        Path file = Files.createDirectories(Path.of(b.cfg.dataDir())).resolve("sessions.json");
        Files.writeString(file, Json.tree(Map.of("matter-dm:c1",
                Map.of("opencode_session_id", "ses_alt", "directory", slash(dir)))).toString());
        SessionStore old = new SessionStore(file); // Alteintrag ohne last_used
        Bot bot = new Bot(b.cfg, mm, oc, old);
        try {
            bot.clock = now::get;
            assertThat(bot.sweep()).isZero(); // gilt ab jetzt als genutzt
            assertThat(SessionStore.lastUsed(old.get("matter-dm:c1"))).isEqualTo(now.get());
            now.addAndGet(15 * DAY);
            assertThat(bot.sweep()).isEqualTo(1);
            assertThat(oc.deleted()).containsExactly("ses_alt");
            assertThat(dir).doesNotExist();
        } finally {
            bot.stop();
        }
    }

    @Test
    void useResetsIdleTime() {
        var now = fakeClock();
        b.ask("x", "eins", null);
        now.addAndGet(10 * DAY);
        b.ask("x", "zwei", null);
        now.addAndGet(10 * DAY);
        assertThat(b.sweep()).isZero();
        now.addAndGet(5 * DAY);
        assertThat(b.sweep()).isEqualTo(1);
    }

    @Test
    void sweepSkipsRunningAnswer() throws Exception {
        var now = fakeClock();
        b.ask("x", "eins", null);
        now.addAndGet(20 * DAY);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        oc.behaviour.add(call -> {
            running.countDown();
            await(gate);
            return "fertig";
        });
        Map<String, String> entry = store.get("rest:x");
        // Prompt läuft mit altem last_used (Uhr steht still während der Antwort)
        b.clock = () -> Long.parseLong(entry.get("last_used"));
        var answer = java.util.concurrent.CompletableFuture.supplyAsync(() -> b.ask("x", "zwei", null).text());
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        b.clock = now::get;
        assertThat(b.sweep()).isZero();
        gate.countDown();
        assertThat(answer.get(5, TimeUnit.SECONDS)).isEqualTo("fertig");
        assertThat(oc.deleted()).isEmpty();
        assertThat(b.sweep()).isEqualTo(1); // danach wieder frei
    }

    @Test
    void sweepKeepsEntryWhenDeleteFails() {
        var now = fakeClock();
        b.ask("x", "eins", null);
        now.addAndGet(15 * DAY);
        oc.deleteError = new OpencodeException("opencode weg");
        assertThat(b.sweep()).isZero();
        assertThat(store.get("rest:x").get("opencode_session_id")).isEqualTo("ses_1");
        oc.deleteError = null;
        assertThat(b.sweep()).isEqualTo(1);
    }

    @Test
    void sweepOffWithZero() {
        var now = fakeClock();
        b.cfg = b.cfg.toBuilder().sessionMaxIdle(java.time.Duration.ZERO).build();
        b.ask("x", "eins", null);
        now.addAndGet(365 * DAY);
        assertThat(b.sweep()).isZero();
        assertThat(store.get("rest:x")).isNotNull();
    }

    @Test
    void dmAfterExpiryGetsContextSinceLastNeu() {
        var now = fakeClock();
        b.onPost(post("Hallo").build());
        waitIdle(b);
        now.addAndGet(15 * DAY);
        assertThat(b.sweep()).isEqualTo(1);
        Post p = post("Weißt du noch?").build();
        mm.channel = List.of(
                raw("{\"id\":\"a0\",\"user_id\":\"u_alice\",\"message\":\"Geheimnis\",\"create_at\":1}"),
                raw("{\"id\":\"a1\",\"user_id\":\"bot1\",\"message\":\"" + Bot.NEW_NOTE + "\",\"create_at\":2}"),
                raw("{\"id\":\"a2\",\"user_id\":\"u_alice\",\"message\":\"Mein Hund heißt Rex\",\"create_at\":3}"),
                raw("{\"id\":\"a3\",\"user_id\":\"bot1\",\"message\":\"Schöner Name.\",\"create_at\":4}"),
                Json.object().put("id", p.id()).put("user_id", "u_alice").put("message", p.message()).put("create_at", 5));
        b.onPost(p);
        waitIdle(b);
        assertThat(oc.prompts.getLast().sid()).isEqualTo("ses_2");
        assertThat(oc.prompts.getLast().text()).isEqualTo(
                "[Bisheriger Verlauf dieser Direktnachrichten – die frühere Sitzung wurde nach längerer Pause beendet]\n"
                        + "Alice Test: Mein Hund heißt Rex\nCaptain (du): Schöner Name.\n"
                        + "[Ende des Verlaufs]\n\nWeißt du noch?");
        assertThat(store.get("matter-dm:c1")).doesNotContainKey("expired_at");
        // nach !neu gibt es keinen DM-Kontext
        b.onPost(post("!neu").build());
        waitIdle(b);
        b.onPost(post("Neu hier").build());
        waitIdle(b);
        assertThat(oc.prompts.getLast().text()).isEqualTo("Neu hier");
    }

    static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
