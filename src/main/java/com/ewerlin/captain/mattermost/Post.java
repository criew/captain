package com.ewerlin.captain.mattermost;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ewerlin.captain.util.Json;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Ein Mattermost-Post anderer Nutzer.
 *
 * @param channelType {@code D}/{@code G}/{@code O}/{@code P}
 * @param senderName  Username ohne führendes „@“
 * @param fromBot     Post eines Bots oder Webhooks (Props)
 * @param createAt    ms seit Epoche
 */
public record Post(
        String id,
        String channelId,
        String channelType,
        String rootId,
        String userId,
        String senderName,
        String message,
        List<String> fileIds,
        boolean fromBot,
        long createAt) {

    private static final Logger log = LoggerFactory.getLogger(Post.class);

    public Post {
        fileIds = List.copyOf(fileIds);
    }

    public Post withSenderName(String name) {
        return new Post(id, channelId, channelType, rootId, userId, name, message, fileIds, fromBot, createAt);
    }

    /**
     * WebSocket-Event → Post; {@code null} für alles außer {@code posted}, für eigene Posts des Bots
     * und für System-Posts ({@code type} != "").
     */
    public static Post parsePosted(JsonNode event, String botUserId) {
        if (!"posted".equals(Json.str(event, "event"))) {
            return null;
        }
        JsonNode data = event.path("data");
        JsonNode raw = data.get("post");
        JsonNode post;
        try {
            post = raw != null && raw.isString() ? Json.parse(raw.asString()) : raw;
        } catch (JacksonException e) {
            String text = raw.asString();
            log.warn("posted-Event mit ungültigem JSON: {}", text.length() > 200 ? text.substring(0, 200) : text);
            return null;
        }
        if (post == null || !post.isObject()) {
            return null;
        }
        return fromJson(post, Json.str(data, "channel_type"), Json.str(data, "sender_name"), botUserId);
    }

    /**
     * Post-Objekt der REST-API bzw. aus einem Event → Post; {@code null} für System-Posts
     * ({@code type} != ""), gelöschte Posts und eigene Posts des Bots.
     */
    public static Post fromJson(JsonNode post, String channelType, String senderName, String botUserId) {
        if (Json.truthy(post, "type") || Json.truthy(post, "delete_at")) {
            return null;
        }
        if (botUserId != null && !botUserId.isEmpty() && Json.str(post, "user_id").equals(botUserId)) {
            return null;
        }
        String sender = senderName == null ? "" : senderName;
        while (sender.startsWith("@")) {
            sender = sender.substring(1);
        }
        return new Post(
                Json.str(post, "id"),
                Json.str(post, "channel_id"),
                channelType,
                Json.str(post, "root_id"),
                Json.str(post, "user_id"),
                sender,
                Json.str(post, "message"),
                Json.list(post.get("file_ids")).stream().map(Json::scalar).toList(),
                fromBotProps(post),
                Json.lng(post, "create_at"));
    }

    /** {@code props.from_bot} bzw. {@code props.from_webhook} = {@code "true"}? */
    public static boolean fromBotProps(JsonNode post) {
        JsonNode props = post.get("props");
        if (props == null || !props.isObject()) {
            return false;
        }
        for (String key : List.of("from_bot", "from_webhook")) {
            if (Json.str(props, key).toLowerCase(Locale.ROOT).equals("true")) {
                return true;
            }
        }
        return false;
    }
}
