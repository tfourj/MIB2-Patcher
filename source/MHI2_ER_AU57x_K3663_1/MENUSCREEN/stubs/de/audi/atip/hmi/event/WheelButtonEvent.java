package de.audi.atip.hmi.event;

/** Compile-only stub. The real class lives in lsd.jxe. */
public class WheelButtonEvent extends KeyEvent {
    public WheelButtonEvent(ATIPEventListener listener, int id, long when, int keyCode, int terminalId, int keySource,
            int steps) {
        super(listener, id, when, keyCode, terminalId, keySource);
    }

    public final int getDirection() {
        return 0;
    }

    public final int getClickCount() {
        return 0;
    }
}
