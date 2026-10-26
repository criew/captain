package com.ewerlin.captain.policy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ewerlin.captain.opencode.Rule;

/**
 * Geteiltes Verzeichnis ({@code CAPTAIN_SHARED_DIR}): nur lesend für alle Chats.
 *
 * <p>Der Admin nennt in der {@code .env} ein Host-Verzeichnis, z. B.
 * {@code CAPTAIN_SHARED_DIR=/srv/captain-shared}. {@code compose.deploy.yml} bindet es
 * schreibgeschützt ({@code :ro}) unter dem festen Pfad {@link #MOUNT} ({@code /shared}) in den
 * opencode-Container ein. Leer = Feature aus (dann hängt dort nur das leere Volume
 * {@code shared-leer}, und nichts gibt es frei).
 *
 * <p>Wie opencode (an 2.0.20 ermittelt, mit 2.1.2 getestet) Pfade außerhalb des Session-Verzeichnisses prüft (aus dem Binary,
 * {@code FileAccess.resolve}; belegt durch {@code SharedIntegrationTest}):
 * <ul>
 * <li>Der Pfad des Modells wird <b>lexikalisch</b> aufgelöst ({@code path.resolve} gegen das
 * Session-Verzeichnis: {@code ..} und {@code .} fallen weg, <b>kein realpath</b>).</li>
 * <li>Liegt er außerhalb, prüft opencode zuerst {@code external_directory} mit Ressource
 * {@code <verzeichnis>/*} (bei einer Datei ihr Elternverzeichnis), dann {@code read} mit dem
 * absoluten Pfad; {@code glob}/{@code grep} prüfen das Suchmuster, der Suchpfad läuft ebenfalls
 * über {@code external_directory}.</li>
 * <li>Muster: {@code *} = beliebig viel (auch {@code /}), {@code ?} = ein Zeichen,
 * Groß-/Kleinschreibung zählt. {@code /shared/*} passt also auf {@code /shared/*} (das Verzeichnis
 * selbst) und alles darunter, nicht auf {@code /sharedX/*}.</li>
 * <li><b>Symlinks</b> löst opencode nicht auf – {@code /shared/x} → {@code /etc/passwd} würde als
 * {@code /shared/x} geprüft und dann gelesen. Deshalb prüft {@code infra/opencode/shared.mjs} beim
 * Start und danach alle 10 s, dass unter {@code /shared} keine Symlinks (und keine Dateien mit
 * mehreren harten Links, keine Captain-eigenen Verzeichnisse) liegen; sonst startet bzw. läuft
 * opencode nicht weiter (fail closed).</li>
 * <li>{@code .git} (in jeder Tiefe) ist gesperrt: {@code .git/config} kann Zugangsdaten in
 * Remote-URLs enthalten.</li>
 * </ul>
 *
 * <p>Dieselbe Pfadprüfung steckt in {@code infra/opencode/shared.mjs} (gemeinsame Testfälle:
 * {@code src/test/resources/data/shared_dir.json}); dort entstehen auch die Policies
 * ({@code external_directory:/shared/*} erlaubt, {@code .git} und {@code edit} unter
 * {@code /shared} verboten).
 */
public final class SharedDir {

    public static final String ENV = "CAPTAIN_SHARED_DIR";
    /** Fester Pfad im opencode-Container (compose.deploy.yml). */
    public static final String MOUNT = "/shared";

    /** Wurzel und Systemverzeichnisse: nie als Ganzes freigeben. */
    public static final Set<String> SYSTEM_DIRS = Set.of(
            "/", "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib32", "/lib64", "/media", "/mnt",
            "/opt", "/proc", "/root", "/run", "/sbin", "/srv", "/sys", "/tmp", "/usr", "/var");

    /**
     * Git-Innenleben in jeder Tiefe: {@code .git} und Bare-/Mirror-Repos ({@code x.git}), als
     * Verzeichnis oder Datei – {@code config} kann Zugangsdaten in Remote-URLs enthalten. {@code *}
     * passt auch auf {@code /} und auf nichts, {@code *.git} deckt also {@code /shared/.git} und
     * {@code /shared/a/b.git} ab, {@code .github} aber nicht. Alle Schreibweisen von {@code git}
     * (case-insensitive Dateisysteme wie CIFS lösen {@code .GIT} zu {@code .git} auf; die Muster
     * unterscheiden Groß/Klein).
     */
    public static final List<String> GIT_PATTERNS = gitPatterns();
    /** Dateien mit Zugangsdaten: nie lesbar (start.mjs lehnt sie unter /shared zusätzlich ab). */
    public static final List<String> SECRET_NAMES = List.of(".git-credentials", ".netrc", "_netrc");
    public static final List<String> SECRET_PATTERNS = secretPatterns();
    /** Unter CAPTAIN_HOME ist nur dieses erste Segment erlaubt (config, data, sessions, opencode … nicht). */
    public static final String HOME_SUBDIR = "shared";

    // Doppelpunkt trennt in Compose Quelle/Ziel/Optionen, "$" wäre eine Compose-Variable;
    // Steuerzeichen und "\" haben in einem Pfad nichts verloren.
    private static final Pattern FORBIDDEN = Pattern.compile("[:$\\\\\\x00-\\x1f\\x7f]");

    private SharedDir() {
    }

    private static List<String> gitPatterns() {
        List<String> out = new ArrayList<>();
        for (char a : "gG".toCharArray()) {
            for (char b : "iI".toCharArray()) {
                for (char c : "tT".toCharArray()) {
                    String g = "" + a + b + c;
                    out.add(MOUNT + "/*." + g);
                    out.add(MOUNT + "/*." + g + "/*");
                }
            }
        }
        return List.copyOf(out);
    }

    private static List<String> secretPatterns() {
        List<String> out = new ArrayList<>();
        for (String n : List.of(".git-credentials", ".netrc", "_netrc")) {
            out.add(MOUNT + "/" + n);
            out.add(MOUNT + "/*/" + n);
        }
        return List.copyOf(out);
    }

    private static List<String> segments(String path) {
        return Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * {@code path} gegen {@code CAPTAIN_HOME} prüfen (segmentweise, exakt).
     *
     * <p>Erlaubt ist nur {@code <home>/shared} oder darunter; verboten sind {@code <home>} selbst,
     * alle Vorfahren und jedes andere erste Segment. {@code home} leer = keine Prüfung (Test-Setup);
     * nicht absolut → Fehler.
     */
    public static void checkHome(String path, String home) {
        String raw = home == null ? "" : home.strip();
        if (path == null || path.isEmpty() || raw.isEmpty()) {
            return;
        }
        if (!raw.startsWith("/") || segments(raw).stream().anyMatch(s -> s.equals(".") || s.equals(".."))) {
            throw new IllegalArgumentException(
                    "CAPTAIN_HOME muss ein absoluter Pfad ohne '.'/'..' sein: " + Webfetch.quote(raw));
        }
        List<String> h = segments(raw);
        List<String> p = segments(path);
        if (!p.subList(0, Math.min(h.size(), p.size())).equals(h)) {
            if (h.subList(0, Math.min(p.size(), h.size())).equals(p)) {
                throw new IllegalArgumentException(ENV + ": " + path + " enthält CAPTAIN_HOME (" + raw
                        + ") – dort liegen Sessions und Datenbank");
            }
            return;
        }
        if (p.size() == h.size() || !p.get(h.size()).equals(HOME_SUBDIR)) {
            throw new IllegalArgumentException(ENV + ": unter CAPTAIN_HOME (" + raw + ") ist nur "
                    + Webfetch.rstrip(raw, '/') + "/" + HOME_SUBDIR + "[/…] erlaubt: " + path);
        }
    }

    /**
     * Host-Pfad prüfen → kanonischer Pfad oder {@code null} (Feature aus).
     *
     * <p>Erlaubt: absoluter POSIX-Pfad ohne {@code .}/{@code ..}-Segmente, nicht {@code /} und kein
     * Systemverzeichnis wie {@code /etc}; ein {@code /} am Ende fällt weg. Mit {@code home}
     * ({@code CAPTAIN_HOME}) zusätzlich {@link #checkHome}. Wirft {@link IllegalArgumentException} –
     * lieber nicht starten als etwas anderes freigeben.
     */
    public static String normalize(String value, String home) {
        String raw = value == null ? "" : value.strip();
        if (raw.isEmpty()) {
            return null;
        }
        if (!raw.chars().allMatch(c -> c < 128)) {
            throw new IllegalArgumentException(ENV + ": nur ASCII-Pfade: " + Webfetch.quote(raw));
        }
        Matcher bad = FORBIDDEN.matcher(raw);
        if (bad.find()) {
            throw new IllegalArgumentException(
                    ENV + ": Zeichen " + Webfetch.quote(bad.group()) + " nicht erlaubt: " + Webfetch.quote(raw));
        }
        if (!raw.startsWith("/")) {
            throw new IllegalArgumentException(ENV + ": muss ein absoluter Pfad sein (/…): " + Webfetch.quote(raw));
        }
        String path = Webfetch.rstrip(raw, '/');
        if (path.isEmpty()) {
            path = "/";
        }
        if (!path.equals("/")) {
            String[] parts = path.split("/", -1);
            for (int i = 1; i < parts.length; i++) {
                String s = parts[i];
                if (s.isEmpty() || s.equals(".") || s.equals("..")) {
                    throw new IllegalArgumentException(
                            ENV + ": Pfad ohne '.', '..' und leere Segmente: " + Webfetch.quote(raw));
                }
            }
        }
        if (SYSTEM_DIRS.contains(path)) {
            throw new IllegalArgumentException(ENV + ": " + path
                    + " ist die Wurzel oder ein Systemverzeichnis – eigenes Unterverzeichnis nehmen: "
                    + Webfetch.quote(raw));
        }
        checkHome(path, home);
        return path;
    }

    public static String normalize(String value) {
        return normalize(value, null);
    }

    /**
     * Session-Regeln, die <i>nach</i> den Verboten kommen (letzte gewinnt).
     *
     * <p>Gibt {@code external_directory} und {@code read} für {@link #MOUNT} und alles darunter frei
     * ({@code glob}/{@code grep} prüfen das Muster, sind schon erlaubt; ihren Suchpfad deckt
     * {@code external_directory} ab). Danach verboten: {@code .git}/{@code *.git} in jeder Tiefe
     * ({@link #GIT_PATTERNS}, für {@code external_directory} und {@code read}; ripgrep lässt
     * {@code .git} bei glob/grep ohnehin aus), Zugangsdaten-Dateien ({@link #SECRET_PATTERNS}) und
     * {@code edit} unter {@link #MOUNT}.
     */
    public static List<Rule> sessionRules(boolean enabled) {
        List<Rule> rules = new ArrayList<>();
        if (!enabled) {
            return rules;
        }
        String inside = MOUNT + "/*";
        rules.add(Rule.allow("external_directory", inside));
        rules.add(Rule.allow("read", MOUNT));
        rules.add(Rule.allow("read", inside));
        for (String action : List.of("external_directory", "read")) {
            for (String p : GIT_PATTERNS) {
                rules.add(Rule.deny(action, p));
            }
        }
        for (String p : SECRET_PATTERNS) {
            rules.add(Rule.deny("read", p));
        }
        rules.add(Rule.deny("edit", MOUNT));
        rules.add(Rule.deny("edit", inside));
        return rules;
    }
}
