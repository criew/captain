package com.ewerlin.captain.mattermost;

/** REST-Aufruf fehlgeschlagen. */
public class MattermostException extends RuntimeException {

    public MattermostException(String message) {
        super(message);
    }

    public MattermostException(String message, Throwable cause) {
        super(message, cause);
    }
}
