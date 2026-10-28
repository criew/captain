package com.ewerlin.captain.opencode;

import java.util.ArrayList;
import java.util.List;

import com.ewerlin.captain.policy.SharedDir;
import com.ewerlin.captain.policy.Webfetch;

/** Session-Regeln: nur Dateizugriff im Session-Verzeichnis (plus webfetch-Allowlist, {@code /shared}). */
public final class SessionPermissions {

    /**
     * Datei-Aktionen, die eine Session in ihrem Verzeichnis darf (v2.0.20–2.2.0): {@code edit} deckt die
     * Tools edit, write und apply_patch ab. Einen eigenen list-Aktionsnamen gibt es in v2 nicht
     * (Verzeichnisse listet glob/read).
     */
    public static final List<String> FILE_ACTIONS = List.of("read", "edit", "glob", "grep");

    /**
     * Namen, die opencode ab dem Session-Verzeichnis aufwärts als Projekt-Config, Plugins
     * ({@code .opencode/plugins}, läuft als JS im Server!) oder Anweisungen lädt.
     */
    public static final List<String> PROTECTED =
            List.of(".opencode", ".claude", ".agents", "opencode.json*", "AGENTS.md", "CLAUDE.md");

    /**
     * Aktionen, die eine Session nie bekommt – als letzte Regeln der Session, also auch dann
     * verborgen, wenn die (editierbare) Admin-Config sie freigibt. {@code execute} ist Code Mode: ein
     * Skript-Tool mit eigenem {@code fetch} (Netz!), das opencode nicht über Policies prüft.
     * {@code opencode_*}: MCP-Ressourcen.
     */
    public static final List<String> DENIED_ACTIONS = List.of(
            "shell", "webfetch", "websearch", "subagent", "skill", "question", "execute",
            "opencode_*", "external_directory");

    private SessionPermissions() {
    }

    /** Eingebaute Aktion (Datei-Tools oder {@link #DENIED_ACTIONS}) – nie als Agent-Tool freigebbar. */
    public static boolean reserved(String tool) {
        String name = tool.endsWith("*") ? tool.substring(0, tool.length() - 1) : tool;
        return name.isEmpty() || FILE_ACTIONS.contains(name) || DENIED_ACTIONS.stream()
                .anyMatch(d -> d.endsWith("*") ? name.startsWith(d.substring(0, d.length() - 1)) : d.equals(name));
    }

    /**
     * Session-Regeln für {@code directory}.
     *
     * <p>opencode wertet Agent-Defaults, globale Config und dann diese Regeln aus; die letzte
     * passende gewinnt. Global ist alles verboten, hier wird nur Dateizugriff freigegeben. Innerhalb
     * des Session-Verzeichnisses prüft opencode {@code read}/{@code edit} mit <i>relativen</i> Pfaden
     * (daher {@code *}), Pfade außerhalb laufen zusätzlich über {@code external_directory} (verboten)
     * und tauchen dann absolut auf (daher {@code /*} verboten außer {@code directory}).
     * {@code glob}/{@code grep} prüfen das Suchmuster, der Suchpfad fällt ebenfalls unter
     * {@code external_directory}. Zum Schluss werden {@link #DENIED_ACTIONS} verboten – das
     * überstimmt auch Freigaben aus der Admin-Config (die vor den Session-Regeln ausgewertet wird);
     * MCP-Tools ({@code <server>_<tool>}) bleiben der Admin-Config überlassen.
     *
     * <p>{@code webfetch}: kanonische Präfixe aus {@code CAPTAIN_WEBFETCH_ALLOW}
     * ({@link Webfetch#parse}). Dann folgen auf {@code webfetch * deny} die Freigaben
     * {@code <präfix>} und {@code <präfix>/*} und danach Verbote für {@code ..}-Segmente und
     * Steuerzeichen – so überstimmt keine Regel der Admin-Config die Allowlist, in keine Richtung.
     *
     * <p>{@code shared}: geteiltes Verzeichnis an ({@code CAPTAIN_SHARED_DIR}). Dann folgen zum
     * Schluss {@link SharedDir#sessionRules}: {@code external_directory} und {@code read} für
     * {@code /shared} und darunter erlaubt, {@code edit} dort verboten.
     */
    public static List<Rule> of(String directory, List<String> webfetch, boolean shared, List<String> agentTools,
            List<String> allowTools) {
        String root = directory.replaceAll("/+$", "");
        List<Rule> rules = new ArrayList<>();
        for (String a : FILE_ACTIONS) {
            rules.add(Rule.allow(a, "*"));
        }
        for (String action : List.of("read", "edit")) {
            rules.add(Rule.deny(action, "/*"));
            rules.add(Rule.allow(action, root + "/*"));
        }
        // Defense in depth: nichts anlegen/lesen, was opencode als Projekt-Config, Plugin oder
        // Anweisung laden könnte (abgeschaltet ist das ohnehin per OPENCODE_DISABLE_PROJECT_CONFIG
        // im Container). "*/" deckt den absoluten Pfad und Unterverzeichnisse ab.
        for (String action : List.of("read", "edit")) {
            for (String name : PROTECTED) {
                for (String pattern : List.of(name, name + "/*", "*/" + name, "*/" + name + "/*")) {
                    rules.add(Rule.deny(action, pattern));
                }
            }
        }
        for (String tool : agentTools) {
            rules.add(Rule.deny(tool, "*"));
        }
        for (String tool : allowTools) {
            rules.add(Rule.allow(tool, "*"));
        }
        for (String a : DENIED_ACTIONS) {
            rules.add(Rule.deny(a, "*"));
        }
        rules.addAll(Webfetch.sessionRules(webfetch));
        rules.addAll(SharedDir.sessionRules(shared));
        return rules;
    }

    public static List<Rule> of(String directory, List<String> webfetch, boolean shared) {
        return of(directory, webfetch, shared, List.of(), List.of());
    }

    public static List<Rule> of(String directory) {
        return of(directory, List.of(), false);
    }
}
