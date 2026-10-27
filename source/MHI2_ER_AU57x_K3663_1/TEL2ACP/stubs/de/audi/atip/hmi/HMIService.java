package de.audi.atip.hmi;

/** Compile-only stub. The real interface lives in lsd.jxe. */
public interface HMIService {
    int getCurrentScreenID();

    int getCurrentPopup();

    void fireSMEvent(int terminalId, int event);
}
