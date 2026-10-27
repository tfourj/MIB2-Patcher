package de.audi.tghu.system.hmi.mibhigh3d;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Hashtable;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.HMIBundle;
import de.audi.atip.hmi.HMIServiceSM;
import de.audi.atip.hmi.view.AbstractScreenFactory;
import de.audi.tghu.hmi.view.HMITerminalImpl;
import de.audi.tghu.hmi.view.InitializationContext;

/**
 * The MENUSCREEN feature: a MENU tile that opens a screen with Open GEM, Open
 * REM and Restart CarPlay (see MenuScreenWidget). New class, not part of
 * lsd.jxe.
 *
 * The patched ButtonWidget3D asks isTile() which MENU tile is ours. That tile
 * stays visible, shows getTileText() and calls open() when pressed. open()
 * registers MenuScreenBundle as a new HMI bundle on first use and shows its
 * screen. The bundle takes a free ID from 26 to 31: HMIRegistry looks bundles
 * up in an array cached at startup (32 entries for the firmware's IDs 0-25),
 * so a higher ID would be stored in a new array the lookup never sees. The state machine stays in the MENU state,
 * so the MENU key brings the main menu back.
 *
 * Settings live in /mnt/persist/menuscreen.conf, written by menuscreen.sh:
 *
 *   slot=online      MENU tile to reuse: online, breakdown or off
 *   tile=Custom      text on the tile
 *   title=...        screen title
 *
 * The file is read again when it is older than two seconds, so changes work
 * without a reboot. Messages go to /mnt/persist/menuscreen.log, which
 * menuscreen.sh status copies to the SD card.
 */
public final class MenuScreen {

    // Free bundle IDs that still fit the registry's startup array (see above)
    private static final int FIRST_FREE_BUNDLE_ID = 26;
    private static final int LAST_FREE_BUNDLE_ID = 31;
    private static final String HMI_BUNDLE = "de.audi.atip.hmi.HMIBundle";
    private static final int SYSTEM_BUNDLE_ID = 0;

    // Hidden tiles of SystemScreenBag1.mainGroupsWizard that can be reused
    private static final int FUNCTION_ONLINE = 1005;
    private static final int FUNCTION_BREAKDOWN = 1011;
    private static final int FUNCTION_NONE = -1;

    private static final String CONFIG_FILE = "/mnt/persist/menuscreen.conf";
    // /mnt/persist is where the HMI's Java code is known to write (TEL2ACP boot guard)
    private static final String LOG_FILE = "/mnt/persist/menuscreen.log";
    private static final long CONFIG_MAX_AGE_MS = 2000;
    private static final int MAX_CONFIG_SIZE = 2048;
    private static final long MAX_LOG_SIZE = 16384;

    private static int tileFunction = FUNCTION_ONLINE;
    private static String tileName = "online";
    private static String tileText = "Custom";
    private static String title = "MENUSCREEN";
    private static long configReadAt = 0;
    private static String lastConfig = null;
    private static MenuScreenBundle bundle = null;
    // Skin of the firmware's System bundle (ID 0), found by freeBundleId()
    private static String systemSkin = null;
    private static IFrameworkAccess framework = null;
    private static int terminalId = -1;
    private static boolean backFilterAdded = false;

    private MenuScreen() {
    }

    /** True for the MENU tile that opens the screen. Never throws. */
    public static boolean isTile(int functionId, String widgetName) {
        reloadIfOld();
        return functionId == tileFunction && tileName.equals(widgetName);
    }

    public static String getTileText() {
        reloadIfOld();
        return tileText;
    }

    static String getTitle() {
        reloadIfOld();
        return title;
    }

    /** Screen ID of the MENUSCREEN screen, -1 before the bundle is registered. */
    static int screenId() {
        return bundle == null ? -1 : bundle.getId() * 10000;
    }

    /** The framework of the terminal that opened the screen, null before that. */
    static IFrameworkAccess getFramework() {
        return framework;
    }

    /** The terminal the screen was last opened on, -1 before that. */
    static int getTerminalId() {
        return terminalId;
    }

    /** Shows the screen on the tile's terminal. Never throws. */
    public static void open(HMITerminalImpl terminal, InitializationContext context) {
        log("open requested");
        try {
            AbstractScreenFactory factory = context.getScreenFactory();
            if (!(factory instanceof SystemScreenFactory)) {
                log("open: tile is not on a system screen, ignored");
                return;
            }
            framework = terminal.getFramework();
            if (bundle == null) {
                BundleContext bc = framework.getBundleCxt();
                int id = freeBundleId(bc);
                if (id < 0) {
                    log("open: no free HMI bundle ID from " + FIRST_FREE_BUNDLE_ID + " to " + LAST_FREE_BUNDLE_ID);
                    return;
                }
                MenuScreenBundle newBundle = new MenuScreenBundle((SystemScreenFactory) factory, skin(), id);
                Hashtable properties = new Hashtable();
                properties.put("ApplicationName", "MenuScreen");
                properties.put("Skin", newBundle.getSkin());
                bc.registerService(HMI_BUNDLE, newBundle, properties);
                bundle = newBundle;
                log("bundle " + id + " registered for skin " + newBundle.getSkin());
            }
            if (!backFilterAdded) {
                framework.getHMIService().getEventDispatcherAdmin().addEventFilter(
                        new MenuScreenBackFilter(framework.getHMIService()));
                backFilterAdded = true;
                log("BACK filter added");
            }
            terminalId = terminal.getTerminalID();
            int screen = screenId();
            HMIServiceSM sm = (HMIServiceSM) framework.getHMIService();
            sm.showScreen(terminalId, 0, screen, false, false, new int[] {screen}, false, new int[0]);
            log("screen " + screen + " shown on terminal " + terminalId);
        } catch (Throwable t) {
            log("open failed", t);
        }
    }

    /** Appends a message and the stack trace to the log. Never throws. */
    static void log(String message, Throwable t) {
        log(message + ": " + t);
        try {
            PrintStream out = new PrintStream(new FileOutputStream(LOG_FILE, true));
            try {
                t.printStackTrace(out);
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
            // Logging is best effort
        }
    }

    /** Appends a line to /mnt/persist/menuscreen.log. Never throws. */
    public static void log(String message) {
        try {
            File file = new File(LOG_FILE);
            boolean append = file.length() < MAX_LOG_SIZE;
            FileOutputStream out = new FileOutputStream(LOG_FILE, append);
            try {
                out.write((System.currentTimeMillis() + " " + message + "\n").getBytes("ISO-8859-1"));
            } finally {
                out.close();
            }
        } catch (Throwable t) {
            // Logging is best effort
        }
    }

    // Highest bundle ID from 26 to 31 that no registered HMI bundle uses, or -1
    private static int freeBundleId(BundleContext bc) {
        boolean[] used = new boolean[LAST_FREE_BUNDLE_ID + 1];
        ServiceReference[] refs = bc.getServiceReferences(HMI_BUNDLE, null);
        for (int i = 0; refs != null && i < refs.length; i++) {
            Object service = bc.getService(refs[i]);
            if (service instanceof HMIBundle) {
                int id = ((HMIBundle) service).getId();
                if (id >= 0 && id < used.length) {
                    used[id] = true;
                }
                if (id == SYSTEM_BUNDLE_ID) {
                    systemSkin = ((HMIBundle) service).getSkin();
                }
            }
            bc.ungetService(refs[i]);
        }
        for (int id = LAST_FREE_BUNDLE_ID; id >= FIRST_FREE_BUNDLE_ID; id--) {
            if (!used[id]) {
                return id;
            }
        }
        return -1;
    }

    // The System bundle's skin, so the registry files our bundle in the same list
    private static String skin() {
        if (systemSkin != null) {
            return systemSkin;
        }
        return System.getProperty("variant.skin", "MIBHigh3D");
    }

    private static synchronized void reloadIfOld() {
        long now = System.currentTimeMillis();
        if (now - configReadAt < CONFIG_MAX_AGE_MS && now >= configReadAt) {
            return;
        }
        configReadAt = now;
        try {
            String text = readConfig();
            if (text.equals(lastConfig)) {
                return;
            }
            lastConfig = text;
            applyConfig(text);
        } catch (Throwable t) {
            // Keep the last working settings
        }
    }

    private static void applyConfig(String text) {
        int function = FUNCTION_ONLINE;
        String name = "online";
        String newTileText = "Custom";
        String newTitle = "MENUSCREEN";
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = text.length();
            }
            String line = text.substring(start, end).trim();
            start = end + 1;
            int eq = line.indexOf('=');
            if (eq < 0 || line.startsWith("#")) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (key.equals("slot")) {
                if (value.equals("breakdown")) {
                    function = FUNCTION_BREAKDOWN;
                    name = "breakdown";
                } else if (value.equals("off")) {
                    function = FUNCTION_NONE;
                    name = "";
                } else {
                    function = FUNCTION_ONLINE;
                    name = "online";
                }
            } else if (key.equals("tile") && value.length() > 0) {
                newTileText = value;
            } else if (key.equals("title") && value.length() > 0) {
                newTitle = value;
            }
        }
        tileFunction = function;
        tileName = name;
        tileText = newTileText;
        title = newTitle;
        log("settings: slot " + (name.length() > 0 ? name : "off") + ", tile " + newTileText);
    }

    /** The settings file as text, or "" when it doesn't exist. */
    private static String readConfig() throws Exception {
        File file = new File(CONFIG_FILE);
        if (!file.isFile()) {
            return "";
        }
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] buf = new byte[MAX_CONFIG_SIZE];
            int len = 0;
            int n;
            while (len < buf.length && (n = in.read(buf, len, buf.length - len)) > 0) {
                len += n;
            }
            return new String(buf, 0, len, "ISO-8859-1");
        } finally {
            in.close();
        }
    }
}
