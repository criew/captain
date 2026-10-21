package com.ewerlin.captain.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;

import com.ewerlin.captain.bot.SessionKeys;
import com.ewerlin.captain.opencode.OpencodeClient;
import com.ewerlin.captain.opencode.PromptOptions;
import com.ewerlin.captain.opencode.Rule;
import com.ewerlin.captain.util.Json;

import tools.jackson.databind.JsonNode;

/** Ein eigener opencode-Container aus dem Image, mit Fake-LLM als Modell. */
final class OpencodeContainer implements AutoCloseable {

    final String name;
    final String url;
    final String password;
    final FakeLlm llm;
    final OpencodeClient oc;

    private OpencodeContainer(String name, String url, String password, FakeLlm llm) {
        this.name = name;
        this.url = url;
        this.password = password;
        this.llm = llm;
        this.oc = new OpencodeClient(url, password);
    }

    /** Startet {@code docker run -d …} und wartet, bis opencode antwortet (sonst Testfehler mit Log). */
    static OpencodeContainer start(String name, String password, FakeLlm llm, List<String> runArgs) {
        List<String> args = new ArrayList<>(List.of("run", "-d", "--name", name, "-p", "127.0.0.1::4096"));
        args.addAll(runArgs);
        Integration.docker(args.toArray(String[]::new));
        String url = "http://127.0.0.1:" + Integration.port(name);
        if (!Integration.waitForOpencode(url, password, 90, () -> running(name))) {
            String logs = Integration.logs(name);
            Integration.dockerQuiet("rm", "-f", name);
            fail("opencode startet nicht:\n" + logs.substring(Math.max(0, logs.length() - 3000)));
        }
        return new OpencodeContainer(name, url, password, llm);
    }

    static boolean running(String name) {
        return Integration.dockerQuiet("inspect", "-f", "{{.State.Running}}", name).equals("true");
    }

    boolean running() {
        return running(name);
    }

    /** Ergebnis eines Prompts: Antwort, angebotene Tools, Tool-Teile, Verzeichnis. */
    record Asked(String answer, List<String> offered, List<JsonNode> parts, String directory) {
    }

    /** Neue Session wie beim Bot (eigene ID, Verzeichnis vorher, Regeln), ein Prompt. */
    Asked ask(String text, List<Rule> permissions, double idle, java.util.function.Function<String, List<Rule>> rules) {
        String sid = SessionKeys.newSessionId();
        String directory = "/tmp/captain/" + sid;
        Integration.docker("exec", name, "mkdir", "-p", directory);
        List<Rule> r = permissions != null ? permissions : rules.apply(directory);
        oc.createSession(directory, "test", r, sid);
        int start = llm.size();
        String answer = oc.prompt(sid, text, PromptOptions.in(directory).model("llm/fake", null).idleTimeout(idle));
        List<String> offered = llm.offeredSince(start);
        List<JsonNode> parts = new ArrayList<>();
        for (JsonNode m : Json.list(oc.request("GET", "/api/session/" + sid + "/message", sid))) {
            if (Json.str(m, "type").equals("assistant")) {
                for (JsonNode p : Json.list(m.get("content"))) {
                    if (Json.str(p, "type").equals("tool")) {
                        parts.add(p);
                    }
                }
            }
        }
        assertThat(Json.list(oc.request("GET", "/api/session/" + sid + "/permission", sid))).isEmpty();
        return new Asked(answer, offered, parts, directory);
    }

    String env() {
        return Integration.opencodeEnv(name);
    }

    String logs() {
        return Integration.logs(name);
    }

    boolean marker(String file) {
        return Integration.dockerRun(false, null, 30, "exec", name, "test", "-e", "/tmp/" + file).code() == 0;
    }

    @Override
    public void close() {
        oc.close();
        Integration.dockerQuiet("rm", "-f", name);
    }
}
