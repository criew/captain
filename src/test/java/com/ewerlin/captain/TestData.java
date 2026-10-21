package com.ewerlin.captain;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import com.ewerlin.captain.util.Json;

import tools.jackson.databind.JsonNode;

/** Testdaten aus {@code src/test/resources/data} (gemeinsam mit den Node-Tests von infra/opencode). */
public final class TestData {

    /** Wurzel des Repositorys (Arbeitsverzeichnis der Gradle-Tests). */
    public static final Path ROOT = Path.of("").toAbsolutePath();

    private TestData() {
    }

    public static String text(String name) {
        try (InputStream in = TestData.class.getResourceAsStream("/data/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Testdatei fehlt: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode json(String name) {
        return Json.parse(text(name));
    }

    public static List<String> lines(String name) {
        return text(name).lines().toList();
    }
}
