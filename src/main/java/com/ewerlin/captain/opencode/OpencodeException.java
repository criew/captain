package com.ewerlin.captain.opencode;

/** Aufruf an opencode fehlgeschlagen oder Ausführung mit Fehler beendet. */
public class OpencodeException extends RuntimeException {

    public OpencodeException(String message) {
        super(message);
    }

    public OpencodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
