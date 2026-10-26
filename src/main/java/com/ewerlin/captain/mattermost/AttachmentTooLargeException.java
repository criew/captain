package com.ewerlin.captain.mattermost;

/** Anhang überschreitet die Größengrenze und wurde nicht (vollständig) geladen. */
public class AttachmentTooLargeException extends MattermostException {

    private final String name;
    private final long size;
    private final long limit;

    public AttachmentTooLargeException(String name, long size, long limit) {
        super("Anhang '" + name + "' ist " + size + " Bytes groß (Grenze " + limit + " Bytes)");
        this.name = name;
        this.size = size;
        this.limit = limit;
    }

    public String name() {
        return name;
    }

    public long size() {
        return size;
    }

    public long limit() {
        return limit;
    }
}
