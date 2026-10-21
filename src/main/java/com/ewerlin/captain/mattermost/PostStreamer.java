package com.ewerlin.captain.mattermost;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

import com.ewerlin.captain.util.Clock;

/**
 * Schreibt eine wachsende Antwort gedrosselt in einen Post.
 *
 * <p>Jede Bearbeitung erzeugt in Mattermost eine Versionszeile und ein {@code post_edited}-Event,
 * daher höchstens eine Änderung pro {@code minInterval}. Texte über {@link #SPLIT_LEN} Zeichen werden
 * auf Folge-Posts verteilt.
 */
public class PostStreamer {

    /** Harte Grenze des Servers. */
    public static final int MAX_POST_LEN = 16383;
    /** Darüber verteilt der Streamer auf weitere Posts. */
    public static final int SPLIT_LEN = 16000;

    private final MattermostApi client;
    private final String channelId;
    private final String rootId;
    private final double minInterval;
    private final DoubleSupplier clock;
    private final List<String> postIds = new ArrayList<>();
    private final List<String> sent = new ArrayList<>(); // zuletzt gesendeter Inhalt je Post
    private double lastSend;
    private boolean hasText;
    private volatile boolean finished;

    public PostStreamer(MattermostApi client, String channelId, String rootId) {
        this(client, channelId, rootId, 1.5, Clock::monotonic);
    }

    public PostStreamer(MattermostApi client, String channelId, String rootId, double minInterval) {
        this(client, channelId, rootId, minInterval, Clock::monotonic);
    }

    public PostStreamer(MattermostApi client, String channelId, String rootId, double minInterval, DoubleSupplier clock) {
        this.client = client;
        this.channelId = channelId;
        this.rootId = rootId;
        this.minInterval = minInterval;
        this.clock = clock;
    }

    /** Zeigt einen Status, solange noch kein Antworttext da ist. */
    public synchronized void status(String text) {
        if (finished || hasText || text == null || text.isEmpty()) {
            return;
        }
        render(text, false);
    }

    public boolean finished() {
        return finished;
    }

    public synchronized List<String> postIds() {
        return List.copyOf(postIds);
    }

    /** Neuer Gesamtstand; legt den Post beim ersten Aufruf an, sonst gedrosselt. Nach {@link #finish} wirkungslos. */
    public synchronized void update(String fullText) {
        if (finished || fullText.strip().isEmpty()) {
            return;
        }
        boolean first = !hasText;
        hasText = true;
        render(fullText, first);
    }

    public void finish(String fullText) {
        finish(fullText, null);
    }

    /**
     * Endstand schreiben (ungedrosselt), ggf. auf mehrere Posts verteilt.
     *
     * <p>{@code props} landen am <b>letzten</b> Teil, und zwar erst, wenn alle Teile geschrieben sind
     * – ein Abbruch mittendrin hinterlässt sie also nicht.
     */
    public synchronized void finish(String fullText, Map<String, Object> props) {
        if (fullText.strip().isEmpty()) {
            fullText = "_(keine Antwort)_";
        }
        hasText = true;
        finished = true; // spätere update()/status() ignorieren
        render(fullText, true);
        if (props != null && !props.isEmpty()) {
            int last = postIds.size() - 1;
            client.updatePost(postIds.get(last), sent.get(last), props);
        }
    }

    private void render(String text, boolean force) {
        double now = clock.getAsDouble();
        if (!force && !postIds.isEmpty() && now - lastSend < minInterval) {
            return;
        }
        List<String> chunks = splitMessage(text, SPLIT_LEN);
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            if (i < postIds.size()) {
                if (!sent.get(i).equals(chunk)) {
                    client.updatePost(postIds.get(i), chunk, null);
                    sent.set(i, chunk);
                }
            } else {
                postIds.add(client.createPost(channelId, chunk, rootId, null, null));
                sent.add(chunk);
            }
        }
        lastSend = clock.getAsDouble();
    }

    public static List<String> splitMessage(String text) {
        return splitMessage(text, SPLIT_LEN);
    }

    /**
     * Teilt Text in Stücke ≤ {@code limit}, bevorzugt an Zeilenumbrüchen.
     *
     * <p>Die Grenzen hängen nur vom jeweiligen Präfix ab, sodass wachsender Text bereits gesendete
     * Stücke nicht mehr verschiebt. Surrogatpaare werden nie getrennt.
     */
    public static List<String> splitMessage(String text, int limit) {
        List<String> chunks = new ArrayList<>();
        while (text.length() > limit) {
            int cut = text.lastIndexOf('\n', limit);
            if (cut <= limit / 2) {
                cut = limit;
                if (Character.isHighSurrogate(text.charAt(cut - 1))) {
                    cut--;
                }
                chunks.add(text.substring(0, cut));
                text = text.substring(cut);
            } else {
                chunks.add(text.substring(0, cut));
                text = text.substring(cut + 1);
            }
        }
        if (!text.isEmpty() || chunks.isEmpty()) {
            chunks.add(text);
        }
        return chunks;
    }
}
