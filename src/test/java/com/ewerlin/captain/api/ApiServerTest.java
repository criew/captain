package com.ewerlin.captain.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ewerlin.captain.opencode.OpencodeException;
import com.ewerlin.captain.opencode.PromptInterruptedException;
import com.ewerlin.captain.util.Json;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;

/** {@link ApiServer} mit Fake-Unterhaltungen über echtes HTTP. */
class ApiServerTest {

    static final String TOKEN = "t".repeat(32);

    /** Merkt Aufrufe; Antwort bzw. Fehler je nach Text. */
    static final class FakeConversations implements Conversations {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Answer ask(String id, String text, String sender, String agent) {
            calls.add(id + "|" + text + "|" + sender + (agent == null ? "" : "|" + agent));
            checkAgent(id, agent);
            return switch (text) {
                case "kaputt" -> throw new OpencodeException("HTTP 500");
                case "stopp" -> throw new PromptInterruptedException("abgebrochen");
                default -> new Answer("ses_" + id.length(), "Antwort: " + text);
            };
        }

        /** Erlaubt ist nur {@code reviewer}; Unterhaltung {@code belegt} läuft mit {@code anderer}. */
        @Override
        public void checkAgent(String id, String agent) {
            if (agent != null && !agent.equals("reviewer")) {
                throw new AgentRejectedException(400, "Agent \"" + agent + "\" ist nicht erlaubt");
            }
            if (agent != null && id.equals("belegt")) {
                throw new AgentRejectedException(409, "Unterhaltung läuft mit Agent \"anderer\"");
            }
        }

        @Override
        public boolean forget(String id) {
            calls.add("forget|" + id);
            return id.equals("bekannt");
        }
    }

    FakeConversations conversations;
    ApiServer api;
    HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws IOException {
        conversations = new FakeConversations();
        api = new ApiServer(conversations, TOKEN, new InetSocketAddress("127.0.0.1", 0));
        api.callbackRetry = new double[] {0.01};
        api.start();
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        return client.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String id, String body) throws Exception {
        return call("POST", "/api/conversations/" + id + "/messages", body, TOKEN);
    }

    @Test
    void answersSynchronously() throws Exception {
        HttpResponse<String> r = post("gitlab:42:mr-7", "{\"text\": \" starte ein Review \", \"sender\": \"GitLab\"}");
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = Json.parse(r.body());
        assertThat(body.path("conversation").asString()).isEqualTo("gitlab:42:mr-7");
        assertThat(body.path("session").asString()).isEqualTo("ses_14");
        assertThat(body.path("answer").asString()).isEqualTo("Antwort: starte ein Review");
        assertThat(conversations.calls).containsExactly("gitlab:42:mr-7|starte ein Review|GitLab");
    }

    @Test
    void healthWithoutToken() throws Exception {
        assertThat(call("GET", "/api/health", null, null).statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsMissingOrWrongToken() throws Exception {
        assertThat(call("POST", "/api/conversations/x/messages", "{\"text\":\"a\"}", null).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/api/conversations/x/messages", "{\"text\":\"a\"}", "falsch").statusCode()).isEqualTo(401);
        assertThat(conversations.calls).isEmpty();
    }

    @Test
    void validatesInput() throws Exception {
        assertThat(post("x", "{kaputt").statusCode()).isEqualTo(400);
        assertThat(post("x", "{\"text\": \"  \"}").statusCode()).isEqualTo(400);
        assertThat(post("x", "{\"text\": 5}").statusCode()).isEqualTo(400);
        assertThat(post("..", "{\"text\": \"a\"}").statusCode()).isEqualTo(400);
        assertThat(post("a%2Fb", "{\"text\": \"a\"}").statusCode()).isEqualTo(400);
        assertThat(post("x".repeat(201), "{\"text\": \"a\"}").statusCode()).isEqualTo(400);
        assertThat(post("x", "{\"text\": \"a\", \"callback_url\": \"file:///etc/passwd\"}").statusCode()).isEqualTo(400);
        assertThat(post("x", "{\"text\": \"" + "a".repeat(ApiServer.MAX_BODY) + "\"}").statusCode()).isEqualTo(413);
        assertThat(call("GET", "/api/conversations/x/messages", null, TOKEN).statusCode()).isEqualTo(405);
        assertThat(call("GET", "/api/irgendwas", null, TOKEN).statusCode()).isEqualTo(404);
        assertThat(conversations.calls).isEmpty();
    }

    @Test
    void errorsMapToStatus() throws Exception {
        HttpResponse<String> r = post("x", "{\"text\": \"kaputt\"}");
        assertThat(r.statusCode()).isEqualTo(502);
        assertThat(Json.parse(r.body()).path("error").asString()).contains("HTTP 500");
        assertThat(Json.parse(r.body()).has("_status")).isFalse();
        assertThat(post("x", "{\"text\": \"stopp\"}").statusCode()).isEqualTo(409);
    }

    @Test
    void agentIsCheckedBeforeAnswering() throws Exception {
        HttpResponse<String> ok = post("mr-1", "{\"text\": \"review\", \"agent\": \" reviewer \"}");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(conversations.calls).containsExactly("mr-1|review|null|reviewer");

        conversations.calls.clear();
        assertThat(post("mr-1", "{\"text\": \"a\", \"agent\": \"build\"}").statusCode()).isEqualTo(400);
        assertThat(post("mr-1", "{\"text\": \"a\", \"agent\": \"\"}").statusCode()).isEqualTo(400);
        assertThat(post("mr-1", "{\"text\": \"a\", \"agent\": 5}").statusCode()).isEqualTo(400);
        HttpResponse<String> conflict = post("belegt", "{\"text\": \"a\", \"agent\": \"reviewer\"}");
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(Json.parse(conflict.body()).path("error").asString()).contains("anderer");
        // auch mit callback_url sofort, nicht erst im Callback
        assertThat(post("mr-1", "{\"text\": \"a\", \"agent\": \"build\", \"callback_url\": \"http://127.0.0.1:1/x\"}")
                .statusCode()).isEqualTo(400);
        assertThat(conversations.calls).isEmpty();
    }

    @Test
    void deleteForgetsConversation() throws Exception {
        assertThat(call("DELETE", "/api/conversations/bekannt", null, TOKEN).statusCode()).isEqualTo(200);
        assertThat(call("DELETE", "/api/conversations/neu", null, TOKEN).statusCode()).isEqualTo(404);
        assertThat(conversations.calls).containsExactly("forget|bekannt", "forget|neu");
    }

    @Test
    void callbackGetsAnswerLater() throws Exception {
        CompletableFuture<String> received = new CompletableFuture<>();
        HttpServer hook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hook.createContext("/hook", ex -> {
            received.complete(ex.getRequestHeaders().getFirst("X-Captain-Conversation") + " "
                    + new String(ex.getRequestBody().readAllBytes()));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        hook.start();
        try {
            String url = "http://127.0.0.1:" + hook.getAddress().getPort() + "/hook";
            HttpResponse<String> r = post("mr-1", "{\"text\": \"review\", \"callback_url\": \"" + url + "\"}");
            assertThat(r.statusCode()).isEqualTo(202);
            String got = received.get(5, TimeUnit.SECONDS);
            assertThat(got).startsWith("mr-1 ");
            JsonNode body = Json.parse(got.substring("mr-1 ".length()));
            assertThat(body.path("answer").asString()).isEqualTo("Antwort: review");
        } finally {
            hook.stop(0);
        }
    }

    @Test
    void callbackRetriesThenGivesUp() {
        // nichts lauscht dort: zwei Versuche, dann aufgeben – ohne Ausnahme
        api.deliver(URI.create("http://127.0.0.1:1/hook"), Json.object().put("conversation", "x").put("answer", "a"));
    }

    @Test
    void callbackUriValidation() {
        assertThat(ApiServer.callbackUri("https://n8n.intern/webhook/x")).isNotNull();
        assertThat(ApiServer.callbackUri("ftp://x/y")).isNull();
        assertThat(ApiServer.callbackUri("kein url")).isNull();
        assertThat(ApiServer.callbackUri("http:///ohne-host")).isNull();
    }
}
