package de.audi.tghu.system.hmi.mibhigh3d;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.HMIService;
import de.audi.atip.hmi.event.KeyEvent;

/**
 * What the MENUSCREEN buttons do. New class, not part of lsd.jxe.
 *
 * GEM and the red engineering menu open the way their key combinations do in
 * de.audi.tghu.fwkbd.ComboKeyDetector: SETUP+CAR starts the AppDevelopment
 * bundle (GEM) and posts KEY_SET_CAR, SETUP+BACK posts KEY_SET_BACK.
 *
 * Restart CarPlay switches the USB power of the media connector off and on
 * with the firmware's own scripts, the same as unplugging the phone.
 */
final class MenuScreenActions {

    // de.audi.atip.hmi.event.KeyEvent
    private static final int KEY_PRESSED = 10401;
    private static final int KEY_SET_BACK = 24;
    private static final int KEY_SET_CAR = 25;
    // KeyMap.keySourceTable entry the combo keys use (key panel)
    private static final int KEY_SOURCE_PANEL = 1;
    // Bundle the SETUP+CAR combo starts before GEM can open
    private static final String GEM_BUNDLE = "AppDevelopment";

    private static final String USB_POWER_CYCLE = "trap '' HUP INT PIPE TERM; "
            + "/bin/sh /etc/scripts/disable_usb_pwr_0.sh > /dev/null 2>&1; "
            + "sleep 3; "
            + "/bin/sh /etc/scripts/enable_usb_pwr_0.sh > /dev/null 2>&1";
    // Ignore presses while a restart runs (power off, 3 s, power on, phone reconnects)
    private static final long RESTART_LOCK_MS = 15000;

    private static long restartStartedAt = 0;

    private MenuScreenActions() {
    }

    /** Opens GEM like SETUP+CAR. Returns a status line for the screen. */
    static String openGem(IFrameworkAccess framework, int terminalId) {
        try {
            framework.getStartupMgr().requestStartBundle(GEM_BUNDLE);
            postKey(framework, terminalId, KEY_SET_CAR);
            MenuScreen.log("GEM requested on terminal " + terminalId);
            return "Opening GEM...";
        } catch (Throwable t) {
            MenuScreen.log("GEM failed", t);
            return "Could not open GEM";
        }
    }

    /** Opens the red engineering menu like SETUP+BACK. Returns a status line. */
    static String openRedEngineering(IFrameworkAccess framework, int terminalId) {
        try {
            postKey(framework, terminalId, KEY_SET_BACK);
            MenuScreen.log("red engineering menu requested on terminal " + terminalId);
            return "Opening the red engineering menu...";
        } catch (Throwable t) {
            MenuScreen.log("red engineering menu failed", t);
            return "Could not open the red engineering menu";
        }
    }

    /** Starts the USB power cycle in the background. Returns a status line. */
    static String restartCarPlay() {
        long now = System.currentTimeMillis();
        synchronized (MenuScreenActions.class) {
            if (now - restartStartedAt < RESTART_LOCK_MS && now >= restartStartedAt) {
                return "CarPlay restart already running";
            }
            restartStartedAt = now;
        }
        try {
            Thread worker = new Thread(new Runnable() {
                public void run() {
                    try {
                        MenuScreen.log("CarPlay restart: USB power off");
                        Process p = Runtime.getRuntime().exec(new String[] {"/bin/sh", "-c", USB_POWER_CYCLE});
                        int exit = p.waitFor();
                        MenuScreen.log("CarPlay restart: USB power on, exit " + exit);
                    } catch (Throwable t) {
                        MenuScreen.log("CarPlay restart failed", t);
                    }
                }
            }, "MenuScreenCarPlayRestart");
            worker.setDaemon(true);
            worker.start();
            return "Restarting CarPlay, back in about 20 s";
        } catch (Throwable t) {
            MenuScreen.log("CarPlay restart failed", t);
            return "Could not restart CarPlay";
        }
    }

    // A key press as the key panel would send it, through the normal HMI event queue
    private static void postKey(IFrameworkAccess framework, int terminalId, int key) {
        HMIService hmi = framework.getHMIService();
        long now = framework.getMonotonicTime();
        hmi.getEventDispatcher().postEvent(
                new KeyEvent(hmi.getRootWindow(terminalId), KEY_PRESSED, now, key, terminalId, KEY_SOURCE_PANEL));
    }
}
