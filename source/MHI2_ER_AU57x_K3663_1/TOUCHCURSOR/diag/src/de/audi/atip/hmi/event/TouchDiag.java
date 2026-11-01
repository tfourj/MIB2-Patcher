package de.audi.atip.hmi.event;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import de.audi.atip.hmi.IRootWindow;
import de.audi.atip.hmi.KbdService;
import de.audi.atip.hmi.view.Screen;
import de.audi.tghu.fwhmi.AbstractWindow;

/**
 * TOUCHCURSOR diagnostic: logs what the MMI touchpad sends and can send it to
 * CarPlay as touchscreen touches. New class, not part of lsd.jxe.
 *
 * Every touchpad event (TouchEvent) and key event (KeyEvent, joystick and
 * wheel included) goes through ATIPEvent.dispatch(), which calls intercept().
 * Each one is written to /ramdisk/touchdiag.log with the ID of the screen that
 * is showing, so the log shows which events reach the CarPlay screen.
 *
 * While /ramdisk/touchdiag.inject exists, touchpad events are not passed on to
 * the HMI. Instead the finger position is mapped onto the CarPlay window and
 * sent as a touchscreen touch through DSICarplaySafeProxy: press, move and
 * release follow the finger, a tap with no press around it becomes a press and
 * a release. Key events from the touchpad (key source KBD_TOUCHPAD, touchpad
 * encoder keys) are swallowed too, so a swipe doesn't also move the focus.
 * Handwriting results and approach events still go to the HMI.
 *
 * On the CarPlay screen the keypanel runs in the recognizer mode the terminal
 * mode app sets (25, 26 with text input): presses carry a position, but moves
 * only come as touchpad encoder and zone keys. While /ramdisk/touchdiag.mode
 * holds a KbdService recognizer mode (e.g. 6, map panning), a touchpad press
 * on the CarPlay screen switches the keypanel to it, the way the map screen
 * does. It is switched again after touchpad encoder keys show up, since only
 * the terminal mode app's own modes send those.
 *
 * While /ramdisk/touchdiag.cursor exists, the CarPlay screen gets a cursor
 * (TouchCursor) instead: the keypanel goes to map panning (6) unless the mode
 * file says otherwise, finger moves (TOUCH_PAD_MOVED_REL) move the cursor and
 * a tap is sent to the iPhone as a touch where the cursor is. The touchpad
 * keys and the joystick event that comes with a press are swallowed, so the
 * focus doesn't move too.
 */
public final class TouchDiag {

    /** Receives the touches for CarPlay, set by DSICarplaySafeProxy. */
    public interface Sink {
        void touch(int fingers, int x, int y);
    }

    private static final String LOG_FILE = "/ramdisk/touchdiag.log";
    private static final String INJECT_FLAG = "/ramdisk/touchdiag.inject";
    private static final String MODE_FILE = "/ramdisk/touchdiag.mode";
    private static final String CURSOR_FLAG = "/ramdisk/touchdiag.cursor";
    private static final long MAX_LOG_SIZE = 1024 * 1024;
    private static final long FLAG_CHECK_MS = 1000;
    private static final long LOG_RETRY_MS = 10000;
    private static final int MAX_MODE_FILE_SIZE = 16;

    // KbdService.RECOGNIZERMODE_MAP_PANNING, which sends finger moves
    private static final int MODE_MAP_PANNING = 6;
    // Cursor pixels per touchpad step, as a fraction
    private static final int CURSOR_GAIN_TIMES = 5;
    private static final int CURSOR_GAIN_DIVIDE = 2;
    // The joystick event of a press near the pad edge comes about 20 ms after the press
    private static final long JOYSTICK_AFTER_PRESS_MS = 150;

    // Screen ID of the CarPlay screen (seen in the first log)
    private static final int CARPLAY_SCREEN = 240000;
    // A tap within this time after a release belongs to that press
    private static final long TAP_AFTER_RELEASE_MS = 500;

    // de.audi.atip.hmi.event.TouchEvent IDs
    private static final int TOUCH_PAD_PRESSED = 10904;
    private static final int TOUCH_PAD_RELEASED = 10905;
    private static final int TOUCH_PAD_TAPPED = 10906;
    private static final int TOUCH_PAD_CHARS_RECOGNIZED = 10908;
    private static final int TOUCH_PAD_APPROACHED = 10909;
    private static final int TOUCH_PAD_ABANDONED = 10910;
    private static final int TOUCH_PAD_MOVED_ABS = 10911;
    private static final int TOUCH_PAD_MOVED_REL = 10912;

    // de.audi.atip.hmi.event.KeyEvent key source and touchpad encoder keys
    private static final int KBD_TOUCHPAD = 9;
    private static final int KEY_TP_ENCODER_X = 64;
    private static final int KEY_TP_ENCODER_Y = 65;

    private static FileOutputStream out = null;
    private static long logSize = 0;
    private static long logOpenFailed = 0;
    private static boolean logFull = false;

    private static volatile Sink sink = null;
    private static volatile int windowWidth = 780;
    private static volatile int windowHeight = 468;
    private static volatile int windowX = 20;
    private static volatile int windowY = 5;
    private static volatile int padWidth = 0;
    private static volatile int padHeight = 0;
    private static volatile boolean inject = false;
    private static volatile long flagChecked = 0;
    private static volatile boolean cursor = false;
    private static volatile long cursorChecked = 0;

    // Only used on the HMI thread
    private static int maxX = 0;
    private static int maxY = 0;
    private static boolean fingerDown = false;
    private static int touchX = 0;
    private static int touchY = 0;
    private static long lastRelease = 0;
    private static int mode = 0;
    private static long modeChecked = 0;
    private static int modeApplied = 0;
    private static boolean modeLost = false;
    private static int cursorX = 390;
    private static int cursorY = 234;
    private static long lastPress = 0;

    private TouchDiag() {
    }

    /** Logs the event; true means it was handled here and must not be dispatched. Never throws. */
    static boolean intercept(ATIPEvent event) {
        try {
            if (event instanceof TouchEvent) {
                return onTouch((TouchEvent) event);
            }
            if (event instanceof KeyEvent) {
                return onKey((KeyEvent) event);
            }
        } catch (Throwable t) {
            // Never break event dispatch
        }
        return false;
    }

    public static void setSink(Sink newSink) {
        sink = newSink;
    }

    /** The CarPlay window size from startService, the space touches are sent in. */
    public static void setWindow(int width, int height) {
        if (width > 0 && height > 0) {
            windowWidth = width;
            windowHeight = height;
        }
    }

    /** Where the CarPlay window sits on the screen, from startService. */
    public static void setOffset(int x, int y) {
        windowX = x;
        windowY = y;
    }

    /** The touchpad resolution from startService. */
    public static void setPad(int width, int height) {
        padWidth = width;
        padHeight = height;
    }

    /** True while the inject flag file exists, checked at most once a second. */
    public static boolean isInjectOn() {
        long now = System.currentTimeMillis();
        if (now - flagChecked >= FLAG_CHECK_MS || now < flagChecked) {
            flagChecked = now;
            inject = new File(INJECT_FLAG).exists();
        }
        return inject;
    }

    /** True while the cursor flag file exists, checked at most once a second. */
    public static boolean isCursorOn() {
        long now = System.currentTimeMillis();
        if (now - cursorChecked >= FLAG_CHECK_MS || now < cursorChecked) {
            cursorChecked = now;
            cursor = new File(CURSOR_FLAG).exists();
        }
        return cursor;
    }

    private static boolean onTouch(TouchEvent event) {
        int id = event.getID();
        int x = event.getX();
        int y = event.getY();
        if (x > maxX) {
            maxX = x;
        }
        if (y > maxY) {
            maxY = y;
        }

        String screen = screenOf(event);
        StringBuffer line = new StringBuffer(80);
        line.append("T ").append(id).append(" code=").append(event.getCode());
        line.append(" x=").append(x).append(" y=").append(y).append(" f=").append(event.getFingerID());
        line.append(" scr=").append(screen);
        log(line.toString());

        boolean onCarPlay = screen.equals(String.valueOf(CARPLAY_SCREEN));
        if (isCursorOn()) {
            return onCursorTouch(event, id, x, y, onCarPlay);
        }
        if (TouchCursor.isShown()) {
            TouchCursor.hide(event.getReceiver());
        }
        if (id == TOUCH_PAD_PRESSED && onCarPlay) {
            applyMode(event);
        }

        if (!isInjectOn()) {
            return false;
        }
        if (id == TOUCH_PAD_CHARS_RECOGNIZED || id == TOUCH_PAD_APPROACHED || id == TOUCH_PAD_ABANDONED) {
            return false;
        }

        int tx = mapX(x);
        int ty = mapY(y);
        if (id == TOUCH_PAD_PRESSED) {
            fingerDown = true;
            touchX = tx;
            touchY = ty;
            send(1, tx, ty);
        } else if (id == TOUCH_PAD_MOVED_ABS) {
            if (fingerDown) {
                touchX = tx;
                touchY = ty;
                send(1, tx, ty);
            }
        } else if (id == TOUCH_PAD_RELEASED) {
            // Releases come with x and y 0: lift the finger where it last was
            if (fingerDown) {
                send(0, touchX, touchY);
            }
            fingerDown = false;
            lastRelease = System.currentTimeMillis();
        } else if (id == TOUCH_PAD_TAPPED) {
            long sinceRelease = System.currentTimeMillis() - lastRelease;
            // Taps also come with x and y 0, so only one with a real position is sent
            boolean hasPosition = x != 0 || y != 0;
            if (!fingerDown && hasPosition && (sinceRelease < 0 || sinceRelease > TAP_AFTER_RELEASE_MS)) {
                send(1, tx, ty);
                send(0, tx, ty);
            }
        }
        return true;
    }

    /** Cursor mode: moves the cursor and sends taps. True means swallowed. */
    private static boolean onCursorTouch(TouchEvent event, int id, int x, int y, boolean onCarPlay) {
        ATIPEventListener receiver = event.getReceiver();
        if (!onCarPlay) {
            TouchCursor.hide(receiver);
            return false;
        }
        if (id == TOUCH_PAD_CHARS_RECOGNIZED || id == TOUCH_PAD_APPROACHED || id == TOUCH_PAD_ABANDONED) {
            return false;
        }
        if (id == TOUCH_PAD_PRESSED) {
            lastPress = System.currentTimeMillis();
            applyMode(event);
            showCursor(receiver);
        } else if (id == TOUCH_PAD_MOVED_REL) {
            cursorX = clamp(cursorX + step(x), windowWidth);
            cursorY = clamp(cursorY + step(y), windowHeight);
            showCursor(receiver);
        } else if (id == TOUCH_PAD_TAPPED) {
            send(1, cursorX, cursorY);
            send(0, cursorX, cursorY);
        }
        return true;
    }

    private static void showCursor(ATIPEventListener receiver) {
        TouchCursor.show(receiver, windowX + cursorX, windowY + cursorY);
    }

    /** Cursor pixels for one MOVED_REL value, a signed byte (255 is -1). */
    private static int step(int value) {
        int signed = value > 127 ? value - 256 : value;
        return signed * CURSOR_GAIN_TIMES / CURSOR_GAIN_DIVIDE;
    }

    private static int clamp(int value, int size) {
        if (value < 0) {
            return 0;
        }
        return value >= size ? size - 1 : value;
    }

    private static boolean onKey(KeyEvent event) {
        int code = event.getKeyCode();
        int source = event.getKeySource();

        StringBuffer line = new StringBuffer(80);
        line.append("K ").append(event.getID()).append(" key=").append(code).append(" src=").append(source);
        if (event instanceof JoystickEvent) {
            line.append(" dir=").append(((JoystickEvent) event).getDirection());
        } else if (event instanceof WheelButtonEvent) {
            WheelButtonEvent wheel = (WheelButtonEvent) event;
            line.append(" dir=").append(wheel.getDirection()).append(" clicks=").append(wheel.getClickCount());
        }
        String screen = screenOf(event);
        line.append(" scr=").append(screen);
        log(line.toString());

        boolean onCarPlay = screen.equals(String.valueOf(CARPLAY_SCREEN));
        boolean encoder = code == KEY_TP_ENCODER_X || code == KEY_TP_ENCODER_Y;
        if (encoder && onCarPlay) {
            // Only the terminal mode app's modes send these: switch again on the next press
            modeLost = true;
        }
        boolean fromTouchpad = source == KBD_TOUCHPAD || encoder;
        if (isCursorOn()) {
            if (!onCarPlay) {
                TouchCursor.hide(event.getReceiver());
                return false;
            }
            long sincePress = System.currentTimeMillis() - lastPress;
            boolean pressJoystick = event instanceof JoystickEvent && sincePress >= 0
                    && sincePress < JOYSTICK_AFTER_PRESS_MS;
            return fromTouchpad || pressJoystick;
        }
        if (TouchCursor.isShown()) {
            TouchCursor.hide(event.getReceiver());
        }
        return fromTouchpad && isInjectOn();
    }

    /**
     * Switches the keypanel to the mode in MODE_FILE (map panning in cursor
     * mode) when it changed, or when encoder keys showed the terminal mode app
     * set its own mode again.
     */
    private static void applyMode(ATIPEvent event) {
        long now = System.currentTimeMillis();
        if (now - modeChecked >= FLAG_CHECK_MS || now < modeChecked) {
            modeChecked = now;
            mode = readMode();
        }
        int wanted = mode > 0 ? mode : (isCursorOn() ? MODE_MAP_PANNING : 0);
        if (wanted <= 0 || (wanted == modeApplied && !modeLost)) {
            return;
        }
        modeApplied = wanted;
        modeLost = false;
        try {
            ATIPEventListener receiver = event.getReceiver();
            if (!(receiver instanceof AbstractWindow)) {
                log("M no window for mode " + wanted);
                return;
            }
            AbstractWindow window = (AbstractWindow) receiver;
            KbdService kbd = window.getFramework().getHMIService().getHMITerminal(window.getTerminalID())
                    .getKbdService();
            int before = kbd.getRecognizerMode();
            // Null settings: KbdHandler uses the defaults of the mode, as for the map screen
            boolean set = kbd.setRecognizerMode(wanted, null);
            log("M mode=" + wanted + " set=" + set + " before=" + before);
        } catch (Throwable t) {
            log("M mode " + wanted + " failed: " + t);
        }
    }

    private static int readMode() {
        File file = new File(MODE_FILE);
        if (!file.exists()) {
            return 0;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buffer = new byte[MAX_MODE_FILE_SIZE];
            int length = in.read(buffer);
            if (length <= 0) {
                return 0;
            }
            return Integer.parseInt(new String(buffer, 0, length).trim());
        } catch (Throwable t) {
            return 0;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
                // Nothing to do
            }
        }
    }

    private static int mapX(int x) {
        return map(x, Math.max(padWidth, maxX + 1), windowWidth);
    }

    private static int mapY(int y) {
        return map(y, Math.max(padHeight, maxY + 1), windowHeight);
    }

    private static int map(int value, int range, int size) {
        if (range <= 0) {
            return 0;
        }
        int mapped = value * size / range;
        if (mapped < 0) {
            return 0;
        }
        return mapped >= size ? size - 1 : mapped;
    }

    private static void send(int fingers, int x, int y) {
        Sink target = sink;
        log("I fingers=" + fingers + " x=" + x + " y=" + y + (target == null ? " (no CarPlay)" : ""));
        if (target != null) {
            target.touch(fingers, x, y);
        }
    }

    private static String screenOf(ATIPEvent event) {
        try {
            ATIPEventListener receiver = event.getReceiver();
            if (receiver instanceof IRootWindow) {
                Screen screen = ((IRootWindow) receiver).getCurrentScreen();
                if (screen != null) {
                    return String.valueOf(screen.getID());
                }
            }
        } catch (Throwable t) {
            // Logged as unknown
        }
        return "?";
    }

    /** Appends one line to the log, up to MAX_LOG_SIZE. Never throws. */
    public static synchronized void log(String message) {
        if (logFull) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            if (out == null) {
                if (logOpenFailed != 0 && now - logOpenFailed < LOG_RETRY_MS && now >= logOpenFailed) {
                    return;
                }
                File file = new File(LOG_FILE);
                logSize = file.length();
                out = new FileOutputStream(LOG_FILE, true);
                logOpenFailed = 0;
                write(now + " touchdiag log opened\n");
            }
            if (logSize > MAX_LOG_SIZE) {
                write(now + " log full, delete " + LOG_FILE + " and restart the MMI to log again\n");
                logFull = true;
                out.close();
                out = null;
                return;
            }
            write(now + " " + message + "\n");
        } catch (Throwable t) {
            logOpenFailed = now == 0 ? 1 : now;
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignored) {
                // Already failed
            }
            out = null;
        }
    }

    private static void write(String text) throws java.io.IOException {
        byte[] bytes = text.getBytes();
        out.write(bytes);
        logSize += bytes.length;
    }
}
