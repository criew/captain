package com.ewerlin.captain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.ewerlin.captain.config.ConfigException;

/** Einstieg: Spring Boot ohne Webserver; der Bot läuft als {@link BotLifecycle}. */
@SpringBootApplication
public class CaptainApplication {

    public static void main(String[] args) {
        try {
            SpringApplication.run(CaptainApplication.class, args);
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof ConfigException) {
                    System.exit(2); // Meldung kommt vom ConfigFailureAnalyzer
                }
            }
            throw e;
        }
    }
}
