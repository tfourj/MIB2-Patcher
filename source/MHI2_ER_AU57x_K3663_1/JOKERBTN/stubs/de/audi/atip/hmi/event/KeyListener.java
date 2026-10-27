package de.audi.atip.hmi.event;

/** Compile-only stub. The real interface lives in lsd.jxe. */
public interface KeyListener {
    void keyPressed(KeyEvent event);

    void keyReleased(KeyEvent event);

    void keyTurned(WheelButtonEvent event);

    void keyMoved(JoystickEvent event);
}
