package de.audi.tghu.fwhmi;

import java.io.File;
import java.io.FileInputStream;

import de.audi.tghu.fwkbd.KeyMap;

/**
 * Button settings for the steering wheel joker (star), INFO (nav
 * announcement) and voice (PTT) buttons. New class, not part of lsd.jxe.
 *
 * Settings live in /mnt/persist/jokerbtn.conf, written by jokerbtn.sh (SSH,
 * GEM or mod/command.sh). A background thread reads the file every few
 * seconds, so a new setting works without a reboot. The file has one line per
 * button:
 *
 *   joker=stock
 *   info=prev
 *   voice=next
 *
 * A missing file, a missing line or an unknown function means "stock". For
 * the joker, stock is the function chosen in the MMI car settings.
 *
 * The joker and INFO are remapped in KeyMap.tableDSI, the DSI to HMI key
 * table that KbdListener reads on every press: their row then produces the
 * chosen HMI key, so every app sees exactly that key, press and release
 * included. The voice button is handled by JokerBtnVoiceKey, because its long
 * press must stay stock.
 */
final class JokerBtnConfig implements Runnable {

    /** No remap: the button does what it does in the stock firmware. */
    static final int STOCK = -1;

    // de.audi.atip.hmi.event.KeyEvent key codes the buttons can be set to
    private static final int KEY_TUN = 1;
    private static final int KEY_MED = 2;
    private static final int KEY_TEL = 4;
    private static final int KEY_NAV = 5;
    private static final int KEY_PREV = 13;
    private static final int KEY_NEXT = 14;
    private static final int INC_ROLLER_RIGHT = 21;
    private static final int KEY_INFO = 49;
    private static final int KEY_JOKER1 = 50;
    private static final int KEY_RSE_MENU = 59;

    // org.dsi.ifc.keypanel.Constants: the steering wheel INFO and joker buttons
    private static final int DSI_KEY_MFW_INFO = 52;
    private static final int DSI_KEY_MFW_JOKER1 = 58;

    private static final String CONFIG_FILE = "/mnt/persist/jokerbtn.conf";
    private static final long POLL_INTERVAL_MS = 2000;
    private static final int MAX_CONFIG_SIZE = 1024;

    private volatile int voiceKey = STOCK;
    private String lastConfig = null;

    private JokerBtnConfig() {
    }

    /** Applies the settings and starts watching the file. Never throws. */
    static JokerBtnConfig start() {
        JokerBtnConfig config = new JokerBtnConfig();
        config.reload();
        try {
            Thread watcher = new Thread(config, "JokerBtnConfig");
            watcher.setDaemon(true);
            watcher.setPriority(Thread.MIN_PRIORITY);
            watcher.start();
        } catch (Throwable t) {
            // Without the watcher the settings from startup still apply
        }
        return config;
    }

    /** HMI key code for a short voice button press, or STOCK. */
    int getVoiceKey() {
        return this.voiceKey;
    }

    public void run() {
        while (true) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            reload();
        }
    }

    private void reload() {
        try {
            String text = readConfig();
            if (text.equals(this.lastConfig)) {
                return;
            }
            this.lastConfig = text;
            int joker = STOCK;
            int info = STOCK;
            int voice = STOCK;
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
                String button = line.substring(0, eq).trim();
                int key = keyFor(line.substring(eq + 1).trim());
                if (button.equals("joker")) {
                    joker = key;
                } else if (button.equals("info")) {
                    info = key;
                } else if (button.equals("voice")) {
                    voice = key;
                }
            }
            applyKey(DSI_KEY_MFW_JOKER1, joker == STOCK ? KEY_JOKER1 : joker);
            applyKey(DSI_KEY_MFW_INFO, info == STOCK ? KEY_INFO : info);
            this.voiceKey = voice;
        } catch (Throwable t) {
            // Keep the last working settings
        }
    }

    private static int keyFor(String function) {
        if (function.equals("prev")) {
            return KEY_PREV;
        } else if (function.equals("next")) {
            return KEY_NEXT;
        } else if (function.equals("mute")) {
            return INC_ROLLER_RIGHT;
        } else if (function.equals("radio")) {
            return KEY_TUN;
        } else if (function.equals("media")) {
            return KEY_MED;
        } else if (function.equals("phone")) {
            return KEY_TEL;
        } else if (function.equals("nav")) {
            return KEY_NAV;
        } else if (function.equals("menu")) {
            return KEY_RSE_MENU;
        } else if (function.equals("navinfo")) {
            return KEY_INFO;
        }
        return STOCK;
    }

    /** Points the row of a DSI key in the key table at the given HMI key. */
    private static void applyKey(int dsiKey, int hmiKey) {
        int[][] table = KeyMap.tableDSI;
        for (int i = 0; i < table.length; i++) {
            if (table[i][0] == dsiKey) {
                table[i][1] = hmiKey;
            }
        }
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
