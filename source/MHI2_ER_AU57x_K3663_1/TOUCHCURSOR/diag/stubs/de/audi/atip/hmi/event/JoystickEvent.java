package de.audi.atip.hmi.event;

/** Compile-only stub. The real class lives in lsd.jxe. */
public class JoystickEvent extends KeyEvent {
    public JoystickEvent(ATIPEventListener listener, int id, long when, int keyCode, int direction, int terminalId) {
        super(listener, id, when, keyCode, terminalId);
    }

    public final int getDirection() {
        return 0;
    }
}
