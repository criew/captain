package com.ewerlin.captain.config;

/** Konfiguration fehlt oder ist ungültig. */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }
}
