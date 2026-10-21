package com.ewerlin.captain.util;

/** Rücksetzbares Ereignis wie ``threading.Event``: setzen, löschen, mit Zeitgrenze warten. */
public class Signal {

    private boolean set;

    public synchronized void set() {
        set = true;
        notifyAll();
    }

    public synchronized void clear() {
        set = false;
    }

    public synchronized boolean isSet() {
        return set;
    }

    /** Wartet höchstens ``seconds`` (negativ = unbegrenzt); liefert den Zustand danach. */
    public synchronized boolean await(double seconds) {
        long deadline = seconds < 0 ? Long.MAX_VALUE : System.nanoTime() + (long) (seconds * 1e9);
        while (!set) {
            long left = deadline - System.nanoTime();
            if (seconds >= 0 && left <= 0) {
                return false;
            }
            try {
                if (seconds < 0) {
                    wait();
                } else {
                    wait(Math.max(1, left / 1_000_000), 0);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return set;
            }
        }
        return true;
    }

    /** Wartet unbegrenzt. */
    public boolean await() {
        return await(-1);
    }
}
