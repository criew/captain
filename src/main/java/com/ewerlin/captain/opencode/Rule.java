package com.ewerlin.captain.opencode;

/** Berechtigungsregel von opencode v2: ``{action, resource, effect}`` (letzte passende gewinnt). */
public record Rule(String action, String resource, String effect) {

    public static Rule allow(String action, String resource) {
        return new Rule(action, resource, "allow");
    }

    public static Rule deny(String action, String resource) {
        return new Rule(action, resource, "deny");
    }
}
