package com.ewerlin.captain.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Ungültige Konfiguration: kurze Meldung statt Stacktrace beim Start. */
public class ConfigFailureAnalyzer extends AbstractFailureAnalyzer<ConfigException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ConfigException cause) {
        return new FailureAnalysis("[Captain] " + cause.getMessage(),
                "Umgebungsvariablen bzw. .env prüfen (README: „Konfiguration“).", cause);
    }
}
