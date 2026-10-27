package de.audi.app.terminalmode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Hashtable;

import org.osgi.framework.ServiceRegistration;

import de.audi.atip.hmi.HMIApplication;
import de.audi.atip.hmi.IHMIServiceApp;
import de.audi.atip.hmi.model.ButtonModel;
import de.audi.atip.hmi.modelaccess.ButtonModelGUI;
import de.audi.atip.log.LogChannel;

/**
 * Replacement for de.audi.app.terminalmode.AbstractTerminalModeHMIApplication
 * from lsd.jxe (MHI2_ER_AU57x_K3663_1, MU1425).
 *
 * Rebuilt 1:1 from the original bytecode. The only change: terminal mode
 * (Apple CarPlay / Android Auto) now also connects to the shared joker
 * ("star") virtual button, so a joker set to "skip forward" skips tracks
 * there too. See JokerBtnSkipListener.
 *
 * Loaded ahead of lsd.jxe with -Xbootclasspath/p:. TerminalModeHMIApplication
 * extends this class, so fields and method signatures must stay identical.
 *
 * Boot guard: lsd.sh creates /mnt/persist/jokerbtn_pending before starting
 * the HMI with this class. The constructor deletes it, which tells the next
 * lsd.sh run that this class loaded fine. If the flag is still there on the
 * next start, lsd.sh disables the patch and starts the stock HMI.
 */
public abstract class AbstractTerminalModeHMIApplication implements HMIApplication, ITerminalModeComponent {

    // Shared virtual buttons (SharedHardkeysGUI)
    private static final int VIRTUAL_BUTTON_PREV = 1;
    private static final int VIRTUAL_BUTTON_NEXT = 2;
    private static final int VIRTUAL_BUTTON_JOKER1 = 15;
    private static final int VIRTUAL_BUTTON_16 = 16;
    private static final int VIRTUAL_BUTTON_22 = 22;

    // Terminal mode button models (ICoreTerminalModeModelBank)
    private static final int TERMINAL_HK_NEXT_BUTTON = 240002;
    private static final int TERMINAL_HK_PREV_BUTTON = 240003;
    private static final int TERMINAL_MODEL_240019 = 240019;
    private static final int TERMINAL_MODEL_240020 = 240020;
    // Free model id for the joker button model added by JOKERBTN
    private static final int JOKERBTN_JOKER_BUTTON = 240098;

    // Original log channel level and message ids
    private static final int LOG_LEVEL = 1000000;
    private static final int LOG_SCREEN_VISIBLE = 5592;
    private static final int LOG_SCREEN_HIDDEN = 5593;
    private static final int LOG_SCREEN_FADED_OUT = 5594;
    private static final int LOG_SCREEN_CONNECTED = 5595;

    // Boot guard flags, shared with the lsd.sh block written by jokerbtn.sh
    private static final String FLAG_PENDING = "/mnt/persist/jokerbtn_pending";
    private static final String FLAG_LOADED = "/mnt/persist/jokerbtn_loaded";

    protected final LogChannel logger;
    protected final IContext context;
    private volatile ServiceRegistration registration;
    private ButtonModel jokerButton;

    public AbstractTerminalModeHMIApplication(IContext context) {
        this.context = context;
        this.logger = context.getLogger().main();
        confirmBoot();
    }

    public void init() {
        Hashtable properties = new Hashtable(3);
        properties.put("moduleID", new Integer(getId()));
        properties.put("ApplicationName", "TerminalMode");
        this.registration = this.context.getServiceManager().registerService(HMIApplication.class, this, properties);
    }

    public void deinit() {
        if (this.registration != null) {
            this.context.getServiceManager().unregisterService(this.registration);
        }
    }

    public ButtonModelGUI getVirtualButton(int id) {
        switch (id) {
            case VIRTUAL_BUTTON_PREV:
                return (ButtonModelGUI) hmiServiceApp().getButtonModel(TERMINAL_HK_PREV_BUTTON);
            case VIRTUAL_BUTTON_NEXT:
                return (ButtonModelGUI) hmiServiceApp().getButtonModel(TERMINAL_HK_NEXT_BUTTON);
            case VIRTUAL_BUTTON_JOKER1:
                return getJokerButton();
            case VIRTUAL_BUTTON_16:
                return (ButtonModelGUI) hmiServiceApp().getButtonModel(TERMINAL_MODEL_240020);
            case VIRTUAL_BUTTON_22:
                return (ButtonModelGUI) hmiServiceApp().getButtonModel(TERMINAL_MODEL_240019);
            default:
                return null;
        }
    }

    public void screenVisible(int screenId, int terminalId) {
        this.logger.log(LOG_LEVEL, LOG_SCREEN_VISIBLE, getLogClass(), (long) screenId);
    }

    public void screenHidden(int screenId, int terminalId) {
        this.logger.log(LOG_LEVEL, LOG_SCREEN_HIDDEN, getLogClass(), (long) screenId);
    }

    public void popupVisible(int popupId, int terminalId) {
    }

    public void popupHidden(int popupId, int terminalId) {
    }

    public void popupRemoved(int popupId, int terminalId) {
    }

    public void screenFadedOut(int screenId, int terminalId) {
        this.logger.log(LOG_LEVEL, LOG_SCREEN_FADED_OUT, getLogClass(), (long) screenId);
    }

    public void screenConnected(int screenId, int terminalId) {
        this.logger.log(LOG_LEVEL, LOG_SCREEN_CONNECTED, getLogClass(), (long) screenId);
    }

    protected abstract String getLogClass();

    protected abstract int getTerminalModeScreenId();

    private IHMIServiceApp hmiServiceApp() {
        return this.context.getFramework().getHmiServiceApp();
    }

    /** The joker button model terminal mode connects to. Null (stock behaviour) if it can't be built. */
    private synchronized ButtonModelGUI getJokerButton() {
        try {
            if (this.jokerButton == null) {
                ButtonModel button = new ButtonModel(JOKERBTN_JOKER_BUTTON);
                button.setButtonListener(new JokerBtnSkipListener(this.context));
                this.jokerButton = button;
            }
            return this.jokerButton;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Tells the lsd.sh boot guard that the patched class loaded. Never throws. */
    private static void confirmBoot() {
        try {
            new File(FLAG_PENDING).delete();
            new FileOutputStream(FLAG_LOADED).close();
        } catch (Throwable t) {
            // The guard only disables the patch, so a failed write must not stop the HMI
        }
    }
}
