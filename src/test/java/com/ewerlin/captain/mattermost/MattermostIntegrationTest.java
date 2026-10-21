package com.ewerlin.captain.mattermost;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.ewerlin.captain.it.Integration;
import com.ewerlin.captain.util.Http;
import com.ewerlin.captain.util.Json;

import tools.jackson.databind.JsonNode;

/**
 * Integrationstests gegen das laufende Mattermost-Test-Setup ({@code infra/mattermost}).
 *
 * <p>Zugangsdaten aus der von {@code infra/mattermost/seed.py} erzeugten env-Datei; Pfad per
 * {@code CAPTAIN_MM_ENV} überschreibbar. Fehlt die Datei, wird übersprungen.
 */
@Tag("integration")
class MattermostIntegrationTest {

    static Map<String, String> env;
    MattermostClient bot;

    @BeforeAll
    static void loadEnv() {
        env = Integration.envOrSkip("CAPTAIN_MM_ENV", "C:/source/captain-shared/mattermost.env", "Mattermost-Testdaten");
    }

    @BeforeEach
    void setUp() {
        bot = new MattermostClient(env.get("MM_URL"), env.get("MM_BOT_TOKEN"));
    }

    @AfterEach
    void tearDown() {
        bot.close();
    }

    static String alicePost(String channelId, String message) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(env.get("MM_URL").replaceAll("/+$", "") + "/api/v4/posts"))
                .header("Authorization", "Bearer " + env.get("MM_ALICE_TOKEN"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("channel_id", channelId, "message", message))))
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isLessThan(300);
        return Json.str(Json.parse(resp.body()), "id");
    }

    static String marker() {
        return Integration.hex(8);
    }

    Thread listen(java.util.function.Consumer<Post> onPost) {
        Thread t = new Thread(() -> bot.listen(onPost, null));
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    void listenReceivesDmGroupAndPrivate() throws Exception {
        assertThat(Json.str(bot.me(), "id")).isEqualTo(env.get("MM_BOT_USER_ID"));
        String marker = marker();
        Map<String, Post> got = new ConcurrentHashMap<>();
        CountDownLatch allThree = new CountDownLatch(1);
        Thread t = listen(post -> {
            if (post.message().contains(marker)) {
                got.put(post.channelId(), post);
                if (got.size() >= 3) {
                    allThree.countDown();
                }
            }
        });
        Map<String, String> channels = Map.of(
                env.get("MM_DM_ALICE_BOT_ID"), "D",
                env.get("MM_GROUP_DM_ID"), "G",
                env.get("MM_CHANNEL_FAMILIE_ID"), "P");
        try {
            assertThat(bot.connected.await(10)).as("kein hello vom WebSocket").isTrue();
            bot.createPost(env.get("MM_DM_ALICE_BOT_ID"), "eigener Post " + marker);
            for (String channelId : channels.keySet()) {
                alicePost(channelId, "Hallo Captain " + marker);
            }
            assertThat(allThree.await(10, TimeUnit.SECONDS)).as("nur " + got.keySet() + " empfangen").isTrue();
        } finally {
            bot.close();
            t.join(10_000);
        }
        assertThat(t.isAlive()).isFalse();
        channels.forEach((channelId, type) -> {
            Post post = got.get(channelId);
            assertThat(post.channelType()).isEqualTo(type);
            assertThat(post.userId()).isEqualTo(env.get("MM_ALICE_USER_ID"));
            assertThat(post.senderName()).isEqualTo(env.get("MM_ALICE_USERNAME"));
            assertThat(post.message()).isEqualTo("Hallo Captain " + marker);
        });
        assertThat(got.values()).allMatch(p -> !p.userId().equals(env.get("MM_BOT_USER_ID")));
    }

    List<JsonNode> botPostsSince(String channelId, long sinceMs) {
        Http.Response resp = bot.request("GET", "/channels/" + channelId + "/posts",
                Map.of("since", String.valueOf(sinceMs)), null, null);
        List<JsonNode> out = new ArrayList<>(Json.values(Json.parse(resp.text()).get("posts")).stream()
                .filter(p -> Json.str(p, "user_id").equals(Json.str(bot.me(), "id"))
                        && Json.lng(p, "create_at") >= sinceMs && !Json.truthy(p, "delete_at"))
                .toList());
        out.sort(Comparator.comparingLong(p -> Json.lng(p, "create_at")));
        return out;
    }

    @Test
    void postStreamerSinglePost() throws InterruptedException {
        String channel = env.get("MM_DM_ALICE_BOT_ID");
        long start = System.currentTimeMillis() - 1;
        PostStreamer streamer = new PostStreamer(bot, channel, null, 0.2);
        streamer.status("🔍 Recherchiere…");
        StringBuilder text = new StringBuilder();
        for (String word : "Das ist eine gestreamte Antwort in genau einem Post".split(" ")) {
            text.append(word).append(' ');
            streamer.update(text.toString());
            Thread.sleep(50);
        }
        String fin = text + "– fertig.";
        streamer.finish(fin);
        List<JsonNode> posts = botPostsSince(channel, start);
        assertThat(posts).hasSize(1);
        assertThat(Json.str(posts.getFirst(), "id")).isEqualTo(streamer.postIds().getFirst());
        assertThat(Json.str(posts.getFirst(), "message")).isEqualTo(fin);
    }

    @Test
    void postStreamerSplitsLongText() {
        String channel = env.get("MM_DM_ALICE_BOT_ID");
        long start = System.currentTimeMillis() - 1;
        PostStreamer streamer = new PostStreamer(bot, channel, null, 0);
        streamer.update("Anfang");
        String fin = "Zeile mit etwas Text\n".repeat(1000).stripTrailing(); // ~21000 Zeichen
        streamer.finish(fin);
        List<JsonNode> posts = botPostsSince(channel, start);
        assertThat(posts).hasSize(2).hasSameSizeAs(streamer.postIds());
        assertThat(String.join("\n", posts.stream().map(p -> Json.str(p, "message")).toList())).isEqualTo(fin);
    }

    @Test
    void listenResumesAfterDisconnect() throws Exception {
        String marker = marker();
        CountDownLatch got = new CountDownLatch(1);
        bot.backoffInitial = 0.1;
        Thread t = listen(post -> {
            if (post.message().contains(marker)) {
                got.countDown();
            }
        });
        try {
            assertThat(bot.connected.await(10)).isTrue();
            String firstId = bot.connectionId();
            bot.dropConnection(); // Abbruch simulieren
            Thread.sleep(50);
            alicePost(env.get("MM_DM_ALICE_BOT_ID"), "nach dem Abbruch " + marker);
            assertThat(got.await(15, TimeUnit.SECONDS)).as("Post nach Reconnect nicht empfangen").isTrue();
            assertThat(bot.connected.await(5)).isTrue();
            assertThat(bot.connectionId()).isEqualTo(firstId); // Server hat die Verbindung fortgesetzt
        } finally {
            bot.close();
            t.join(10_000);
        }
    }
}
