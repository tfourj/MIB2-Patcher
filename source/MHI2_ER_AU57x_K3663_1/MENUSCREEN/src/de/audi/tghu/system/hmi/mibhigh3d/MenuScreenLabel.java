package de.audi.tghu.system.hmi.mibhigh3d;

import de.audi.tghu.hmi.view.LabelWidget;

/**
 * A label whose text can change while it is on screen. New class, not part of
 * lsd.jxe.
 *
 * LabelWidget.setText only stores the text. A label without a text model
 * formats it in updateText(), which connected() runs when the screen is
 * shown, so text set before that just works. Later changes need updateText()
 * again, which is protected.
 */
final class MenuScreenLabel extends LabelWidget {

    MenuScreenLabel(int x, int y, int width, int height) {
        super(x, y, width, height);
    }

    /** Sets the text, and redraws it if the label is already on screen. */
    void show(String text) {
        setText(text);
        if (this.initContext != null) {
            updateText();
            setCompositeDirty(0);
        }
    }
}
