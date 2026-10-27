package de.audi.app.terminalmode;

import de.audi.app.terminalmode.osgi.IServiceManager;
import de.audi.atip.base.IFrameworkAccess;

/** Compile-only stub. The real interface lives in lsd.jxe. */
public interface IContext {
    ITerminalLogger getLogger();

    IServiceManager getServiceManager();

    IFrameworkAccess getFramework();
}
