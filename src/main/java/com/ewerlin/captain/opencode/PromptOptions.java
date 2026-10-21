package com.ewerlin.captain.opencode;

import java.util.List;
import java.util.function.Consumer;

/**
 * Optionen für {@link OpencodeApi#prompt}.
 *
 * @param directory   dient zum Auflösen relativer {@code files} (Ort ist an die Session gebunden)
 * @param model       {@code provider/modell}; wird vorher gesetzt, falls es abweicht
 * @param files       Anhänge (Container-Pfade); {@code null}/leer = keine
 * @param onDelta     bekommt jeweils den <b>gesamten</b> bisherigen Antworttext
 * @param onStatus    Statustext pro Tool-Aufruf
 * @param idleTimeout Sekunden ohne Event dieser Session, bevor abgebrochen wird
 */
public record PromptOptions(
        String directory,
        String model,
        String variant,
        List<String> files,
        Consumer<String> onDelta,
        Consumer<String> onStatus,
        double idleTimeout) {

    public static final double DEFAULT_IDLE_TIMEOUT = 600;

    public static PromptOptions in(String directory) {
        return new PromptOptions(directory, null, null, null, null, null, DEFAULT_IDLE_TIMEOUT);
    }

    public PromptOptions model(String model, String variant) {
        return new PromptOptions(directory, model, variant, files, onDelta, onStatus, idleTimeout);
    }

    public PromptOptions files(List<String> files) {
        return new PromptOptions(directory, model, variant, files, onDelta, onStatus, idleTimeout);
    }

    public PromptOptions onDelta(Consumer<String> onDelta) {
        return new PromptOptions(directory, model, variant, files, onDelta, onStatus, idleTimeout);
    }

    public PromptOptions onStatus(Consumer<String> onStatus) {
        return new PromptOptions(directory, model, variant, files, onDelta, onStatus, idleTimeout);
    }

    public PromptOptions idleTimeout(double seconds) {
        return new PromptOptions(directory, model, variant, files, onDelta, onStatus, seconds);
    }
}
