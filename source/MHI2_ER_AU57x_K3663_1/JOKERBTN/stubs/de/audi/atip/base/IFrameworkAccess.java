package de.audi.atip.base;

import de.audi.atip.hmi.HMIService;
import de.audi.atip.hmi.IHMIServiceApp;
import de.audi.atip.log.LogChannel;
import de.audi.atip.sysapp.ISysApp;

/** Compile-only stub. The real interface lives in lsd.jxe. */
public interface IFrameworkAccess {
    IHMIServiceApp getHmiServiceApp();

    ISysApp getSysApp();

    LogChannel getLogChannel(String name);

    HMIService getHMIService();

    long getMonotonicTime();
}
