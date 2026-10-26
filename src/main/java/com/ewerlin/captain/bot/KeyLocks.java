package com.ewerlin.captain.bot;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Ein Lock pro Session-Key: Nachrichten derselben Session laufen seriell.
 *
 * <p>Ein Lock lebt nur, solange ihn jemand hält oder darauf wartet – so wachsen die Locks für
 * kurzlebige {@code th:*}-Keys nicht unbegrenzt.
 */
public final class KeyLocks {

    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        int users;
    }

    /** Gehaltener Lock; {@link #close()} gibt ihn frei. */
    public final class Held implements AutoCloseable {
        private final String key;
        private final Entry entry;

        private Held(String key, Entry entry) {
            this.key = key;
            this.entry = entry;
        }

        @Override
        public void close() {
            entry.lock.unlock();
            release(key, entry);
        }
    }

    private final Map<String, Entry> locks = new HashMap<>();

    /** Wartet auf den Lock des Keys ({@code try (var held = locks.lock(key)) {…}}). */
    public Held lock(String key) {
        Entry entry;
        synchronized (locks) {
            entry = locks.computeIfAbsent(key, k -> new Entry());
            entry.users++;
        }
        entry.lock.lock();
        return new Held(key, entry);
    }

    private void release(String key, Entry entry) {
        synchronized (locks) {
            if (--entry.users == 0) {
                locks.remove(key, entry);
            }
        }
    }

    /** Anzahl lebender Locks (für Tests). */
    int size() {
        synchronized (locks) {
            return locks.size();
        }
    }
}
