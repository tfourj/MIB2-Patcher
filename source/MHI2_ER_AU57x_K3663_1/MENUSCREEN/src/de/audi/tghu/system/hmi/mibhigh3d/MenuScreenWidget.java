package de.audi.tghu.system.hmi.mibhigh3d;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.event.KeyEvent;
import de.audi.atip.hmi.event.WheelButtonEvent;
import de.audi.atip.hmi.view.HMIView;
import de.audi.tghu.hmi.view.LabelWidget;
import de.audi.tghu.hmi.view.ScreenWidget;
import de.audi.tghu.hmi.view.d4.BorderWidgetD4;

/**
 * The MENUSCREEN screen: a title, three buttons (Open GEM, Open REM, Restart
 * CarPlay) and a status line. New class, not part of lsd.jxe.
 *
 * Built like SystemScreenBag2.messageScreen (labels in the standard border on
 * a type 28 screen). The screen handles the MMI knob itself: turning moves
 * the selection, pressing runs the selected button. BACK reaches the screen
 * before the state machine, which has no BACK transition in the MENU state,
 * so the screen leaves for MENU itself (see MenuScreen.close).
 */
final class MenuScreenWidget extends ScreenWidget {

    // ScreenWidget type of SystemScreenBag2.messageScreen
    private static final int SCREEN_TYPE = 28;
    // de.audi.atip.hmi.event.KeyEvent key codes
    private static final int KEY_BACK = 15;
    // The MMI knob: press, and turns as WheelButtonEvents (the volume knob is 16)
    private static final int KEY_MENU_KNOB = 17;
    // de.audi.atip.hmi.event.WheelButtonEvent.LEFTTURN
    private static final int LEFT_TURN = 1;
    // Standard border images 8010-8045, as in SystemScreenBag2.messageScreen
    private static final int BORDER_FIRST_IMAGE = 8010;
    private static final int BORDER_IMAGE_COUNT = 36;
    // SystemScreenFactory colour sets: 8 is the MENU title, 9 the message screen
    private static final int TITLE_COLORS = 8;
    private static final int SCREEN_COLORS = 9;

    private static final int ACTION_GEM = 0;
    private static final int ACTION_REM = 1;
    private static final int ACTION_CARPLAY = 2;
    private static final String[] ACTION_TEXTS = {"Open GEM", "Open REM", "Restart CarPlay"};
    private static final String READY_TEXT = "Turn to choose, press to start";

    private MenuScreenLabel[] actionLabels;
    private MenuScreenLabel statusLabel;
    private int selected = ACTION_CARPLAY;

    private MenuScreenWidget() {
        super(SCREEN_TYPE);
    }

    static MenuScreenWidget create(SystemScreenFactory factory, int terminalId, int screenId) {
        MenuScreenWidget screen = new MenuScreenWidget();

        LabelWidget titleLabel = new LabelWidget(13, 16, 769, 60);
        titleLabel.setWidgetName("menuScreenTitle");
        titleLabel.setAlignment(2, 5);
        titleLabel.setColors(factory.getColorArray(TITLE_COLORS));
        titleLabel.setText(MenuScreen.getTitle());

        BorderWidgetD4 border = new BorderWidgetD4(0, 0, 800, 480);
        border.setWidgetName("border");
        border.setAlignment(13);
        int[] images = new int[BORDER_IMAGE_COUNT];
        for (int i = 0; i < images.length; i++) {
            images[i] = BORDER_FIRST_IMAGE + i;
        }
        border.setBitmaps(images);
        border.add(titleLabel);

        screen.actionLabels = new MenuScreenLabel[ACTION_TEXTS.length];
        for (int i = 0; i < ACTION_TEXTS.length; i++) {
            MenuScreenLabel action = new MenuScreenLabel(13, 100 + i * 70, 769, 60);
            action.setWidgetName("menuScreenAction" + (i + 1));
            action.setAlignment(1, 5);
            screen.actionLabels[i] = action;
            border.add(action);
        }

        screen.statusLabel = new MenuScreenLabel(13, 330, 769, 60);
        screen.statusLabel.setWidgetName("menuScreenStatus");
        screen.statusLabel.setAlignment(1, 5);
        screen.statusLabel.show(READY_TEXT);
        border.add(screen.statusLabel);
        screen.showSelection();

        screen.setID(screenId);
        screen.setScreenFactory(factory);
        screen.setFonts(factory.getFonts(0, terminalId));
        screen.setColors(factory.getColorArray(SCREEN_COLORS));
        screen.setUseForMainWizardPreview(false);
        screen.setMainWizardPreviewBehaviour(1);
        screen.setViews(new int[0], new HMIView[0][]);
        screen.add(border);
        MenuScreen.log("screen built for terminal " + terminalId);
        return screen;
    }

    public void keyPressed(KeyEvent e) {
        int key = e.getKeyCode();
        if (key == KEY_BACK) {
            e.consume();
            MenuScreen.close(getTerminalID(), "BACK");
            return;
        }
        if (key == KEY_MENU_KNOB) {
            e.consume();
            runSelected();
            return;
        }
        super.keyPressed(e);
    }

    public void keyReleased(KeyEvent e) {
        if (e.getKeyCode() == KEY_MENU_KNOB) {
            e.consume();
            return;
        }
        super.keyReleased(e);
    }

    public void keyTurned(WheelButtonEvent e) {
        if (e.getKeyCode() != KEY_MENU_KNOB) {
            super.keyTurned(e);
            return;
        }
        e.consume();
        int steps = Math.max(1, e.getClickCount());
        int count = ACTION_TEXTS.length;
        int next = this.selected + (e.getDirection() == LEFT_TURN ? -steps : steps);
        // Wrap around: past the last button comes the first, and back
        next = (next % count + count) % count;
        if (next != this.selected) {
            this.selected = next;
            this.statusLabel.show(READY_TEXT);
            showSelection();
        }
    }

    private void runSelected() {
        String status;
        try {
            IFrameworkAccess framework = MenuScreen.getFramework();
            int terminalId = getTerminalID();
            if (framework == null) {
                status = "Not ready, open the screen again from MENU";
            } else if (this.selected == ACTION_GEM) {
                status = MenuScreenActions.openGem(framework, terminalId);
            } else if (this.selected == ACTION_REM) {
                status = MenuScreenActions.openRedEngineering(framework, terminalId);
            } else {
                status = MenuScreenActions.restartCarPlay();
            }
        } catch (Throwable t) {
            MenuScreen.log("action failed", t);
            status = "Something went wrong, run Status and check the patch log";
        }
        this.statusLabel.show(status);
        repaint();
    }

    // The selected button is highlighted and marked, in case the skin has no highlight colour
    private void showSelection() {
        for (int i = 0; i < this.actionLabels.length; i++) {
            boolean on = i == this.selected;
            this.actionLabels[i].show((on ? "> " : "   ") + ACTION_TEXTS[i]);
            this.actionLabels[i].setHighlighted(on);
        }
        repaint();
    }

    // doCheckedRepaint needs the screen to be on the display (initContext is
    // set when it connects), so changes made while building it just wait
    private void repaint() {
        if (this.initContext != null) {
            doCheckedRepaint();
        }
    }
}
