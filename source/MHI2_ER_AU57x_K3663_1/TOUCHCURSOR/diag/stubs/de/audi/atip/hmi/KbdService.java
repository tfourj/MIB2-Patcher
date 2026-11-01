package de.audi.atip.hmi;

/** Compile-only stub. The real interface lives in lsd.jxe. */
public interface KbdService {
    boolean setRecognizerMode(int mode, KbdGenericSettings settings);

    int getRecognizerMode();
}
