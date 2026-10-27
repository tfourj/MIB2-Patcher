package org.osgi.framework;

/** Compile-only stub. The real interface lives in lsd.jxe. */
public interface BundleContext {
    ServiceReference getServiceReference(String clazz);

    Object getService(ServiceReference reference);

    boolean ungetService(ServiceReference reference);
}
