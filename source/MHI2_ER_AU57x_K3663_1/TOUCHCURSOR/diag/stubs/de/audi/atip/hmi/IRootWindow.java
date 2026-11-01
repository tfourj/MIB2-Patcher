package de.audi.atip.hmi;

import de.audi.atip.hmi.event.ATIPEventListener;
import de.audi.atip.hmi.view.Screen;

/** Compile-only stub. The real interface lives in lsd.jxe. */
public interface IRootWindow extends ATIPEventListener {
    Screen getCurrentScreen();
}
