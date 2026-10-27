package org.osgi.framework;

import java.util.Dictionary;

/** Compile-only stub. The real interface lives in lsd.jxe. */
public interface BundleContext {
    ServiceRegistration registerService(String clazz, Object service, Dictionary properties);

    ServiceReference[] getServiceReferences(String clazz, String filter);

    Object getService(ServiceReference reference);

    boolean ungetService(ServiceReference reference);
}
