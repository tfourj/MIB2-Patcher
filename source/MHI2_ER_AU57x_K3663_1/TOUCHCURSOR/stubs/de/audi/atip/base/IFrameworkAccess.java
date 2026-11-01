package de.audi.atip.base;

import de.audi.atip.hmi.HMIService;
import de.audi.atip.hmi.IHMITerminalRegistry;

/** Compile-only stub. The real interface lives in lsd.jxe. */
public interface IFrameworkAccess {
    HMIService getHMIService();

    IHMITerminalRegistry getHMITerminalRegistry();
}
