package de.audi.tghu.fwhmi;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.event.JoystickEvent;
import de.audi.atip.hmi.event.KeyEvent;
import de.audi.atip.hmi.event.KeyListener;
import de.audi.atip.hmi.event.WheelButtonEvent;
import de.audi.atip.hmi.modelaccess.ChoiceModelApp;
import de.audi.atip.hmi.view.HMIView;
import de.audi.atip.hmi.view.VirtualGUI;
import de.audi.atip.log.LogChannel;
import de.audi.atip.sysapp.SysConst;
import de.audi.atip.timer.Timer;
import de.audi.atip.timer.TimerListener;

/**
 * Replacement for de.audi.tghu.fwhmi.SDSManagerGUI from lsd.jxe
 * (MHI2_ER_AU57x_K3663_1, MU1425).
 *
 * Rebuilt 1:1 from the original bytecode. This hard-key listener turns the
 * steering wheel voice (PTT) button into the voice control buttons
 * 140001-140003. The changes:
 * - the constructor starts JokerBtnConfig, which reads the button settings
 *   and remaps the joker and INFO buttons
 * - voice button presses go through JokerBtnVoiceKey first, which turns a short
 *   press into the chosen function and leaves long presses stock
 *
 * Loaded ahead of lsd.jxe with -Xbootclasspath/p:, so fields and method
 * signatures must stay identical to the original class.
 *
 * The boot guard is confirmed by the patched AbstractTerminalModeHMIApplication,
 * which starts later than this class, so a failure here also trips the guard.
 */
final class SDSManagerGUI implements VirtualGUI, KeyListener, TimerListener {

    private static final int SDS_PTT_BUTTON_ID = 140001;
    private static final int SDS_PTT_BUTTON_ID_LONG_PRESSED = 140002;
    private static final int SDS_PTT_BUTTON_ID_DOUBLE_PRESSED = 140003;

    // de.audi.atip.hmi.event.KeyEvent key codes
    private static final int KEY_PTT = 18;
    private static final int KEY_PTT_LONGPRESSED = 23;
    private static final int KEY_PTT_DOUBLEPRESSED = 28;

    // System models (de.audi.atip.sysapp.SystemModelBank)
    private static final int SDS_STATUS_CHOICE = 223;
    private static final int STATE_SDS_MANAGER_CHOICE = 246;

    // SDS manager app states in STATE_SDS_MANAGER_CHOICE, and the status values they set.
    // The values are from the original code; the names are best guesses.
    private static final int SDS_APP_DEFINED = 0;
    private static final int SDS_APP_ACTIVATED = 1;
    private static final int SDS_APP_STARTING = 1024;
    private static final int SDS_STATUS_IDLE = 0;
    private static final int SDS_STATUS_WAITING = 1;
    private static final int SDS_STATUS_STARTING = 5;

    // SysConst index of the PTT timer delay, and the delay used when it is 0
    private static final int SYSCONST_PTT_TIMER = 209;
    private static final long PTT_TIMER_DEFAULT_MS = 3000;
    private static final int PTT_TIMER_GROUP = 5;

    // Original log channel name, level and message ids
    private static final String LOG_CHANNEL = "Fw.KeyEvent";
    private static final int LOG_LEVEL = 10000000;
    private static final int LOG_KEY_TURNED = 580;
    private static final int LOG_KEY_TURNED_OTHER = 581;
    private static final int LOG_KEY_PRESSED = 582;
    private static final int LOG_KEY_PRESSED_OTHER = 583;
    private static final int LOG_SDS_ACTIVATED = 584;
    private static final int LOG_SDS_STATE_UNKNOWN = 585;
    private static final int LOG_PTT_TIMER_START = 586;
    private static final int LOG_KEY_RELEASED = 587;
    private static final int LOG_KEY_RELEASED_OTHER = 588;
    private static final int LOG_PTT_TIMER_FIRED = 589;

    private final LogChannel lcATIPEvent;
    private final LogChannel lcHMIService;
    private final HMIView[] views;
    private final VirtualButton virtualButtonPTT;
    private final VirtualButton virtualButtonPTTLongPressed;
    private final VirtualButton virtualButtonPTTDoublePressed;
    private final Timer pttTimer;
    private final ChoiceModelApp sdsStatusChoice;
    private final ChoiceModelApp stateSDSManagerChoice;
    private final JokerBtnVoiceKey voiceKey;

    public SDSManagerGUI(IFrameworkAccess framework) {
        this.views = new HMIView[3];
        this.lcATIPEvent = framework.getLogChannel(LOG_CHANNEL);
        this.lcHMIService = framework.getLogChannel(LOG_CHANNEL);
        this.sdsStatusChoice = framework.getSysApp().getChoiceModelApp(SDS_STATUS_CHOICE);
        this.stateSDSManagerChoice = framework.getSysApp().getChoiceModelApp(STATE_SDS_MANAGER_CHOICE);
        int delay = SysConst.getSysConst(SYSCONST_PTT_TIMER);
        this.pttTimer = new Timer("pttTimer", PTT_TIMER_GROUP, null, this,
                delay != 0 ? (long) delay : PTT_TIMER_DEFAULT_MS, true);
        this.virtualButtonPTT = new VirtualButton(framework, SDS_PTT_BUTTON_ID, KEY_PTT);
        this.views[0] = this.virtualButtonPTT;
        this.virtualButtonPTTLongPressed = new VirtualButton(framework, SDS_PTT_BUTTON_ID_LONG_PRESSED, KEY_PTT_LONGPRESSED);
        this.views[1] = this.virtualButtonPTTLongPressed;
        this.virtualButtonPTTDoublePressed =
                new VirtualButton(framework, SDS_PTT_BUTTON_ID_DOUBLE_PRESSED, KEY_PTT_DOUBLEPRESSED);
        this.views[2] = this.virtualButtonPTTDoublePressed;
        this.voiceKey = new JokerBtnVoiceKey(framework, JokerBtnConfig.start());
    }

    public HMIView[] getViews() {
        return this.views;
    }

    public HMIView[] getStationKeyVirtualButtons() {
        return new HMIView[0];
    }

    public void keyTurned(WheelButtonEvent event) {
        this.lcATIPEvent.log(LOG_LEVEL, LOG_KEY_TURNED, event);
        if (event.getKeyCode() == KEY_PTT) {
            this.virtualButtonPTT.keyTurned(event);
        } else {
            this.lcATIPEvent.log(LOG_LEVEL, LOG_KEY_TURNED_OTHER);
        }
    }

    public void keyPressed(KeyEvent event) {
        this.lcATIPEvent.log(LOG_LEVEL, LOG_KEY_PRESSED, event);
        if (event.getKeyCode() == KEY_PTT) {
            // A remapped short press waits for the release
            if (!this.voiceKey.holdPress(event)) {
                setSDSStatusChoice();
                this.virtualButtonPTT.keyPressed(event);
            }
        } else if (event.getKeyCode() == KEY_PTT_LONGPRESSED) {
            // A held press turned out to be a long press, so voice control gets the press first
            KeyEvent press = this.voiceKey.takeHeldPress();
            if (press != null) {
                setSDSStatusChoice();
                this.virtualButtonPTT.keyPressed(press);
            }
            setSDSStatusChoice();
            this.virtualButtonPTTLongPressed.keyPressed(event);
        } else if (event.getKeyCode() == KEY_PTT_DOUBLEPRESSED) {
            if (!this.voiceKey.doublePress(event)) {
                setSDSStatusChoice();
                this.virtualButtonPTTDoublePressed.keyPressed(event);
            }
        } else {
            this.lcATIPEvent.log(LOG_LEVEL, LOG_KEY_PRESSED_OTHER);
        }
    }

    private void setSDSStatusChoice() {
        int state = this.stateSDSManagerChoice.getValue();
        switch (state) {
            case SDS_APP_DEFINED:
                this.sdsStatusChoice.setValue(SDS_STATUS_WAITING);
                break;
            case SDS_APP_STARTING:
                this.sdsStatusChoice.setValue(SDS_STATUS_STARTING);
                break;
            case SDS_APP_ACTIVATED:
                this.lcHMIService.log(LOG_LEVEL, LOG_SDS_ACTIVATED);
                return;
            default:
                this.lcHMIService.log(LOG_LEVEL, LOG_SDS_STATE_UNKNOWN, (long) state);
                return;
        }
        this.lcHMIService.log(LOG_LEVEL, LOG_PTT_TIMER_START, this.pttTimer.getDelay());
        this.pttTimer.restart();
    }

    public void keyReleased(KeyEvent event) {
        this.lcATIPEvent.log(LOG_LEVEL, LOG_KEY_RELEASED, event);
        if (event.getKeyCode() == KEY_PTT) {
            if (!this.voiceKey.release(event)) {
                this.virtualButtonPTT.keyReleased(event);
            }
        } else {
            this.lcATIPEvent.log(LOG_LEVEL, LOG_KEY_RELEASED_OTHER);
        }
    }

    public void keyMoved(JoystickEvent event) {
    }

    public void fireTimer(Timer timer) {
        if (this.pttTimer.equals(timer)) {
            this.lcHMIService.log(LOG_LEVEL, LOG_PTT_TIMER_FIRED);
            this.sdsStatusChoice.setValue(SDS_STATUS_IDLE);
        }
    }

    public void cancelTimer(Timer timer) {
    }
}
