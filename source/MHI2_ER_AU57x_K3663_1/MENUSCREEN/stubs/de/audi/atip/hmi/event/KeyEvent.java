package de.audi.atip.hmi.event;

/** Compile-only stub. The real class lives in lsd.jxe. */
public class KeyEvent extends ATIPEvent {
    public KeyEvent(ATIPEventListener listener, int id, long when, int keyCode, int terminalId, int keySource) {
        super(listener, id);
    }

    public final int getKeyCode() {
        return 0;
    }

    public final void consume() {
    }

    public final void consume(boolean repaintNeeded) {
    }

    public final void setConsumed(boolean consumed) {
    }

    public final boolean isConsumed() {
        return false;
    }

    public final boolean isRepaintNeeded() {
        return false;
    }
}
