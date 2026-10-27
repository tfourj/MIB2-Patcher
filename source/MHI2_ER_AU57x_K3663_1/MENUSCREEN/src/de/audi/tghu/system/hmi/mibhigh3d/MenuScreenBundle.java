package de.audi.tghu.system.hmi.mibhigh3d;

import java.io.InputStream;

import de.audi.atip.hmi.HMIBundle;
import de.audi.atip.hmi.view.Screen;

/**
 * The MENUSCREEN HMI bundle: hands out the MENUSCREEN screen. New class, not
 * part of lsd.jxe.
 *
 * HMIRegistry picks the bundle for a screen by screen ID / 10000, so bundle
 * ID n owns screens n * 10000 and up. MenuScreen picks n from 26 to 31. Images
 * and texts come from the System bundle's screen factory, which also gives
 * the screen the system fonts, colours and border images.
 */
final class MenuScreenBundle implements HMIBundle {

    private final SystemScreenFactory factory;
    private final String skin;
    private final int id;

    MenuScreenBundle(SystemScreenFactory factory, String skin, int id) {
        this.factory = factory;
        this.skin = skin;
        this.id = id;
    }

    public Screen getScreen(int screenId, int terminalId) {
        if (screenId != this.id * 10000) {
            return null;
        }
        try {
            return MenuScreenWidget.create(this.factory, terminalId, screenId);
        } catch (Throwable t) {
            MenuScreen.log("screen build failed", t);
            return null;
        }
    }

    public String getSkin() {
        return this.skin;
    }

    public int getId() {
        return this.id;
    }

    public InputStream getImageInputStream(int imageId) {
        return this.factory.getImageInputStream(imageId);
    }

    public InputStream getImageInputStream(int imageId, int terminalId) {
        return this.factory.getImageInputStream(imageId, terminalId);
    }

    public String getImagePath(int imageId, int terminalId) {
        return this.factory.getImagePath(imageId, terminalId);
    }

    public String getText(int textId) {
        return this.factory.getText(textId);
    }

    public Screen[] getPartialPopupStubs(int terminalId) {
        return new Screen[0];
    }

    public Screen getPartialPopup(int popupId, int terminalId) {
        return null;
    }
}
