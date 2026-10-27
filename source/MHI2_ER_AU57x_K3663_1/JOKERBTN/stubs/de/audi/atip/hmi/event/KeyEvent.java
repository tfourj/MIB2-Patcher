package de.audi.atip.hmi.event;

/** Compile-only stub. The real class lives in lsd.jxe. */
public class KeyEvent extends ATIPEvent {
    public KeyEvent(ATIPEventListener listener, int id, long when, int keyCode, int terminalId, int keySource) {
        super(listener, id);
    }

    public final int getKeyCode() {
        return 0;
    }

    public final int getTerminalID() {
        return 0;
    }

    public final int getKeySource() {
        return 0;
    }
}
