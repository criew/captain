package com.ewerlin.captain.bot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Schreibt Dateien atomar: Temp-Datei im selben Verzeichnis, fsync, dann ersetzen. */
final class AtomicFile {

    /** Ersetzt {@code tmp} → {@code target} (in Tests austauschbar, um Fehler zu simulieren). */
    @FunctionalInterface
    interface Mover {
        void move(Path tmp, Path target) throws IOException;
    }

    static final Mover REPLACE = (tmp, target) ->
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

    private AtomicFile() {
    }

    static void write(Path path, String content, String prefix, Mover mover) throws IOException {
        Path dir = path.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, prefix, ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buf = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            }
            mover.move(tmp, path);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}
