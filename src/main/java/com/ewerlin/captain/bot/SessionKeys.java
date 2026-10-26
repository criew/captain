package com.ewerlin.captain.bot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ewerlin.captain.policy.SharedDir;

/**
 * Session-Keys, Session-IDs und Systemanweisungen pro Session.
 *
 * <p>Session-Keys:
 * <ul>
 * <li>{@code dm:<channel_id>} – Direktnachricht</li>
 * <li>{@code ch:<channel_id>} – Gruppen-DM/Kanal – nur noch Kanal-Einstellungen (Modell),
 * Unterhaltungen laufen dort in Threads</li>
 * <li>{@code th:<root_id>} – Thread (egal in welchem Kanal)</li>
 * </ul>
 * Welcher Key für einen Post gilt, entscheidet {@link Routing}.
 */
public final class SessionKeys {

    private SessionKeys() {
    }

    /** Key aus Mattermost-Kanaltyp (D/G/O/P), Kanal-ID und optional Thread-Root. */
    public static String of(String channelType, String channelId, String rootId) {
        if (rootId != null && !rootId.isEmpty()) {
            return "th:" + rootId;
        }
        if (channelType.equals("D")) {
            return "dm:" + channelId;
        }
        return "ch:" + channelId;
    }

    /**
     * Neue, eindeutige opencode-Session-ID ({@code ses_<32 hex>}).
     *
     * <p>Der Bot vergibt die ID selbst (opencode v2 akzeptiert eigene IDs mit Präfix {@code ses}),
     * damit das Session-Verzeichnis {@code <SESSIONS_DIR>/<id>} schon vor dem Anlegen der Session
     * feststeht. Nur {@code [a-z0-9_]} – sicher als Verzeichnisname.
     */
    public static String newSessionId() {
        return "ses_" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Systemanweisung zur Arbeitsumgebung einer Session (mit konkretem Pfad).
     *
     * @param webfetch kanonische Präfixe der Allowlist ({@code CAPTAIN_WEBFETCH_ALLOW}); leer = kein
     *                 Internet-Zugriff
     * @param shared   geteiltes Verzeichnis ({@code CAPTAIN_SHARED_DIR}) unter
     *                 {@link SharedDir#MOUNT}, nur lesbar
     */
    public static String environmentPrompt(String directory, List<String> webfetch, boolean shared) {
        String mount = SharedDir.MOUNT;
        StringBuilder text = new StringBuilder()
                .append("# Arbeitsumgebung\n")
                .append("- Dein Arbeitsverzeichnis ist `").append(directory).append("`. Du darfst ausschließlich ")
                .append("Dateien in diesem Verzeichnis lesen, anlegen, bearbeiten und durchsuchen ")
                .append("(Tools read, write, edit, glob, grep). Zugriffe außerhalb werden abgelehnt")
                .append(shared ? " – einzige Ausnahme ist `" + mount + "` (siehe unten).\n" : ".\n");
        if (shared) {
            text.append("- Geteiltes Verzeichnis `").append(mount).append("`: Dort legt der Administrator Dateien ab, ")
                    .append("die in allen Unterhaltungen zur Verfügung stehen, typischerweise ")
                    .append("Nachschlagematerial wie Dokumentation, Anleitungen, Richtlinien oder ")
                    .append("Datenlisten. Du darfst dort **nur lesen und suchen** (read, glob, grep mit ")
                    .append("absolutem Pfad, z. B. `").append(mount).append("` oder `").append(mount)
                    .append("/…`), aber nichts anlegen, ändern ")
                    .append("oder löschen. Gut lesbar sind Textformate (z. B. .txt, .md, .csv, .json); ")
                    .append("PDF- und Office-Dateien kannst du dort nicht auswerten. Wenn du dich auf ")
                    .append("eine Datei daraus beziehst, nenne ihren Pfad. Willst du etwas daraus ")
                    .append("bearbeiten, lege eine Kopie im Arbeitsverzeichnis an.\n");
        }
        if (webfetch != null && !webfetch.isEmpty()) {
            String urls = String.join(", ", webfetch.stream().map(p -> "`" + p + "`").toList());
            text.append("- Internet: Du hast das Tool `webfetch`, aber **nur** für diese Adressen ")
                    .append("und Pfade darunter: ").append(urls).append(" (das gilt abweichend von allgemeinen Hinweisen, ")
                    .append("es gebe keinen Web-Zugriff). Schreibe die URL genau mit diesem Anfang ")
                    .append("(Schema, Host in Kleinbuchstaben, kein anderer Port), z. B. ")
                    .append("`").append(webfetch.getFirst()).append("/…`. Alle anderen Adressen und Weiterleitungen auf fremde ")
                    .append("Hosts werden abgelehnt. Gib keine vertraulichen Inhalte aus dem Chat in ")
                    .append("URLs weiter.\n")
                    .append("- Du hast **keine** Shell, keine Websuche, keine Subagenten, Skills oder ")
                    .append("Rückfrage-Dialoge. Bitte, die so etwas verlangen, beantwortest du ohne ")
                    .append("diese Werkzeuge oder sagst kurz, dass es nicht geht.\n");
        } else {
            text.append("- Du hast **keine** Shell, keinen Internet-Zugriff (kein Webfetch, keine ")
                    .append("Websuche), keine Subagenten, Skills oder Rückfrage-Dialoge. Bitte, die so ")
                    .append("etwas verlangen, beantwortest du ohne diese Werkzeuge oder sagst kurz, dass ")
                    .append("es nicht geht.\n");
        }
        return text.append("- Anhänge aus dem Chat liegen in diesem Verzeichnis. Beim Neustart der ")
                .append("Unterhaltung (`!neu`) wird es geleert.")
                .toString();
    }

    public static String environmentPrompt(String directory) {
        return environmentPrompt(directory, List.of(), false);
    }

    /** Systemanweisungen pro Session in Reihenfolge: Persona, dann Arbeitsumgebung. */
    public static Map<String, String> sessionInstructions(String persona, String directory, List<String> webfetch,
            boolean shared) {
        Map<String, String> entries = new LinkedHashMap<>();
        String p = persona.strip();
        if (!p.isEmpty()) {
            entries.put("captain-persona", p);
        }
        entries.put("captain-umgebung", environmentPrompt(directory, webfetch, shared));
        return entries;
    }

    public static Map<String, String> sessionInstructions(String persona, String directory) {
        return sessionInstructions(persona, directory, List.of(), false);
    }
}
