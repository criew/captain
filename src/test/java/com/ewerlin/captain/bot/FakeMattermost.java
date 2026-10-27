package com.ewerlin.captain.bot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.ewerlin.captain.mattermost.AttachmentTooLargeException;
import com.ewerlin.captain.mattermost.MattermostApi;
import com.ewerlin.captain.mattermost.Post;
import com.ewerlin.captain.util.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Fake-Mattermost für die Bot-Tests (ohne Netz). */
class FakeMattermost implements MattermostApi {

    static final Map<String, JsonNode> USERS = Map.of(
            "u_alice", Json.parse("{\"id\":\"u_alice\",\"username\":\"alice\",\"first_name\":\"Alice\",\"last_name\":\"Test\"}"),
            "u_bob", Json.parse("{\"id\":\"u_bob\",\"username\":\"bob\",\"nickname\":\"Bobby\"}"),
            "u_robot", Json.parse("{\"id\":\"u_robot\",\"username\":\"robot\",\"is_bot\":true}"),
            "bot1", Json.parse("{\"id\":\"bot1\",\"username\":\"captain\",\"is_bot\":true}"));

    /** Gesendeter Post: Kanal, Text, Thread. */
    static final class Sent {
        final String channelId;
        volatile String message;
        final String rootId;

        Sent(String channelId, String message, String rootId) {
            this.channelId = channelId;
            this.message = message;
            this.rootId = rootId;
        }
    }

    final JsonNode me = Json.parse("{\"id\":\"bot1\",\"username\":\"captain\"}");
    final Map<String, Sent> posts = new LinkedHashMap<>(); // id → Post
    final List<String[]> updates = new ArrayList<>();
    final List<List<String>> reactions = new ArrayList<>(); // (op, post, emoji)
    final AtomicInteger typings = new AtomicInteger();
    final Object lock = new Object();
    final Map<String, List<JsonNode>> roots = new HashMap<>();
    volatile List<JsonNode> channel = new ArrayList<>(); // für getChannelPosts
    final AtomicInteger threadCalls = new AtomicInteger();
    volatile List<JsonNode> channels = new ArrayList<>(); // für myChannels (Nachholen)
    final Map<String, List<JsonNode>> history = new HashMap<>(); // Kanal → Posts (für getPostsAfter)
    final List<Map.Entry<String, Long>> afterCalls = new ArrayList<>();
    final Map<String, Map<String, Object>> props = new HashMap<>(); // Post-ID → props eigener Posts
    volatile Long maxBytes;
    // austauschbar wie per Monkeypatch
    volatile Function<String, List<JsonNode>> threadHook;
    volatile Supplier<List<JsonNode>> channelsHook;

    @Override
    public JsonNode me() {
        return me;
    }

    @Override
    public String createPost(String channelId, String message, String rootId, List<String> fileIds,
            Map<String, Object> props) {
        synchronized (lock) {
            String pid = "b" + (posts.size() + 1);
            posts.put(pid, new Sent(channelId, message, rootId));
            this.props.put(pid, props == null ? new HashMap<>() : new HashMap<>(props));
            return pid;
        }
    }

    @Override
    public void updatePost(String postId, String message, Map<String, Object> props) {
        synchronized (lock) {
            if (props != null) { // nur Props setzen (Abschluss einer Antwort)
                this.props.put(postId, new HashMap<>(props));
                if (posts.get(postId).message.equals(message)) {
                    return;
                }
            }
            posts.get(postId).message = message;
            updates.add(new String[] {postId, message});
        }
    }

    @Override
    public void typing(String channelId, String parentId) {
        typings.incrementAndGet();
    }

    final AtomicInteger userCalls = new AtomicInteger();
    /** Gesetzt: Download meldet den Start und wartet dann (Abbruch während des Ladens). */
    volatile CountDownLatch downloadStarted;
    volatile CountDownLatch downloadGate;

    @Override
    public JsonNode getUser(String userId) {
        userCalls.incrementAndGet();
        JsonNode user = USERS.get(userId);
        if (user == null) {
            throw new IllegalArgumentException("unbekannter Nutzer " + userId);
        }
        return user;
    }

    @Override
    public List<JsonNode> getThread(String rootId) {
        Function<String, List<JsonNode>> hook = threadHook;
        if (hook != null) {
            return hook.apply(rootId);
        }
        return thread(rootId);
    }

    List<JsonNode> thread(String rootId) {
        threadCalls.incrementAndGet();
        return roots.getOrDefault(rootId, List.of());
    }

    @Override
    public List<JsonNode> getChannelPosts(String channelId) {
        return new ArrayList<>(channel);
    }

    @Override
    public List<JsonNode> myChannels() {
        Supplier<List<JsonNode>> hook = channelsHook;
        return hook != null ? hook.get() : channelList();
    }

    List<JsonNode> channelList() {
        return new ArrayList<>(channels);
    }

    @Override
    public List<JsonNode> getPostsAfter(String channelId, long after) {
        synchronized (lock) {
            afterCalls.add(Map.entry(channelId, after));
            List<JsonNode> out = new ArrayList<>(history.getOrDefault(channelId, List.of()).stream()
                    .filter(p -> Json.lng(p, "create_at") > after).toList());
            out.sort(Comparator.comparingLong(p -> Json.lng(p, "create_at")));
            return out;
        }
    }

    @Override
    public String downloadFile(String fileId, String destDir, Long maxBytes) {
        this.maxBytes = maxBytes;
        CountDownLatch gate = downloadGate;
        if (gate != null) {
            downloadStarted.countDown();
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (fileId.startsWith("big")) {
            throw new AttachmentTooLargeException(fileId + ".pdf", 30L * 1024 * 1024, maxBytes);
        }
        Path path = Path.of(destDir, fileId + ".txt");
        try {
            Files.writeString(path, "x");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return path.toString();
    }

    @Override
    public void addReaction(String postId, String emoji) {
        synchronized (lock) {
            reactions.add(List.of("add", postId, emoji));
        }
    }

    @Override
    public void removeReaction(String postId, String emoji) {
        synchronized (lock) {
            reactions.add(List.of("remove", postId, emoji));
        }
    }

    @Override
    public void listen(Consumer<Post> onPost, Runnable onHello) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
    }

    List<String> finalMessages() {
        synchronized (lock) {
            return posts.values().stream().map(s -> s.message).toList();
        }
    }

    List<Sent> sent() {
        synchronized (lock) {
            return List.copyOf(posts.values());
        }
    }

    static ObjectNode raw(String pid, String ch, String user, String text, long at, String root) {
        return Json.object().put("id", pid).put("channel_id", ch).put("user_id", user).put("message", text)
                .put("create_at", at).put("update_at", at).put("root_id", root).put("type", "");
    }

    static ObjectNode raw(String pid, String ch, String user, String text, long at) {
        return raw(pid, ch, user, text, at, "");
    }
}
