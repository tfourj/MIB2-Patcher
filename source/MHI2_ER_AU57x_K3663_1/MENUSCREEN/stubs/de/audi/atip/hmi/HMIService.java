package de.audi.atip.hmi;

import de.audi.atip.hmi.event.EventDispatcher;
import de.audi.atip.hmi.event.EventDispatcherAdmin;

/** Compile-only stub. The real interface lives in lsd.jxe. */
public interface HMIService {
    IRootWindow getRootWindow(int terminalId);

    EventDispatcher getEventDispatcher();

    EventDispatcherAdmin getEventDispatcherAdmin();

    int getCurrentScreenID();

    void fireSMEvent(int terminalId, int event);
}
