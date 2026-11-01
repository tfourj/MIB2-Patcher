package de.audi.atip.hmi.event;

/** Compile-only stub. The real class lives in lsd.jxe. */
public class TouchEvent extends ATIPEvent {
    public TouchEvent(ATIPEventListener listener, int id, int touchCode, int x, int y) {
        super(listener, id);
    }

    public final int getCode() {
        return 0;
    }

    public final int getX() {
        return 0;
    }

    public final int getY() {
        return 0;
    }

    public final int getFingerID() {
        return 0;
    }
}
