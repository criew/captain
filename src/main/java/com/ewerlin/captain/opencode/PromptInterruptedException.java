package com.ewerlin.captain.opencode;

/** Ausführung wurde abgebrochen ({@code abort} oder serverseitig). */
public class PromptInterruptedException extends OpencodeException {

    public PromptInterruptedException(String message) {
        super(message);
    }
}
