package de.audi.atip.hmi;

import java.io.InputStream;

import de.audi.atip.hmi.view.Screen;

/** Compile-only stub. The real interface lives in lsd.jxe. */
public interface HMIBundle {
    Screen getScreen(int screenId, int terminalId);

    String getSkin();

    int getId();

    InputStream getImageInputStream(int imageId);

    InputStream getImageInputStream(int imageId, int terminalId);

    String getImagePath(int imageId, int terminalId);

    String getText(int textId);

    Screen[] getPartialPopupStubs(int terminalId);

    Screen getPartialPopup(int popupId, int terminalId);
}
