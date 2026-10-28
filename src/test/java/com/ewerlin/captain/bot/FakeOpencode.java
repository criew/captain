package com.ewerlin.captain.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.ewerlin.captain.opencode.ModelInfo;
import com.ewerlin.captain.opencode.OpencodeApi;
import com.ewerlin.captain.opencode.OpencodeException;
import com.ewerlin.captain.opencode.PromptOptions;
import com.ewerlin.captain.opencode.Rule;

/** Fake-opencode: Prompt-Verhalten per {@link #behaviour} (je Aufruf eine Funktion) steuerbar. */
class FakeOpencode implements OpencodeApi {

    /** Ein Prompt-Aufruf. */
    record Call(String sid, String text, PromptOptions options) {
        void delta(String text) {
            options.onDelta().accept(text);
        }

        void status(String text) {
            options.onStatus().accept(text);
        }
    }

    record Created(String sid, String directory, String title, List<Rule> permissions, String sessionId, String agent) {
    }

    final List<Created> created = new ArrayList<>();
    final Map<String, Map<String, String>> instructions = new HashMap<>();
    volatile OpencodeException instructionsError;
    final List<Call> prompts = new ArrayList<>();
    final List<Function<Call, String>> behaviour = new ArrayList<>();
    final List<String> aborted = new ArrayList<>();
    final List<String> deleted = new ArrayList<>();
    volatile RuntimeException deleteError;
    final List<String> instructionCalls = new ArrayList<>();
    List<ModelInfo> models = List.of(
            new ModelInfo("llm/modell-a", "g", List.of()),
            new ModelInfo("opencode/x", "x", List.of("low", "high")));

    @Override
    public synchronized String createSession(String directory, String title, List<Rule> permissions, String sessionId,
            String agent) {
        assertThat(Files.isDirectory(Path.of(directory))).as("Verzeichnis muss vor der Session existieren").isTrue();
        String sid = sessionId != null ? sessionId : "ses_" + (created.size() + 1);
        created.add(new Created(sid, directory, title, permissions, sessionId, agent));
        return sid;
    }

    @Override
    public void setInstructions(String sessionId, Map<String, String> entries) {
        synchronized (this) {
            instructionCalls.add(sessionId);
        }
        OpencodeException error = instructionsError;
        if (error != null) {
            throw error;
        }
        synchronized (this) {
            instructions.put(sessionId, new LinkedHashMap<>(entries));
        }
    }

    @Override
    public boolean sessionExists(String sessionId) {
        return true;
    }

    @Override
    public synchronized void abort(String sessionId) {
        aborted.add(sessionId);
    }

    @Override
    public void deleteSession(String sessionId) {
        RuntimeException error = deleteError;
        if (error != null) {
            throw error;
        }
        synchronized (this) {
            deleted.add(sessionId);
        }
    }

    @Override
    public List<ModelInfo> listModels(String directory) {
        return models;
    }

    @Override
    public String prompt(String sessionId, String text, PromptOptions options) {
        Call call = new Call(sessionId, text, options);
        Function<Call, String> next = null;
        synchronized (this) {
            prompts.add(call);
            if (!behaviour.isEmpty()) {
                next = behaviour.removeFirst();
            }
        }
        assertThat(Files.isDirectory(Path.of(options.directory()))).as("Verzeichnis muss vor dem Prompt existieren").isTrue();
        if (next != null) {
            return next.apply(call);
        }
        options.onDelta().accept("Antwort");
        return "Antwort auf: " + text;
    }

    synchronized List<Call> prompts() {
        return List.copyOf(prompts);
    }

    synchronized List<String> texts() {
        return prompts.stream().map(Call::text).toList();
    }

    synchronized List<String> aborted() {
        return List.copyOf(aborted);
    }

    synchronized List<String> deleted() {
        return List.copyOf(deleted);
    }
}
