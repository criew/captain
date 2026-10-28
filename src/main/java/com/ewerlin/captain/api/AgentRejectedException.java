package com.ewerlin.captain.api;

/** Agent-Wahl einer REST-Unterhaltung abgelehnt; {@code status} ist der HTTP-Status (400/409). */
public final class AgentRejectedException extends RuntimeException {

    private final int status;

    public AgentRejectedException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
