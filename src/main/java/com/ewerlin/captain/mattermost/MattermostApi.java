package com.ewerlin.captain.mattermost;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import tools.jackson.databind.JsonNode;

/** Was der Bot von Mattermost braucht (implementiert von {@link MattermostClient}, in Tests gefälscht). */
public interface MattermostApi {

    /** Der Bot-User (einmalig geladen). */
    JsonNode me();

    String createPost(String channelId, String message, String rootId, List<String> fileIds, Map<String, Object> props);

    /** Text ändern; {@code props} ({@code null} = unverändert) ersetzt die Props komplett ({@code from_bot} bleibt). */
    void updatePost(String postId, String message, Map<String, Object> props);

    /** Alle Posts eines Threads (inkl. Root), älteste zuerst. */
    List<JsonNode> getThread(String rootId);

    /** Die letzten Posts eines Kanals (inkl. Thread-Antworten), älteste zuerst. */
    List<JsonNode> getChannelPosts(String channelId);

    /** Alle Kanäle des Bots über alle Teams, inkl. DMs und Gruppen-DMs. */
    List<JsonNode> myChannels();

    /** Posts mit {@code create_at > after} (inkl. Thread-Antworten), älteste zuerst. */
    List<JsonNode> getPostsAfter(String channelId, long after);

    void addReaction(String postId, String emoji);

    void removeReaction(String postId, String emoji);

    void typing(String channelId, String parentId);

    JsonNode getUser(String userId);

    /**
     * Lädt eine Datei nach {@code destDir} und gibt den lokalen Pfad zurück; mit {@code maxBytes}
     * wird ein größerer Anhang nicht geladen → {@link AttachmentTooLargeException}.
     */
    String downloadFile(String fileId, String destDir, Long maxBytes);

    /**
     * Blockiert und ruft {@code onPost} für jeden neuen Post anderer Nutzer; {@code onHello} bei jeder
     * neuen Verbindung ohne Replay. Endet erst nach {@link #close()}.
     */
    void listen(Consumer<Post> onPost, Runnable onHello);

    /** Beendet {@code listen}. */
    void close();
}
