package de.audi.app.terminalmode;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.model.ButtonModel;
import de.audi.atip.hmi.model.DefaultButtonListener;
import de.audi.atip.hmi.modelaccess.ButtonModelApp;

/**
 * Listens to the steering wheel joker ("star") button for terminal mode
 * (Apple CarPlay / Android Auto). New class, not part of lsd.jxe.
 *
 * When the joker button is set to "skip forward", it presses terminal mode's
 * own HK_NEXT button. That button's listener only skips when CarPlay or
 * Android Auto has the audio focus, so this does nothing while media or the
 * tuner is playing; those apps handle the joker button themselves.
 */
public final class JokerBtnSkipListener extends DefaultButtonListener {

    // System model holding the joker button function (SystemModelBank, 267)
    private static final int JOKER_FUNCTION_CHOICE = 267;
    // JokerKeyMeaning.SKIP_FORWARD
    private static final int SKIP_FORWARD = 5;
    // ICoreTerminalModeModelBank.TERMINAL_HK_NEXT_BUTTON
    private static final int TERMINAL_HK_NEXT_BUTTON = 240002;

    private final IContext context;

    public JokerBtnSkipListener(IContext context) {
        this.context = context;
    }

    public void keyTyped(int modelId, int arg1, int arg2) {
        try {
            IFrameworkAccess framework = this.context.getFramework();
            if (framework.getSysApp().getChoiceModelApp(JOKER_FUNCTION_CHOICE).getValue() != SKIP_FORWARD) {
                return;
            }
            ButtonModelApp next = framework.getHmiServiceApp().getButtonModel(TERMINAL_HK_NEXT_BUTTON);
            if (next instanceof ButtonModel) {
                // A short press: the listener skips on release if the long-press timer has not fired
                ((ButtonModel) next).keyPressed(arg1, arg2);
                ((ButtonModel) next).keyReleased(arg1, arg2);
            }
        } catch (Throwable t) {
            // Never let the joker button break key handling
        }
    }
}
