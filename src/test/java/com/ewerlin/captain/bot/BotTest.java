package com.ewerlin.captain.bot;

import static com.ewerlin.captain.bot.BotTestSupport.post;
import static com.ewerlin.captain.bot.BotTestSupport.slash;
import static com.ewerlin.captain.bot.BotTestSupport.waitFor;
import static com.ewerlin.captain.bot.BotTestSupport.waitIdle;
import static org.assertj.core.api.Assertions.assertThat;

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

import com.ewerlin.captain.config.CaptainConfig;
import com.ewerlin.captain.mattermost.Post;
import com.ewerlin.captain.opencode.OpencodeException;
import com.ewerlin.captain.opencode.PromptInterruptedException;
import com.ewerlin.captain.opencode.Rule;
import com.ewerlin.captain.opencode.SessionGoneException;
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
                .opencodeModel("ollama/gemma4:e4b")
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
        assertThat(oc.prompts.getFirst().options().model()).isEqualTo("ollama/gemma4:e4b");
        // Endstand kommt aus dem Rückgabewert, nicht aus dem letzten Delta
        assertThat(mm.finalMessages()).containsExactly("Antwort auf: Hallo");
        FakeMattermost.Sent reply = mm.sent().getFirst();
        assertThat(reply.rootId).isNull();
        assertThat(reply.channelId).isEqualTo("c1");
        Map<String, String> entry = store.get("dm:c1");
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
        assertThat(store.get("th:" + p.id())).isNotNull();
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
        assertThat(store.get("th:" + p.id()).get("opencode_session_id")).isEqualTo("ses_1");
        b.onPost(post("!neu").type("O").ch("c5").root(p.id()).build());
        waitIdle(b);
        assertThat(store.get("th:" + p.id())).isNull();
        b.onPost(post("weiter").type("O").ch("c5").root(p.id()).build()); // ohne Erwähnung
        waitIdle(b);
        assertThat(oc.prompts.getLast().sid()).isEqualTo("ses_2");
    }

    @Test
    void channelModelAppliesToNewThreads() {
        b.onPost(post("@captain !modell opencode/x high").type("O").ch("c5").build());
        waitIdle(b);
        assertThat(store.get("ch:c5")).isEqualTo(Map.of("model", "opencode/x", "variant", "high"));
        Post p = post("@captain Hallo").type("O").ch("c5").build();
        b.onPost(p);
        waitIdle(b);
        assertThat(oc.prompts.getLast().options().model()).isEqualTo("opencode/x");
        assertThat(oc.prompts.getLast().options().variant()).isEqualTo("high");
        b.onPost(post("!modell ollama/gemma4:e4b").type("O").ch("c5").root(p.id()).build());
        waitIdle(b);
        b.onPost(post("weiter").type("O").ch("c5").root(p.id()).build());
        waitIdle(b);
        assertThat(oc.prompts.getLast().options().model()).isEqualTo("ollama/gemma4:e4b");
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
        assertThat(store.get("th:r1")).isNotNull();
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
    void allowlist() {
        b.cfg = b.cfg.toBuilder().allowedUsers(Set.of("bob")).build();
        b.onPost(post("alice darf nicht").build());
        b.onPost(post("bob darf").user("u_bob").build());
        waitIdle(b);
        assertThat(oc.texts()).containsExactly("bob darf");
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
        store.set("dm:c1", "ses_alt", old);
        oc.behaviour.add(call -> {
            call.delta("halb");
            throw new SessionGoneException("weg");
        });
        b.onPost(post("Hallo").build());
        waitIdle(b);
        assertThat(oc.prompts().stream().map(FakeOpencode.Call::sid).toList()).containsExactly("ses_alt", "ses_1");
        assertThat(store.get("dm:c1").get("opencode_session_id")).isEqualTo("ses_1");
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
        assertThat(store.get("dm:c1")).isEqualTo(Map.of("model", "opencode/x", "variant", "high"));
        b.onPost(post("!modell gibtsnicht/y").build());
        waitIdle(b);
        assertThat(mm.finalMessages().getLast()).contains("Unbekanntes Modell");
        b.onPost(post("Hallo").build());
        waitIdle(b);
        assertThat(oc.prompts.getLast().options().model()).isEqualTo("opencode/x");
        assertThat(oc.prompts.getLast().options().variant()).isEqualTo("high");
        b.onPost(post("!modell standard").build());
        waitIdle(b);
        assertThat(store.get("dm:c1").get("opencode_session_id")).isNotEmpty();
        assertThat(store.get("dm:c1")).doesNotContainKey("model");
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
        store.setModel("ch:c5", "opencode/x", "low");
        b.onPost(post("@captain !modell ollama/gemma4:e4b").type("O").ch("c5").root("r1").build());
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
        assertThat(entries.keySet()).containsExactly("captain-persona", "captain-umgebung"); // Reihenfolge
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
        assertThat(store.get("dm:c1").get("opencode_session_id")).isEqualTo("ses_1");
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
        assertThat(oc.instructions.get("ses_1").keySet()).containsExactly("captain-persona", "captain-umgebung");
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
        String first = store.get("dm:c1").get("directory");
        assertThat(Path.of(first, "f1.txt")).isRegularFile();
        b.onPost(post("!neu").build());
        waitIdle(b);
        assertThat(Path.of(first)).doesNotExist();
        assertThat(store.get("dm:c1") == null ? Map.of() : store.get("dm:c1")).doesNotContainKey("directory");
        b.onPost(post("weiter").build());
        waitIdle(b);
        String second = store.get("dm:c1").get("directory");
        assertThat(second).isNotEqualTo(first).endsWith("/ses_2");
        assertThat(Path.of(second)).isDirectory();
    }

    @Test
    void existingSessionKeepsDirAndRecreatesMissing() throws IOException {
        b.onPost(post("eins").build());
        waitIdle(b);
        String directory = store.get("dm:c1").get("directory");
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
        store.set("dm:c1", "ses_alt", slash(legacy));
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

    static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
