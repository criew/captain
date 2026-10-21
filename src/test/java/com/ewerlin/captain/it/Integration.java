package com.ewerlin.captain.it;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;

import com.ewerlin.captain.TestData;

/** Gemeinsame Helfer der Integrationstests: env-Dateien, Docker-CLI, Warten auf opencode. */
public final class Integration {

    /** Image des Betriebs ({@code compose.deploy.yml}); per {@code CAPTAIN_OC_IMAGE} überschreibbar. */
    public static final String IMAGE = env("CAPTAIN_OC_IMAGE", "captain/opencode:2.1.2");
    public static final Path TEMPLATE = TestData.ROOT.resolve("infra/opencode/config/opencode.jsonc");
    public static final Path AGENTS = TestData.ROOT.resolve("infra/opencode/config/AGENTS.md");

    private Integration() {
    }

    public static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** {@code KEY=VALUE}-Datei (Kommentare, Anführungszeichen wie im Python-Original). */
    public static Map<String, String> readEnv(Path path) {
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                int eq = line.indexOf('=');
                if (line.isEmpty() || line.startsWith("#") || eq < 0) {
                    continue;
                }
                String value = line.substring(eq + 1).strip();
                value = value.replaceAll("^[\"']+|[\"']+$", "");
                values.put(line.substring(0, eq).strip(), value);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return values;
    }

    /** env-Datei laden oder den Test überspringen. */
    public static Map<String, String> envOrSkip(String variable, String fallback, String what) {
        Path path = Path.of(env(variable, fallback));
        Assumptions.assumeTrue(Files.exists(path), what + " fehlt: " + path);
        return readEnv(path);
    }

    /** Ergebnis eines Prozesses. */
    public record Result(int code, String out, String err) {
        public String all() {
            return out + err;
        }
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    /**
     * Argument für die Windows-Kommandozeile maskieren (Regeln von {@code CommandLineToArgvW}): Java
     * setzt dort zwar Anführungszeichen um Argumente mit Leerzeichen, maskiert aber keine
     * Anführungszeichen im Argument – {@code sh -c '…"…"…'} oder JSON-Werte kämen zerstückelt an.
     * Ein so vorbereitetes Argument übernimmt Java unverändert.
     */
    static String windowsArg(String arg) {
        if (!WINDOWS || arg.indexOf('"') < 0) {
            return arg;
        }
        StringBuilder sb = new StringBuilder("\"");
        int backslashes = 0;
        for (char c : arg.toCharArray()) {
            if (c == '\\') {
                backslashes++;
            } else if (c == '"') {
                sb.append("\\".repeat(backslashes * 2 + 1)).append('"');
                backslashes = 0;
            } else {
                sb.append("\\".repeat(backslashes)).append(c);
                backslashes = 0;
            }
        }
        return sb.append("\\".repeat(backslashes * 2)).append('"').toString();
    }

    /** {@code docker …} ausführen (ohne Git-Bash-Pfadumschreibung), optional mit stdin. */
    public static Result run(List<String> command, String input, double timeout) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command.stream().map(Integration::windowsArg).toList());
            pb.environment().put("MSYS_NO_PATHCONV", "1");
            Process p = pb.start();
            CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> read(p.getInputStream()));
            CompletableFuture<byte[]> err = CompletableFuture.supplyAsync(() -> read(p.getErrorStream()));
            try (OutputStream in = p.getOutputStream()) {
                if (input != null) {
                    in.write(input.getBytes(StandardCharsets.UTF_8));
                }
            }
            if (!p.waitFor((long) (timeout * 1000), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("Zeitüberschreitung: " + command);
            }
            return new Result(p.exitValue(), new String(out.join(), StandardCharsets.UTF_8),
                    new String(err.join(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static byte[] read(java.io.InputStream in) {
        try {
            return in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    public static Result dockerRun(boolean check, String input, double timeout, String... args) {
        List<String> cmd = new ArrayList<>(List.of("docker"));
        cmd.addAll(List.of(args));
        Result r = run(cmd, input, timeout);
        if (check && r.code() != 0) {
            throw new IllegalStateException("docker " + String.join(" ", args) + " → " + r.code() + "\n" + r.err());
        }
        return r;
    }

    /** {@code docker …}, Fehler → Ausnahme; liefert stdout ohne Leerraum an den Enden. */
    public static String docker(String... args) {
        return dockerRun(true, null, 300, args).out().strip();
    }

    /** {@code docker …}, Fehler egal. */
    public static String dockerQuiet(String... args) {
        return dockerRun(false, null, 300, args).out().strip();
    }

    public static String logs(String name) {
        return dockerRun(false, null, 60, "logs", name).all();
    }

    /** Überspringt, wenn Docker nicht läuft oder das Image fehlt. */
    public static void requireImage() {
        Result r;
        try {
            r = dockerRun(false, null, 60, "image", "inspect", IMAGE);
        } catch (IllegalStateException e) {
            r = new Result(1, "", e.getMessage());
        }
        Assumptions.assumeTrue(r.code() == 0, "Image " + IMAGE + " fehlt (docker compose -f compose.deploy.yml build)");
    }

    /** Port des Containers für 4096 auf dem Host. */
    public static String port(String name) {
        String line = docker("port", name, "4096").lines().findFirst().orElseThrow();
        return line.substring(line.lastIndexOf(':') + 1);
    }

    /** Wartet, bis {@code /api/info} mit Basic-Auth antwortet; {@code false} nach {@code seconds}. */
    public static boolean waitForOpencode(String url, String password, double seconds, java.util.function.BooleanSupplier alive) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        String auth = "Basic " + Base64.getEncoder().encodeToString(("opencode:" + password).getBytes(StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + (long) (seconds * 1e9);
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<Void> resp = http.send(HttpRequest.newBuilder(URI.create(url + "/api/info"))
                        .timeout(Duration.ofSeconds(3)).header("Authorization", auth).build(),
                        HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() == 200) {
                    return true;
                }
            } catch (IOException e) {
                // startet noch
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (alive != null && !alive.getAsBoolean()) {
                return false;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Umgebung des opencode-Prozesses (nicht des Startskripts) im Container. */
    public static String opencodeEnv(String name) {
        String script = "for p in /proc/[0-9]*; do c=$(tr \"\\000\" \" \" < $p/cmdline 2>/dev/null); "
                + "case \"$c\" in node*) ;; *\" serve \"*) tr \"\\000\" \"\\n\" < $p/environ; exit 0;; esac; done; exit 1";
        return docker("exec", name, "sh", "-c", script);
    }

    /** Admin-Config-Verzeichnis (Vorlage + AGENTS.md) unter {@code tmp}. */
    public static Path adminConf(Path tmp, String configText, Map<String, String> files) {
        try {
            Path conf = Files.createDirectories(tmp.resolve("config-" + java.util.UUID.randomUUID().toString().substring(0, 6)));
            Files.writeString(conf.resolve("opencode.jsonc"), configText.replace("\r\n", "\n"), StandardCharsets.UTF_8);
            Files.copy(AGENTS, conf.resolve("AGENTS.md"));
            for (Map.Entry<String, String> f : files.entrySet()) {
                Path target = conf.resolve(f.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, f.getValue().replace("\r\n", "\n"), StandardCharsets.UTF_8);
            }
            return conf;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String template() {
        try {
            return Files.readString(TEMPLATE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Pfad für {@code docker -v} (Windows: Vorwärts-Schrägstriche). */
    public static String mountPath(Path path) {
        return path.toAbsolutePath().toString().replace("\\", "/");
    }

    public static String hex(int n) {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, n);
    }
}
