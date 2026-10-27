package de.audi.tghu.fwhmi;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.event.JoystickEvent;
import de.audi.atip.hmi.event.KeyEvent;
import de.audi.atip.hmi.event.KeyListener;
import de.audi.atip.hmi.event.WheelButtonEvent;
import de.audi.atip.hmi.view.HMIView;

/** Compile-only stub. The real class lives in lsd.jxe. */
final class VirtualButton implements HMIView, KeyListener {
    protected VirtualButton(IFrameworkAccess framework, int modelId, int keyCode) {
    }

    public void keyPressed(KeyEvent event) {
    }

    public void keyReleased(KeyEvent event) {
    }

    public void keyTurned(WheelButtonEvent event) {
    }

    public void keyMoved(JoystickEvent event) {
    }
}
