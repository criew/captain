package com.ewerlin.captain.util;

/** Zeit- und Warte-Hilfen (Sekunden als ``double`` wie ``time.monotonic``). */
public final class Clock {

    private Clock() {
    }

    /** Monotone Zeit in Sekunden. */
    public static double monotonic() {
        return System.nanoTime() / 1e9;
    }

    /** Wanduhr in Millisekunden seit der Epoche. */
    public static long nowMs() {
        return System.currentTimeMillis();
    }

    /** Schläft ``seconds``; eine Unterbrechung beendet das Warten (Flag bleibt gesetzt). */
    public static void sleep(double seconds) {
        if (seconds <= 0) {
            return;
        }
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
