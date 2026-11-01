package de.audi.atip.hmi.event;

import java.io.File;
import java.io.FileOutputStream;

import de.audi.atip.hmi.IRootWindow;
import de.audi.atip.hmi.view.Screen;

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
 */
public final class TouchDiag {

    /** Receives the touches for CarPlay, set by DSICarplaySafeProxy. */
    public interface Sink {
        void touch(int fingers, int x, int y);
    }

    private static final String LOG_FILE = "/ramdisk/touchdiag.log";
    private static final String INJECT_FLAG = "/ramdisk/touchdiag.inject";
    private static final long MAX_LOG_SIZE = 1024 * 1024;
    private static final long FLAG_CHECK_MS = 1000;
    private static final long LOG_RETRY_MS = 10000;
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
    private static volatile int padWidth = 0;
    private static volatile int padHeight = 0;
    private static volatile boolean inject = false;
    private static volatile long flagChecked = 0;

    // Only used on the HMI thread
    private static int maxX = 0;
    private static int maxY = 0;
    private static boolean fingerDown = false;
    private static long lastRelease = 0;

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

        StringBuffer line = new StringBuffer(80);
        line.append("T ").append(id).append(" code=").append(event.getCode());
        line.append(" x=").append(x).append(" y=").append(y).append(" f=").append(event.getFingerID());
        line.append(" scr=").append(screenOf(event));
        log(line.toString());

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
            send(1, tx, ty);
        } else if (id == TOUCH_PAD_MOVED_ABS) {
            if (fingerDown) {
                send(1, tx, ty);
            }
        } else if (id == TOUCH_PAD_RELEASED) {
            if (fingerDown) {
                send(0, tx, ty);
            }
            fingerDown = false;
            lastRelease = System.currentTimeMillis();
        } else if (id == TOUCH_PAD_TAPPED) {
            long sinceRelease = System.currentTimeMillis() - lastRelease;
            if (!fingerDown && (sinceRelease < 0 || sinceRelease > TAP_AFTER_RELEASE_MS)) {
                send(1, tx, ty);
                send(0, tx, ty);
            }
        }
        return true;
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
        line.append(" scr=").append(screenOf(event));
        log(line.toString());

        boolean fromTouchpad = source == KBD_TOUCHPAD || code == KEY_TP_ENCODER_X || code == KEY_TP_ENCODER_Y;
        return fromTouchpad && isInjectOn();
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
