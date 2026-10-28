package com.ewerlin.captain.opencode;

import java.util.List;
import java.util.Map;

/** Was der Bot von opencode braucht (implementiert von {@link OpencodeClient}, in Tests gefälscht). */
public interface OpencodeApi {

    /**
     * Legt eine Session im (Container-)Verzeichnis {@code directory} an.
     *
     * @param permissions Session-Regeln, werden hinter die globalen Regeln gehängt (letzte passende
     *                    gewinnt, siehe {@link SessionPermissions#of}); {@code null} = keine
     * @param sessionId   eigene ID (muss mit {@code ses} beginnen), sonst vergibt opencode eine
     */
    String createSession(String directory, String title, List<Rule> permissions, String sessionId);

    /** Hängt dauerhafte Systemanweisungen an die Session (in Reihenfolge der Einträge). */
    void setInstructions(String sessionId, Map<String, String> entries);

    boolean sessionExists(String sessionId);

    /** Bricht eine laufende Ausführung ab; unbekannte Sessions werden ignoriert. */
    void abort(String sessionId);

    /** Löscht die Session samt Verlauf; unbekannte Sessions werden ignoriert. */
    void deleteSession(String sessionId);

    /** Verfügbare Modelle (ohne {@code directory}: Startverzeichnis von opencode). */
    List<ModelInfo> listModels(String directory);

    /**
     * Schickt {@code text} an die Session und wartet auf das Ende der Ausführung; liefert den
     * finalen Text.
     *
     * @throws SessionGoneException        Session existiert nicht (mehr)
     * @throws PromptInterruptedException  Ausführung abgebrochen
     * @throws OpencodeException           Fehler-Event, HTTP-Fehler, Zeitüberschreitung
     */
    String prompt(String sessionId, String text, PromptOptions options);
}
