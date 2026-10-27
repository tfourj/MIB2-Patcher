package de.audi.app.terminalmode.osgi;

import java.util.Dictionary;

import org.osgi.framework.ServiceRegistration;

/** Compile-only stub. The real interface lives in lsd.jxe. */
public interface IServiceManager {
    ServiceRegistration registerService(Class type, Object service, Dictionary properties);

    void unregisterService(ServiceRegistration registration);
}
