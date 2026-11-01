package de.audi.app.terminalmode.dsi;

import org.dsi.ifc.base.DSIBase;
import org.dsi.ifc.base.DSIListener;

/** Compile-only stub. The real class lives in lsd.jxe. */
public abstract class NotificationProxy {
    public NotificationProxy() {
    }

    protected abstract DSIBase getDSI();

    protected abstract DSIListener getDSIListener();

    protected void newDSIAvailable() {
    }

    public void setNotification(int[] attributes, DSIListener listener) {
    }
}
