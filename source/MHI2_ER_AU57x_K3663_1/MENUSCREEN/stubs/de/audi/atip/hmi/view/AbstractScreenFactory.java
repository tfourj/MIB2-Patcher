package de.audi.atip.hmi.view;

import java.io.InputStream;

/** Compile-only stub. The real class lives in lsd.jxe. */
public abstract class AbstractScreenFactory {
    public String getText(int textId) {
        return null;
    }

    public InputStream getImageInputStream(int imageId) {
        return null;
    }

    public InputStream getImageInputStream(int imageId, int terminalId) {
        return null;
    }

    public String getImagePath(int imageId, int terminalId) {
        return null;
    }
}
