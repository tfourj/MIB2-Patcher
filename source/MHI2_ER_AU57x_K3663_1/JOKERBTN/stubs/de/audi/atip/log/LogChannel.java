package de.audi.atip.log;

/** Compile-only stub. The real class lives in lsd.jxe. */
public abstract class LogChannel {
    public final boolean log(int level, int messageId) {
        return false;
    }

    public final boolean log(int level, int messageId, long value) {
        return false;
    }

    public final boolean log(int level, int messageId, Object value) {
        return false;
    }

    public final boolean log(int level, int messageId, Object arg, long value) {
        return false;
    }
}
