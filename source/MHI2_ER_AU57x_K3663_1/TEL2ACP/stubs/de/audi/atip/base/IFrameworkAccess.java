package de.audi.atip.base;

import org.osgi.framework.BundleContext;

import de.audi.atip.hmi.HMIService;
import de.audi.atip.hmi.IHMIServiceApp;
import de.audi.atip.hmi.KbdService;

/** Compile-only stub. The real interface lives in lsd.jxe. */
public interface IFrameworkAccess {
    HMIService getHMIService();

    KbdService getKbdService();

    IHMIServiceApp getHmiServiceApp();

    BundleContext getBundleCxt();
}
