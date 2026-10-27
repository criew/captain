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
     */
    Answer ask(String id, String text, String sender);

    /** Vergisst die Unterhaltung (neue Session beim nächsten Aufruf); {@code false} = gab es nicht. */
    boolean forget(String id);
}
