package de.audi.tghu.hmi.view;

import de.audi.atip.hmi.event.KeyEvent;
import de.audi.atip.hmi.modelaccess.HMIModelGUI;
import de.audi.atip.hmi.view.HMIView;
import fi.hybrid.hwg.Font;

/** Compile-only stub. The real class lives in lsd.jxe. */
public abstract class AbstractWidget implements IWidgetLogChannel, HMIView {
    protected HMIModelGUI model;
    protected int widgetState;
    protected HMITerminalImpl terminal;
    protected InitializationContext initContext;

    public final String getWidgetName() {
        return null;
    }

    public void setWidgetName(String name) {
    }

    public void setBounds(int x, int y, int width, int height) {
    }

    public void setVisible(boolean visible) {
    }

    public void setCompositeDirty(int index) {
    }

    public void setFonts(Font[] fonts) {
    }

    public void setColors(int[][] colors) {
    }

    public void setBitmaps(int[] bitmaps) {
    }

    public void add(AbstractWidget widget) {
    }

    public void keyPressed(KeyEvent e) {
    }

    public void keyReleased(KeyEvent e) {
    }

    protected void fireSMEvent(int terminalId, int event) {
    }
}
