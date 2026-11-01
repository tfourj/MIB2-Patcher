package de.audi.atip.hmi;

import de.audi.atip.hmi.view.IGUIManager;

/** Compile-only stub. The real interface lives in lsd.jxe. */
public interface HMITerminal {
    KbdService getKbdService();

    IGUIManager getGUIManager();
}
