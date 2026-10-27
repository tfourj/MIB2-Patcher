package de.audi.atip.hmi;

/** Compile-only stub. The real interface lives in lsd.jxe. */
public interface HMIServiceSM {
    void showScreen(int terminalId, int subterminalId, int screenId, boolean lock, boolean reinit, int[] states,
            boolean flag, int[] partialPopups);
}
