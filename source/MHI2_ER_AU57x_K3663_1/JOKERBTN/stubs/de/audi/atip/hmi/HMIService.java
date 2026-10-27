package de.audi.atip.hmi;

import de.audi.atip.hmi.event.EventDispatcher;

/** Compile-only stub. The real interface lives in lsd.jxe. */
public interface HMIService {
    IRootWindow getRootWindow(int terminalId);

    EventDispatcher getEventDispatcher();
}
