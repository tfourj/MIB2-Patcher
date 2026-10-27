package de.audi.tghu.fwhmi.appmgr;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import org.dsi.ifc.carplay.DSICarplay;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.HMIService;
import de.audi.atip.hmi.model.ButtonModel;
import de.audi.atip.log.LogChannel;

/**
 * Replacement for de.audi.tghu.fwhmi.appmgr.HardKeyManager from lsd.jxe
 * (MHI2_ER_AU57x_K3663_1, MU1425).
 *
 * Rebuilt 1:1 from the original bytecode. The only change is the TEL key
 * (hard-key model 4): it opens Apple CarPlay / terminal mode instead of the
 * phone menu. What TEL does while a terminal mode screen is already shown
 * depends on the mode in /mnt/persist/tel2acp_mode (see handleTelKey).
 * When TEL opens CarPlay it also lights the TEL key, because the terminal
 * mode state never sets a key light itself.
 *
 * Loaded ahead of lsd.jxe with -Xbootclasspath/p:, so fields and method
 * signatures must stay identical to the original class.
 *
 * Boot guard: lsd.sh creates /mnt/persist/tel2acp_pending before starting
 * the HMI with this class. The constructor deletes it, which tells the next
 * lsd.sh run that this class loaded fine. If the flag is still there on the
 * next start, lsd.sh disables the patch and starts the stock HMI.
 */
public class HardKeyManager {

    private static final int HARDKEY_COUNT = 16;

    // System state machine events (de.audi.atip.statemachine.SMEventSystem)
    private static final int HK_TUNER = 101;
    private static final int HK_MEDIA = 102;
    private static final int HK_TEL = 103;
    private static final int HK_NAV = 104;
    private static final int HK_INFO = 105;
    private static final int HK_CAR = 106;
    private static final int HK_NET = 107;
    private static final int HK_SETTINGS = 111;
    private static final int HK_SETUP_RETURN = 151;
    private static final int HK_SETUP_CAR = 152;
    private static final int HK_SETUP_INFO = 153;
    private static final int HK_CAR_INFO = 154;
    private static final int MFL_MENU = 1374;
    private static final int HK_MENU = 1400;
    private static final int SWITCH_TO_TERMINAL_MODE = 1903;

    // TEL is ignored while this popup is shown (original behaviour)
    private static final int POPUP_TEL_BLOCKED = 250002;

    // Key light id of TEL, as the phone state machine passes it to KbdService
    private static final int HK_LIGHT_TEL = 4;

    // Terminal mode app id 24, screens 240000..240009 (TerminalModeScreenFactory)
    private static final int SCREEN_APP_DIVISOR = 10000;
    private static final int TERMINAL_MODE_APP_ID = 24;

    // ICoreSystemModelBank.ACTIVE_SMARTPHONE_DEVICE_CHOICE: 1 while a CarPlay device is active
    // (2 Android Auto, 0 none), set by TMDeviceControl
    private static final int ACTIVE_SMARTPHONE_DEVICE_CHOICE = 1122;
    private static final int ACTIVE_DEVICE_CARPLAY = 1;

    // CarPlay DSI service, asks the iPhone to show one of its own screens (DSICarplay.UI_*)
    private static final String DSI_CARPLAY = "org.dsi.ifc.carplay.DSICarplay";
    private static final int CARPLAY_UI_HOME = 1;
    private static final int CARPLAY_UI_PHONE = 2;
    private static final int CARPLAY_UI_NAVIGATION = 3;
    private static final int CARPLAY_UI_MEDIA = 4;
    private static final int CARPLAY_UI_MEDIA_NOWPLAYING = 5;

    // What TEL does while terminal mode is on screen, written by "tel2acp.sh mode-*".
    // "phone" is the 1.1.0 name of the custom mode.
    private static final String MODE_FILE = "/mnt/persist/tel2acp_mode";
    private static final int MODE_TEL = 0;
    private static final int MODE_CARPLAY = 1;
    private static final int MODE_CUSTOM = 2;

    // CarPlay screen the custom mode opens, written by "tel2acp.sh custom-*". Phone when not set
    private static final String CUSTOM_FILE = "/mnt/persist/tel2acp_custom";

    // Original log channel level and message ids
    private static final int LOG_LEVEL = 1000000;
    private static final int LOG_KEY_15 = 1264;
    private static final int LOG_UNKNOWN_KEY = 1265;
    private static final int LOG_FIRE_SM_EVENT = 1266;

    // Boot guard flags, shared with the lsd.sh block written by tel2acp.sh
    private static final String FLAG_PENDING = "/mnt/persist/tel2acp_pending";
    private static final String FLAG_LOADED = "/mnt/persist/tel2acp_loaded";

    private final IFrameworkAccess framework;
    private final LogChannel logChannel;
    private final ButtonModel[] hardkeys;

    public HardKeyManager(IFrameworkAccess framework, LogChannel logChannel) {
        this.framework = framework;
        this.logChannel = logChannel;
        this.hardkeys = new ButtonModel[HARDKEY_COUNT];
        for (int i = 0; i < HARDKEY_COUNT; i++) {
            this.hardkeys[i] = new ButtonModel(i + 1);
        }
        confirmBoot();
    }

    public void triggerKeyEvent(int terminalId, int key) {
        switch (key) {
            case 1:
                fireSMEvent(terminalId, HK_TUNER);
                break;
            case 2:
                fireSMEvent(terminalId, HK_MEDIA);
                break;
            case 3:
                fireSMEvent(terminalId, HK_NET);
                break;
            case 4:
                handleTelKey(terminalId);
                break;
            case 5:
                fireSMEvent(terminalId, HK_NAV);
                break;
            case 6:
                fireSMEvent(terminalId, HK_INFO);
                break;
            case 7:
                fireSMEvent(terminalId, HK_CAR);
                break;
            case 8:
                fireSMEvent(terminalId, HK_SETTINGS);
                break;
            case 9:
                fireSMEvent(terminalId, MFL_MENU);
                break;
            case 10:
                fireSMEvent(terminalId, HK_SETUP_RETURN);
                break;
            case 11:
                fireSMEvent(terminalId, HK_SETUP_CAR);
                break;
            case 12:
                fireSMEvent(terminalId, HK_SETUP_INFO);
                break;
            case 13:
                fireSMEvent(terminalId, HK_CAR_INFO);
                break;
            case 14:
                fireSMEvent(terminalId, HK_NET);
                break;
            case 15:
                getLogChannel().log(LOG_LEVEL, LOG_KEY_15);
                break;
            case 16:
                fireSMEvent(terminalId, HK_MENU);
                break;
            default:
                getLogChannel().log(LOG_LEVEL, LOG_UNKNOWN_KEY, (long) key);
                break;
        }
    }

    public ButtonModel[] getModels() {
        return this.hardkeys;
    }

    public ButtonModel getModel(int id) {
        if (id > 0 && id < this.hardkeys.length + 1) {
            return this.hardkeys[id - 1];
        }
        return null;
    }

    public ButtonModel getModel(int terminalId, int id) {
        return getModel(id);
    }

    /**
     * TEL opens CarPlay. TEL again while CarPlay is on screen depends on the mode:
     * tel opens the phone menu, carplay does nothing, custom opens the CarPlay
     * screen picked in the custom file (the phone menu when that isn't possible).
     */
    private void handleTelKey(int terminalId) {
        HMIService hmi = getHmiService();
        if (hmi.getCurrentPopup() == POPUP_TEL_BLOCKED) {
            return;
        }
        if (hmi.getCurrentScreenID() / SCREEN_APP_DIVISOR != TERMINAL_MODE_APP_ID) {
            fireSMEvent(terminalId, SWITCH_TO_TERMINAL_MODE);
            lightTelKey(terminalId);
            return;
        }
        int mode = readMode();
        if (mode == MODE_CARPLAY) {
            return;
        }
        if (mode == MODE_CUSTOM && openCarPlayUi(readCustom())) {
            lightTelKey(terminalId);
            return;
        }
        fireSMEvent(terminalId, HK_TEL);
    }

    /** The TEL mode from the mode file. Missing or unknown means tel. */
    private static int readMode() {
        String mode = readSetting(MODE_FILE);
        if (mode.equals("carplay")) {
            return MODE_CARPLAY;
        }
        if (mode.equals("custom") || mode.equals("phone")) {
            return MODE_CUSTOM;
        }
        return MODE_TEL;
    }

    /** The CarPlay screen from the custom file. Missing or unknown means phone. */
    private static int readCustom() {
        String custom = readSetting(CUSTOM_FILE);
        if (custom.equals("home")) {
            return CARPLAY_UI_HOME;
        }
        if (custom.equals("maps")) {
            return CARPLAY_UI_NAVIGATION;
        }
        if (custom.equals("music")) {
            return CARPLAY_UI_MEDIA;
        }
        if (custom.equals("nowplaying")) {
            return CARPLAY_UI_MEDIA_NOWPLAYING;
        }
        return CARPLAY_UI_PHONE;
    }

    /** The first word of a setting file, empty when it is missing. Never throws. */
    private static String readSetting(String path) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(path);
            byte[] buf = new byte[16];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n).trim() : "";
        } catch (Throwable t) {
            // Not set yet
            return "";
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable t) {
                // Nothing left to do
            }
        }
    }

    /**
     * Asks the iPhone to show one of its CarPlay screens. Only while a CarPlay device
     * is active, so Android Auto and the no-device screen keep the phone menu. Never throws.
     */
    private boolean openCarPlayUi(int ui) {
        try {
            if (this.framework.getHmiServiceApp().getChoiceModel(ACTIVE_SMARTPHONE_DEVICE_CHOICE).getValue()
                    != ACTIVE_DEVICE_CARPLAY) {
                return false;
            }
            BundleContext context = this.framework.getBundleCxt();
            ServiceReference ref = context.getServiceReference(DSI_CARPLAY);
            if (ref == null) {
                return false;
            }
            try {
                DSICarplay carplay = (DSICarplay) context.getService(ref);
                if (carplay == null) {
                    return false;
                }
                carplay.requestUI(ui);
                return true;
            } finally {
                context.ungetService(ref);
            }
        } catch (Throwable t) {
            // Fall back to the phone menu
            return false;
        }
    }

    /** Lights TEL like the phone screen does. Never throws, the key press already went through. */
    private void lightTelKey(int terminalId) {
        try {
            this.framework.getKbdService().setHKIlluminationExclusive(terminalId, HK_LIGHT_TEL, true);
        } catch (Throwable t) {
            // A missing light must not break the TEL key
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

    private void fireSMEvent(int terminalId, int event) {
        getLogChannel().log(LOG_LEVEL, LOG_FIRE_SM_EVENT, (long) terminalId, (long) event);
        getHmiService().fireSMEvent(terminalId, event);
    }

    private HMIService getHmiService() {
        return this.framework.getHMIService();
    }

    private LogChannel getLogChannel() {
        return this.logChannel;
    }
}
