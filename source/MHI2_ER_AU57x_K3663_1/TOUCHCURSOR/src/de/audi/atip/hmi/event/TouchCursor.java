package de.audi.atip.hmi.event;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;

import de.audi.atip.hmi.IRootWindow;
import de.audi.atip.hmi.KbdService;
import de.audi.atip.hmi.view.Screen;
import de.audi.tghu.fwhmi.AbstractWindow;

/**
 * TOUCHCURSOR: the MMI touchpad moves a cursor on the CarPlay screen, and a
 * tap touches the iPhone screen where the cursor is. New class, not part of
 * lsd.jxe.
 *
 * Every touchpad event (TouchEvent) and key event goes through
 * ATIPEvent.dispatch(), which calls intercept(). While a CarPlay session runs
 * (set by DSICarplaySafeProxy) and the CarPlay screen is showing:
 * - The first touchpad press switches the keypanel to map panning, the way
 *   the map screen does. The terminal mode app's own modes (25, 26) only send
 *   swipes as encoder and zone keys; map panning sends finger moves.
 * - TOUCH_PAD_MOVED_REL steps move the cursor (TouchCursorView), and
 *   TOUCH_PAD_TAPPED is sent to the iPhone as a touchscreen press and release
 *   at the cursor. DSICarplaySafeProxy registers the touchscreen.
 * - Touchpad keys and the joystick event of a press near the pad edge are
 *   swallowed, so the CarPlay focus doesn't move too. The knob works as stock.
 * Everywhere else, and without CarPlay, every event passes as stock.
 *
 * Messages go to /mnt/persist/touchcursor.log.
 */
public final class TouchCursor {

    /** Receives the touches for CarPlay, set by DSICarplaySafeProxy. */
    public interface Sink {
        void touch(int fingers, int x, int y);
    }

    private static final String LOG_FILE = "/mnt/persist/touchcursor.log";
    private static final long MAX_LOG_SIZE = 16384;

    // Screen ID of the CarPlay screen
    private static final int CARPLAY_SCREEN = 240000;
    // KbdService.RECOGNIZERMODE_MAP_PANNING, which sends finger moves
    private static final int MODE_MAP_PANNING = 6;
    // Cursor pixels per touchpad step, as a fraction
    private static final int CURSOR_GAIN_TIMES = 5;
    private static final int CURSOR_GAIN_DIVIDE = 2;
    // The joystick event of a press near the pad edge comes about 20 ms after the press
    private static final long JOYSTICK_AFTER_PRESS_MS = 150;

    // de.audi.atip.hmi.event.TouchEvent IDs
    private static final int TOUCH_PAD_PRESSED = 10904;
    private static final int TOUCH_PAD_TAPPED = 10906;
    private static final int TOUCH_PAD_CHARS_RECOGNIZED = 10908;
    private static final int TOUCH_PAD_APPROACHED = 10909;
    private static final int TOUCH_PAD_ABANDONED = 10910;
    private static final int TOUCH_PAD_MOVED_REL = 10912;

    // de.audi.atip.hmi.event.KeyEvent key source and touchpad encoder keys
    private static final int KBD_TOUCHPAD = 9;
    private static final int KEY_TP_ENCODER_X = 64;
    private static final int KEY_TP_ENCODER_Y = 65;

    private static volatile Sink sink = null;
    private static volatile boolean carPlayActive = false;
    private static volatile int windowWidth = 780;
    private static volatile int windowHeight = 468;
    private static volatile int windowX = 20;
    private static volatile int windowY = 5;
    private static volatile boolean modeSet = false;

    // Only used on the HMI thread
    private static boolean modeLost = false;
    private static boolean lastOnCarPlay = false;
    private static int cursorX = 390;
    private static int cursorY = 234;
    private static long lastPress = 0;
    private static boolean failureLogged = false;

    private TouchCursor() {
    }

    /** Handles the event; true means it must not be dispatched. Never throws. */
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
            if (!failureLogged) {
                failureLogged = true;
                log("event handling failed", t);
            }
        }
        return false;
    }

    public static void setSink(Sink newSink) {
        sink = newSink;
    }

    /** True from CarPlay's startService until its DSI service goes away. */
    public static void setCarPlayActive(boolean active) {
        carPlayActive = active;
        // A new session starts in the terminal mode app's keypanel mode
        modeSet = false;
    }

    /** The CarPlay window from startService: its size is the space touches are sent in. */
    public static void setWindow(int x, int y, int width, int height) {
        windowX = x;
        windowY = y;
        if (width > 0 && height > 0) {
            windowWidth = width;
            windowHeight = height;
        }
    }

    private static boolean onTouch(TouchEvent event) {
        ATIPEventListener receiver = event.getReceiver();
        if (!isCursorScreen(event)) {
            TouchCursorView.hide(receiver);
            return false;
        }
        int id = event.getID();
        if (id == TOUCH_PAD_CHARS_RECOGNIZED || id == TOUCH_PAD_APPROACHED || id == TOUCH_PAD_ABANDONED) {
            return false;
        }
        if (id == TOUCH_PAD_PRESSED) {
            lastPress = System.currentTimeMillis();
            applyMode(event);
            showCursor(receiver);
        } else if (id == TOUCH_PAD_MOVED_REL) {
            cursorX = clamp(cursorX + step(event.getX()), windowWidth);
            cursorY = clamp(cursorY + step(event.getY()), windowHeight);
            showCursor(receiver);
        } else if (id == TOUCH_PAD_TAPPED) {
            touch(1);
            touch(0);
        }
        return true;
    }

    private static boolean onKey(KeyEvent event) {
        if (!isCursorScreen(event)) {
            TouchCursorView.hide(event.getReceiver());
            return false;
        }
        int code = event.getKeyCode();
        boolean encoder = code == KEY_TP_ENCODER_X || code == KEY_TP_ENCODER_Y;
        if (encoder) {
            // Only the terminal mode app's modes send these: switch again on the next press
            modeLost = true;
        }
        long sincePress = System.currentTimeMillis() - lastPress;
        boolean pressJoystick = event instanceof JoystickEvent && sincePress >= 0
                && sincePress < JOYSTICK_AFTER_PRESS_MS;
        return event.getKeySource() == KBD_TOUCHPAD || encoder || pressJoystick;
    }

    /** True while CarPlay runs and its screen is showing. */
    private static boolean isCursorScreen(ATIPEvent event) {
        boolean onCarPlay = carPlayActive && screenOf(event) == CARPLAY_SCREEN;
        if (onCarPlay && !lastOnCarPlay) {
            // Other screens set their own keypanel modes
            modeSet = false;
        }
        lastOnCarPlay = onCarPlay;
        return onCarPlay;
    }

    /** Switches the keypanel to map panning, once per visit or after the terminal mode app reset it. */
    private static void applyMode(ATIPEvent event) {
        if (modeSet && !modeLost) {
            return;
        }
        modeSet = true;
        modeLost = false;
        ATIPEventListener receiver = event.getReceiver();
        if (!(receiver instanceof AbstractWindow)) {
            return;
        }
        AbstractWindow window = (AbstractWindow) receiver;
        KbdService kbd = window.getFramework().getHMIService().getHMITerminal(window.getTerminalID())
                .getKbdService();
        // Null settings: KbdHandler uses the defaults of the mode, as for the map screen
        if (!kbd.setRecognizerMode(MODE_MAP_PANNING, null)) {
            log("keypanel did not take map panning mode");
        }
    }

    private static void showCursor(ATIPEventListener receiver) {
        TouchCursorView.show(receiver, windowX + cursorX, windowY + cursorY);
    }

    private static void touch(int fingers) {
        Sink target = sink;
        if (target != null) {
            target.touch(fingers, cursorX, cursorY);
        }
    }

    /** Cursor pixels for one MOVED_REL value, a signed byte (255 is -1). */
    private static int step(int value) {
        int signed = value > 127 ? value - 256 : value;
        // The pad counts the other way round than the screen on both axes
        return -signed * CURSOR_GAIN_TIMES / CURSOR_GAIN_DIVIDE;
    }

    private static int clamp(int value, int size) {
        if (value < 0) {
            return 0;
        }
        return value >= size ? size - 1 : value;
    }

    private static int screenOf(ATIPEvent event) {
        ATIPEventListener receiver = event.getReceiver();
        if (receiver instanceof IRootWindow) {
            Screen screen = ((IRootWindow) receiver).getCurrentScreen();
            if (screen != null) {
                return screen.getID();
            }
        }
        return -1;
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

    /** Appends a line to /mnt/persist/touchcursor.log. Never throws. */
    static void log(String message) {
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
}
