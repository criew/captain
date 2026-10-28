package com.ewerlin.captain.api;

/** Unterhaltungen des REST-Eingabekanals (umgesetzt vom Bot, in Tests ersetzbar). */
public interface Conversations {

    /** Antwort: opencode-Session und Text. */
    record Answer(String session, String text) {
    }

    /**
     * Beantwortet {@code text} in der Unterhaltung {@code id}; blockiert bis zur fertigen Antwort.
     *
     * @param sender Name vor der Nachricht ({@code Name: Text}); {@code null} = keiner
     * @param agent  opencode-Agent (nur beim ersten Aufruf nötig, danach gemerkt); {@code null} = wie
     *               bisher bzw. Standard
     * @throws AgentRejectedException Agent nicht erlaubt (400) oder passt nicht zur Unterhaltung (409)
     */
    Answer ask(String id, String text, String sender, String agent);

    default Answer ask(String id, String text, String sender) {
        return ask(id, text, sender, null);
    }

    /** Prüft vorab, ob {@code agent} zur Unterhaltung passt (wie {@link #ask}, ohne etwas zu ändern). */
    void checkAgent(String id, String agent);

    /** Vergisst die Unterhaltung (neue Session beim nächsten Aufruf); {@code false} = gab es nicht. */
    boolean forget(String id);
}
