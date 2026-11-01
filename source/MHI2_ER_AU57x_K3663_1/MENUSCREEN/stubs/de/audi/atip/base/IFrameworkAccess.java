package de.audi.atip.base;

import org.osgi.framework.BundleContext;

import de.audi.atip.hmi.HMIService;
import de.audi.atip.start.IStartupManager;
import de.audi.atip.statemachine.SMInterpreter;

/** Compile-only stub. The real interface lives in lsd.jxe. */
public interface IFrameworkAccess {
    BundleContext getBundleCxt();

    HMIService getHMIService();

    IStartupManager getStartupMgr();

    long getMonotonicTime();

    SMInterpreter getSMInterpreter();
}
