package com.ewerlin.captain.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ewerlin.captain.bot.SessionKeys;
import com.ewerlin.captain.opencode.Rule;
import com.ewerlin.captain.opencode.SessionPermissions;
import com.ewerlin.captain.util.Clock;
import com.ewerlin.captain.util.Json;

/**
 * Integrationstests: geteiltes Verzeichnis ({@code CAPTAIN_SHARED_DIR}) gegen echtes opencode.
 *
 * <p>Startet eigene opencode-Container aus dem Image ({@code CAPTAIN_OC_IMAGE}) mit Startskript,
 * Admin-Config-Vorlage und {@link FakeLlm} als Modell (ruft auf Kommando {@code read}/{@code glob}/
 * {@code grep}/{@code write} auf). {@code /shared} ist ein Docker-Volume, das wie im Betrieb
 * <b>schreibgeschützt</b> eingebunden ist; befüllt (und für die Symlink-Tests nachträglich
 * verändert) wird es über Hilfscontainer.
 *
 * <p>Nachweise: Datei unter {@code /shared} lesen, {@code glob}/{@code grep} dort funktionieren;
 * schreiben, {@code ..}-Ausbrüche, {@code /sharedX}, Großschreibung und fremde
 * Session-Verzeichnisse werden abgelehnt; Symlinks unter {@code /shared} lassen opencode nicht
 * starten bzw. beenden es (die Gegenprobe ohne Prüfung zeigt, dass opencode Symlinks sonst folgt);
 * ohne Variable gibt es keinen Zugriff, auch nicht mit Session-Regel {@code * * allow}. Braucht
 * Docker.
 */
@Tag("integration")
class SharedIntegrationTest {

    static final String PASSWORD = "sh-" + Integration.hex(8);
    static final String HOST_PATH = "/srv/captain-shared"; // Wert der Variable (Host-Pfad, nur geprüft)
    static final String SECRET = "GEHEIM-ANDERE-SESSION";
    static final String DOC = "Handbuch Zeile 1\nStichwort: Leuchtturm\n";
    static final String BLOCKED = "Blocked by configuration policy";
    static final String DENIED_EXT = "Permission denied: external_directory";
    static final List<Rule> ALLOW_ALL = List.of(Rule.allow("*", "*"));
    /** Inhalt des geteilten Volumes: Datei, Unterverzeichnis, Datei mit Leerzeichen. */
    static final String SEED = "mkdir -p /w/sub /w/leer && printf '%s' \"$DOC\" > /w/handbuch.txt"
            + " && printf 'Unterordner Leuchtturm\\n' > /w/sub/b.md && printf 'x\\n' > '/w/mit leer.txt'";

    static FakeLlm llm;
    @TempDir
    Path tmp;
    Volume volume;
    final List<Stack> made = new ArrayList<>();

    /** Docker-Volume als Quelle von /shared, beschreibbar nur über Hilfscontainer. */
    static final class Volume implements AutoCloseable {
        final String name = "captain-sh-" + Integration.hex(8);

        Volume() {
            Integration.docker("volume", "create", name);
            sh(SEED);
        }

        String sh(String script) {
            return Integration.docker("run", "--rm", "-e", "DOC=" + DOC, "-v", name + ":/w", "--entrypoint", "sh",
                    Integration.IMAGE, "-c", script);
        }

        @Override
        public void close() {
            Integration.dockerQuiet("volume", "rm", "-f", name);
        }
    }

    @BeforeAll
    static void startLlm() {
        Integration.requireImage();
        llm = new FakeLlm();
    }

    @AfterAll
    static void stopLlm() {
        if (llm != null) {
            llm.close();
        }
    }

    @BeforeEach
    void createVolume() {
        volume = new Volume();
    }

    @AfterEach
    void cleanup() {
        made.forEach(Stack::close);
        volume.close();
    }

    static List<String> runArgs(Path conf, String shared, String mount, List<String> extra) {
        List<String> args = new ArrayList<>(List.of(
                "--add-host", "host.docker.internal:host-gateway",
                "-e", "OPENCODE_SERVER_PASSWORD=" + PASSWORD,
                "-e", "LLM_BASE_URL=http://host.docker.internal:" + llm.port() + "/v1",
                "-e", "LLM_API_KEY=k", "-e", "LLM_MODEL=fake", "-e", "OPENCODE_MODEL=llm/fake",
                "-e", "CAPTAIN_SHARED_DIR=" + shared, "-e", "CAPTAIN_HOME=/opt/captain",
                "-v", Integration.mountPath(conf) + ":/etc/captain:ro"));
        if (mount != null) {
            args.addAll(List.of("-v", mount));
        }
        args.addAll(extra);
        args.add(Integration.IMAGE);
        return args;
    }

    Path conf() {
        return Integration.adminConf(tmp, Integration.template(), Map.of());
    }

    /** Container samt fremder Session mit Geheimnis. */
    final class Stack implements AutoCloseable {
        final OpencodeContainer c;
        final boolean shared;
        final String other = "/tmp/captain/" + SessionKeys.newSessionId();

        Stack(String sharedValue, String mount, List<String> extra, List<String> cmd) {
            List<String> args = runArgs(conf(), sharedValue, mount, extra);
            args.addAll(cmd);
            c = OpencodeContainer.start("captain-sh-" + Integration.hex(8), PASSWORD, llm, args);
            shared = !sharedValue.isEmpty();
            Integration.docker("exec", c.name, "sh", "-c", "mkdir -p " + other + " && echo " + SECRET + " > " + other + "/geheim.txt");
        }

        /** Neue Session wie beim Bot, Modell ruft {@code tool(args)}. */
        String call(String tool, Map<String, Object> args, List<Rule> permissions) {
            return c.ask("TOOL:" + tool + " " + Json.write(args), permissions, 90,
                    d -> SessionPermissions.of(d, List.of(), shared)).answer();
        }

        String call(String tool, Map<String, Object> args) {
            return call(tool, args, null);
        }

        List<String> offered(String tool, Map<String, Object> args) {
            return c.ask("TOOL:" + tool + " " + Json.write(args), null, 90, d -> SessionPermissions.of(d, List.of(), shared)).offered();
        }

        String read(String path, List<Rule> permissions) {
            return call("read", Map.of("path", path), permissions);
        }

        String read(String path) {
            return read(path, null);
        }

        @Override
        public void close() {
            c.close();
        }
    }

    Stack stack(String shared, String mount, List<String> extra, List<String> cmd) {
        Stack s = new Stack(shared, mount, extra, cmd);
        made.add(s);
        return s;
    }

    Stack stack(String mount) {
        return stack(HOST_PATH, mount, List.of(), List.of());
    }

    String ro() {
        return volume.name + ":/shared:ro";
    }

    /** opencode darf nicht starten → Log des Startskripts. */
    String startFails(String shared, String mount, List<String> extra) {
        String name = "captain-sh-" + Integration.hex(8);
        List<String> args = new ArrayList<>(List.of("run", "--name", name, "-p", "127.0.0.1::4096"));
        args.addAll(runArgs(conf(), shared, mount, extra));
        Integration.Result out;
        try {
            out = Integration.dockerRun(false, null, 90, args.toArray(String[]::new));
        } finally {
            Integration.dockerQuiet("rm", "-f", name);
        }
        assertThat(out.code()).as(out.all()).isNotZero();
        assertThat(out.err()).contains("opencode startet nicht");
        return out.err();
    }

    @Test
    void readGlobGrepAllowedWriteAndEscapesDenied() {
        volume.sh("mkdir -p /w/repo/.git && printf '[remote]\\n url = https://user:TOKEN-123@git.example/x\\n' "
                + "> /w/repo/.git/config && printf 'Repo-Inhalt Leuchtturm\\n' > /w/repo/README.md");
        Stack s = stack(ro());

        // lesen: Datei, Verzeichnis, Unterordner, Leerzeichen, relativ aus dem Session-Verzeichnis
        OpencodeContainer.Asked a = s.c.ask("TOOL:read " + Json.write(Map.of("path", "/shared/handbuch.txt")), null, 90,
                d -> SessionPermissions.of(d, List.of(), true));
        assertThat(a.answer()).contains("Stichwort: Leuchtturm");
        // opencode 2.2.0 bietet zusätzlich opencode_worktree_remove an: Permission "edit" (nicht per
        // Regel verbergbar, Policies prüfen "edit:<pfad>", nicht den Tool-Namen). Außerhalb des
        // Session-Verzeichnisses greift die edit-Sperre, darin gibt es keine Git-Worktrees.
        assertThat(a.offered().stream().filter(t -> !t.equals("opencode_worktree_remove")).sorted().toList())
                .containsExactly("edit", "glob", "grep", "read", "write");
        for (String dir : List.of("/tmp/captain", "/shared", "..")) {
            OpencodeContainer.Asked w = s.c.ask("TOOL:opencode_worktree_remove " + Json.write(Map.of("directory", dir, "force", true)),
                    null, 90, d -> SessionPermissions.of(d, List.of(), true));
            assertThat(w.answer()).as(dir).contains("Permission denied: edit");
        }
        OpencodeContainer.Asked w = s.c.ask("TOOL:opencode_worktree_remove " + Json.write(Map.of("directory", ".", "force", true)),
                null, 90, d -> SessionPermissions.of(d, List.of(), true));
        assertThat(w.answer()).contains("not a worktree");
        assertThat(s.read("/shared")).contains("handbuch.txt");
        assertThat(s.read("/shared/")).contains("sub/");
        assertThat(s.read("/shared/sub/b.md")).contains("Unterordner Leuchtturm");
        assertThat(s.read("/shared/mit leer.txt")).contains("1: x");
        assertThat(s.read("../../../shared/handbuch.txt")).contains("Stichwort");

        // glob/grep unter /shared (ohne .git-Inhalte)
        String answer = s.call("glob", Map.of("pattern", "**/*.md", "path", "/shared"));
        assertThat(answer).contains("/shared/sub/b.md", "/shared/repo/README.md");
        answer = s.call("grep", Map.of("pattern", "Leuchtturm", "path", "/shared"));
        assertThat(answer).contains("/shared/handbuch.txt", "/shared/sub/b.md");
        for (Map<String, Object> args : List.<Map<String, Object>>of(Map.of("tool", "grep", "pattern", "TOKEN-123", "path", "/shared"),
                Map.of("tool", "grep", "pattern", "TOKEN", "path", "/shared", "include", "**/.git/**"),
                Map.of("tool", "glob", "pattern", "**/.git/**", "path", "/shared"),
                Map.of("tool", "glob", "pattern", "../tmp/captain/**", "path", "/shared"))) {
            Map<String, Object> rest = new java.util.LinkedHashMap<>(args);
            String tool = (String) rest.remove("tool");
            answer = s.call(tool, rest);
            assertThat(answer).as(args.toString()).doesNotContain("TOKEN-123", "geheim", ".git/config");
        }

        // .git gesperrt, auch über Umwege und als Suchpfad von glob/grep
        for (String path : List.of("/shared/repo/.git/config", "/shared/repo/.git", "/shared/repo/./.git/config",
                "/shared/sub/../repo/.git/config", "/shared/repo/.GIT/config", "/shared/.netrc")) {
            answer = s.read(path);
            assertThat(answer).as(path).doesNotContain("TOKEN-123").contains("permission.rejected");
        }
        assertThat(s.call("grep", Map.of("pattern", "TOKEN", "path", "/shared/repo/.git"))).contains(DENIED_EXT);
        assertThat(s.call("glob", Map.of("pattern", "*", "path", "/shared/repo/.git"))).contains(DENIED_EXT);

        // abgelehnt: fremde Session (direkt und per ..), /sharedX, Großschreibung, /etc, Wurzel
        for (String path : List.of(s.other + "/geheim.txt", "/shared/../" + s.other + "/geheim.txt",
                "/shared/sub/../.." + s.other + "/geheim.txt", "/sharedX/x",
                "/shared-x/handbuch.txt", "/SHARED/handbuch.txt", "/etc/passwd", "/")) {
            answer = s.read(path);
            assertThat(answer).as(path).contains(DENIED_EXT).doesNotContain(SECRET);
        }
        answer = s.call("grep", Map.of("pattern", "GEHEIM", "path", "/shared/../tmp/captain"));
        assertThat(answer).contains(DENIED_EXT).doesNotContain(SECRET);

        // schreiben verboten (Session-Regel); mit "* * allow" greift die Policy
        for (Map.Entry<String, Map<String, Object>> t : List.of(
                Map.entry("write", Map.<String, Object>of("path", "/shared/neu.txt", "content", "x")),
                Map.entry("edit", Map.<String, Object>of("path", "/shared/handbuch.txt", "oldString", "Zeile", "newString", "X")))) {
            assertThat(s.call(t.getKey(), t.getValue())).as(t.getKey()).contains("Permission denied: edit");
            assertThat(s.call(t.getKey(), t.getValue(), ALLOW_ALL)).as(t.getKey()).contains(BLOCKED);
        }
        assertThat(Integration.docker("exec", s.c.name, "ls", "/shared")).doesNotContain("neu.txt");
        assertThat(s.read("/shared/handbuch.txt")).contains("Zeile 1");

        // Gegenprobe ohne Session-Sperren: die Policies aus start.mjs sind die Obergrenze
        assertThat(s.read("/shared/handbuch.txt", ALLOW_ALL)).contains("Stichwort");
        for (String path : List.of("/etc/passwd", s.other + "/geheim.txt", "/shared/repo/.git/config", "/sharedX/x",
                "/home/captain/.local/share/opencode/opencode.db")) {
            answer = s.read(path, ALLOW_ALL);
            assertThat(answer).as(path).contains(BLOCKED).doesNotContain(SECRET, "TOKEN-123");
        }

        // Änderungen ohne Neustart sichtbar
        volume.sh("printf 'neu hinzugekommen\\n' > /w/neu.txt");
        assertThat(s.read("/shared/neu.txt")).contains("neu hinzugekommen");

        // opencode selbst bekommt die Variablen nicht, nur die Policies
        String env = s.c.env();
        assertThat(env).doesNotContain("CAPTAIN_SHARED_DIR", "CAPTAIN_HOME")
                .contains("\"resource\":\"external_directory:/shared/*\",\"effect\":\"allow\"");
    }

    /** Ohne CAPTAIN_SHARED_DIR: auch ein eingebundenes /shared bleibt gesperrt. */
    @Test
    void withoutVariableNoAccess() {
        Stack s = stack("", ro(), List.of(), List.of());
        assertThat(s.shared).isFalse();
        String answer = s.read("/shared/handbuch.txt");
        assertThat(answer).contains(DENIED_EXT).doesNotContain("Stichwort");
        // selbst mit Session-Regeln für /shared (Bot falsch konfiguriert) bzw. "* * allow"
        String directory = "/tmp/captain/" + SessionKeys.newSessionId();
        for (List<Rule> rules : List.of(SessionPermissions.of(directory, List.of(), true), ALLOW_ALL)) {
            answer = s.read("/shared/handbuch.txt", rules);
            assertThat(answer).contains(BLOCKED).doesNotContain("Stichwort");
        }
        assertThat(s.call("grep", Map.of("pattern", "Leuchtturm", "path", "/shared"), ALLOW_ALL)).contains(BLOCKED);
        assertThat(s.c.logs()).contains("geteiltes Verzeichnis aus");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ln -s /etc/passwd /w/passwd",
            "ln -s /tmp/captain /w/sessions",
            "mkdir -p /w/a/b && ln -s ../../handbuch.txt /w/a/b/rel",
            "ln -s /home/captain/.local/share/opencode /w/sub/db",
            "ln /w/handbuch.txt /w/hart.txt",
            "mkfifo /w/fifo",
            "mkdir -p /w/m.git/objects /w/m.git/refs && echo ref > /w/m.git/HEAD",
            "echo 'machine x password y' > /w/sub/.netrc"})
    void symlinksAndSpecialFilesBlockStart(String bad) {
        volume.sh(bad);
        String err = startFails(HOST_PATH, ro(), List.of());
        assertThat(err).contains("/shared/", "nicht erlaubt");
    }

    /**
     * Ohne die Prüfung im Startskript liest opencode über Symlinks fremde Daten: opencode mit genau
     * der Umgebung aus start.mjs (Policies mit /shared-Freigabe), aber ohne Prüfung von /shared –
     * Beleg, dass opencode Pfade nicht per realpath auflöst und die Prüfung nötig ist.
     */
    @Test
    void gegenprobeOpencodeFolgtSymlinks() {
        volume.sh("ln -s /tmp/captain /w/sessions && ln -s /etc/passwd /w/passwd");
        String script = "import(\"/opt/captain/start.mjs\").then((m) => {"
                + " const fs = require(\"fs\"); const b = m.buildConfig(fs.readFileSync(m.SOURCE, \"utf8\"), process.env);"
                + " fs.mkdirSync(\"/run/captain\", { recursive: true }); fs.writeFileSync(m.TARGET, b.json);"
                + " const env = m.environment(process.env, m.enabledProviders(b.clean, process.env), { shared: true });"
                + " require(\"child_process\").spawn(m.COMMAND[0], m.COMMAND.slice(1), { stdio: \"inherit\", env })"
                + ".on(\"exit\", (c) => process.exit(c ?? 1)); })";
        Stack s = stack(HOST_PATH, ro(), List.of("--entrypoint", "node"), List.of("-e", script));
        assertThat(s.read("/shared/sessions/" + s.other.substring(s.other.lastIndexOf('/') + 1) + "/geheim.txt")).contains(SECRET);
        assertThat(s.read("/shared/passwd")).contains("root:x:0:0");
    }

    @Test
    void symlinkAtRuntimeStopsOpencode() {
        Stack s = stack(ro());
        assertThat(s.read("/shared/handbuch.txt")).contains("Stichwort");
        volume.sh("ln -s /tmp/captain /w/sessions");
        double deadline = Clock.monotonic() + 30;
        while (s.c.running() && Clock.monotonic() < deadline) {
            Clock.sleep(1);
        }
        assertThat(s.c.running()).as("opencode läuft trotz Symlink weiter").isFalse();
        String text = s.c.logs();
        assertThat(text).contains("FEHLER", "/shared/sessions: Symlink");
        // und startet damit auch nicht wieder
        Integration.docker("start", s.c.name);
        Clock.sleep(5);
        assertThat(s.c.running()).isFalse();
        assertThat(s.c.logs()).contains("opencode startet nicht");
    }

    @ParameterizedTest
    @ValueSource(strings = {"shared", "./shared", "/", "/etc", "/srv", "/srv/../etc", "/srv//x", "C:/shared",
            "/srv/a:b", "/opt/captain", "/opt/captain/sharedX", "/opt/captain/config", "/opt/captain/sessions/x", "/opt"})
    void invalidVariableFailsClosed(String value) {
        assertThat(startFails(value, ro(), List.of())).contains("CAPTAIN_SHARED_DIR");
    }

    @Test
    void writableOrMissingMountFailsClosed() {
        assertThat(startFails(HOST_PATH, volume.name + ":/shared", List.of())).contains("beschreibbar");
        assertThat(startFails(HOST_PATH, null, List.of())).contains("/shared fehlt");
    }

    /** Geteiltes Verzeichnis = Session-Verzeichnisse (z. B. CAPTAIN_HOME/sessions). */
    @Test
    void captainDataBelowSharedFailsClosed() {
        assertThat(startFails(HOST_PATH, ro(), List.of("-v", volume.name + ":/tmp/captain"))).contains("Captain-Daten");
    }

    /** CAPTAIN_SHARED_DIR darf auf einen Symlink zeigen: Docker löst die Quelle beim Anlegen auf (Pfade im Docker-Host). */
    @Test
    void mountSourceMayBeSymlink() {
        String tag = Integration.hex(8);
        String target = "/tmp/captain-ziel-" + tag;
        String link = "/tmp/captain-link-" + tag;
        Integration.docker("run", "--rm", "-v", "/tmp:/host-tmp", "--entrypoint", "sh", Integration.IMAGE, "-c",
                "mkdir -p /host-tmp/captain-ziel-" + tag + " && echo per-link > /host-tmp/captain-ziel-" + tag
                        + "/a.txt && ln -s " + target + " /host-tmp/captain-link-" + tag);
        try {
            Stack s = stack(link, link + ":/shared:ro", List.of(), List.of());
            assertThat(s.read("/shared/a.txt")).contains("per-link");
            assertThat(Integration.docker("exec", s.c.name, "stat", "-c", "%F", "/shared")).isEqualTo("directory");
        } finally {
            Integration.dockerQuiet("run", "--rm", "-v", "/tmp:/host-tmp", "--entrypoint", "sh", Integration.IMAGE, "-c",
                    "rm -rf /host-tmp/captain-ziel-" + tag + " /host-tmp/captain-link-" + tag);
        }
    }

    /** Bind-Mount unter dem geteilten Verzeichnis (z. B. sessions/&lt;id&gt;): anderes Dateisystem. */
    @Test
    void mountpointBelowSharedFailsClosed() {
        try (Volume other = new Volume()) {
            assertThat(startFails(HOST_PATH, ro(), List.of("-v", other.name + ":/shared/sub:ro"))).contains("/shared/sub: Mountpoint");
        }
    }

    @Test
    void scanLimitFailsClosed() {
        assertThat(startFails(HOST_PATH, ro(), List.of("-e", "CAPTAIN_SHARED_MAX_ENTRIES=3")))
                .contains("mehr als 3 Eintraege", "CAPTAIN_SHARED_MAX_ENTRIES");
    }

    /** CAPTAIN_SHARED_DIR=&lt;CAPTAIN_HOME&gt;/shared/… ist erlaubt (Container bekommt CAPTAIN_HOME=/opt/captain). */
    @Test
    void homeSharedSubdirAllowed() {
        Stack s = stack("/opt/captain/shared/infos", ro(), List.of(), List.of());
        assertThat(s.read("/shared/handbuch.txt")).contains("Stichwort");
    }
}
