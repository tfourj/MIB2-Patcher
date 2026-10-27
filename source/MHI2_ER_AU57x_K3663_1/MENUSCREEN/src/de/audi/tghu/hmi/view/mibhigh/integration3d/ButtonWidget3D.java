package de.audi.tghu.hmi.view.mibhigh.integration3d;

import de.audi.atip.hmi.event.KeyEvent;
import de.audi.atip.hmi.modelaccess.ChoiceModelGUI;
import de.audi.atip.hmi.modelaccess.RangeModelGUI;
import de.audi.tghu.hmi.view.ButtonWidget;
import de.audi.tghu.hmi.view.RedrawContext;
import de.audi.tghu.hmi.view.ScreenWidget;
import de.audi.tghu.system.hmi.mibhigh3d.MenuScreen;

/**
 * Replacement for the firmware's 3D tile button (MENU and CAR menus).
 *
 * Same fields, methods and behaviour as the class in lsd.jxe
 * (MHI2_ER_AU57x_K3663_1), plus one hook: the MENU tile chosen in
 * /mnt/persist/menuscreen.conf (see MenuScreen) stays visible, shows its own
 * text and opens the MENUSCREEN screen instead of firing its state machine
 * event. Every other tile behaves exactly as before.
 */
public class ButtonWidget3D extends ButtonWidget {

    // de.audi.atip.hmi.event.KeyEvent.KEY_BACK
    private static final int KEY_BACK = 15;
    // Pressed bit in the state reported to the 3D engine
    private static final int STATE_PRESSED = 4096;

    private KeyEvent lastPressedEvent;
    private KeyEvent lastReleasedEvent;
    private boolean previewButton;
    // MENUSCREEN: the tile was pressed and the screen is not open yet
    private boolean menuScreenPending;

    public ButtonWidget3D() {
        super();
        this.previewButton = false;
    }

    public ButtonWidget3D(int x, int y) {
        super(x, y);
        this.previewButton = false;
    }

    public ButtonWidget3D(int x, int y, int width, int height) {
        this();
        setBounds(x, y, width, height);
    }

    public int getFunctionState() {
        if (isPressed()) {
            return this.widgetState | STATE_PRESSED;
        }
        return this.widgetState & ~STATE_PRESSED;
    }

    public void keyPressedCallback() {
        logChannel3DWidgets.log(10000000, 3572, (long) getFunctionID());
        if (this.lastPressedEvent != null) {
            this.lastPressedEvent.setConsumed(false);
            press(this.lastPressedEvent);
            if (this.lastPressedEvent.isConsumed() && this.lastPressedEvent.isRepaintNeeded()) {
                ScreenWidget screen = (ScreenWidget) this.initContext.getScreen();
                if (screen != null) {
                    screen.doCheckedRepaint();
                }
            }
            this.lastPressedEvent = null;
        } else {
            logChannel3DWidgets.log(10000000, 3573);
            if (this.menuScreenPending) {
                MenuScreen.log("press callback without a stored press");
            }
        }
    }

    public void keyReleasedCallback() {
        logChannel3DWidgets.log(10000000, 3574, (long) getFunctionID());
        if (this.lastReleasedEvent != null) {
            this.lastReleasedEvent.setConsumed(false);
            super.keyReleased(this.lastReleasedEvent);
            this.lastReleasedEvent = null;
        } else {
            logChannel3DWidgets.log(10000000, 3575);
        }
        openIfStillPending();
    }

    public void keyPressed(KeyEvent e, boolean wait3D) {
        logChannel3DWidgets.log(10000000, 3576, wait3D, (long) getFunctionID());
        if (e.getKeyCode() != KEY_BACK && isMenuScreenTile()) {
            this.menuScreenPending = true;
            MenuScreen.log("tile pressed, key " + e.getKeyCode() + ", waiting for 3D " + wait3D);
        }
        if (!wait3D || e.getKeyCode() == KEY_BACK) {
            press(e);
        } else {
            this.lastPressedEvent = e;
            e.consume(false);
        }
    }

    public void keyReleased(KeyEvent e, boolean wait3D) {
        logChannel3DWidgets.log(10000000, 3577, wait3D, (long) getFunctionID());
        if (!wait3D || e.getKeyCode() == KEY_BACK) {
            super.keyReleased(e);
        } else {
            this.lastReleasedEvent = e;
            e.consume(false);
        }
    }

    public void keyPressed(KeyEvent e) {
        press(e);
    }

    public void render(RedrawContext context) {
    }

    public int getMinValue() {
        if (this.model != null && this.model instanceof RangeModelGUI) {
            return ((RangeModelGUI) this.model).getMinimum();
        }
        return Integer.MIN_VALUE;
    }

    public int getMaxValue() {
        if (this.model != null && this.model instanceof RangeModelGUI) {
            return ((RangeModelGUI) this.model).getMaximum();
        }
        return Integer.MAX_VALUE;
    }

    public int getCurrentValue() {
        if (this.model != null) {
            if (this.model instanceof RangeModelGUI) {
                return ((RangeModelGUI) this.model).getValue();
            }
            if (this.model instanceof ChoiceModelGUI) {
                return ((ChoiceModelGUI) this.model).getValue();
            }
        }
        return -1;
    }

    public boolean isPreviewButton() {
        return this.previewButton;
    }

    public void setPreviewButton(boolean previewButton) {
        this.previewButton = previewButton;
    }

    KeyEvent getLastPressedEvent() {
        return this.lastPressedEvent;
    }

    KeyEvent getLastReleasedEvent() {
        return this.lastReleasedEvent;
    }

    // MENUSCREEN hook: keeps the chosen MENU tile visible
    public void setVisible(boolean visible) {
        super.setVisible(visible || isMenuScreenTile());
    }

    // MENUSCREEN hook: the chosen MENU tile shows its own text
    public String getText() {
        if (isMenuScreenTile()) {
            return MenuScreen.getTileText();
        }
        return super.getText();
    }

    /** A press, as the firmware handles it, except on the MENUSCREEN tile. */
    private void press(KeyEvent e) {
        if (e.getKeyCode() != KEY_BACK && isMenuScreenTile()) {
            e.consume();
            MenuScreen.log("tile press reached the hook");
            this.menuScreenPending = false;
            MenuScreen.open(this.terminal, this.initContext);
            return;
        }
        super.keyPressed(e);
    }

    // The 3D engine sends a press callback and then a release callback. If only
    // the release arrived, open the screen then, so a press is never lost.
    private void openIfStillPending() {
        if (this.menuScreenPending && isMenuScreenTile()) {
            this.menuScreenPending = false;
            MenuScreen.log("no press callback from the 3D engine, opening on release");
            MenuScreen.open(this.terminal, this.initContext);
        }
    }

    private boolean isMenuScreenTile() {
        try {
            return MenuScreen.isTile(getFunctionID(), getWidgetName());
        } catch (Throwable t) {
            // Never let the hook break the stock tiles
            return false;
        }
    }
}
