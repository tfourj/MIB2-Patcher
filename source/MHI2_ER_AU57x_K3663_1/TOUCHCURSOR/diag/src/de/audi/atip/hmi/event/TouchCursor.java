package de.audi.atip.hmi.event;

import de.audi.atip.hmi.HMITerminal;
import de.audi.atip.hmi.IRootWindow;
import de.audi.atip.hmi.view.IGUIManager;
import de.audi.atip.timer.Timer;
import de.audi.atip.timer.TimerListener;
import de.audi.tghu.fwhmi.AbstractWindow;
import de.audi.tghu.hmi.view.HWGManager;
import de.audi.tghu.hmi.view.RedrawContext;
import fi.hybrid.a2dg.Graphics2D;
import fi.hybrid.hwg.Bitmap;
import fi.hybrid.hwg.Composite;

/**
 * TOUCHCURSOR diagnostic: draws the cursor over the CarPlay screen. New class,
 * not part of lsd.jxe.
 *
 * Works like the firmware's WidgetFinderDiag markers: a screen-sized composite
 * in front of everything in the HWGManager root composite, holding one small
 * texture (white square, black border). Moving the cursor moves the texture
 * and repaints through IRootWindow.repaint(), the HMI's normal paint call.
 * Only called on the HMI thread, from event dispatch, which is also the
 * thread that paints.
 *
 * The cursor hides itself after HIDE_AFTER_MS without touchpad use. The hide
 * timer goes through TimerSyncer, which posts the timeout as an event, so it
 * also runs on the HMI thread (as for the radio preset popup's hide timer).
 */
final class TouchCursor {

    private static final int SIZE = 18;
    private static final int SCREEN_WIDTH = 800;
    private static final int SCREEN_HEIGHT = 480;
    // HWGManager.getTexture format, as WidgetFinderDiag uses it
    private static final int TEXTURE_FORMAT = 3;
    // In front of everything, as for WidgetFinderDiag
    private static final int DEPTH_FRONT = Integer.MIN_VALUE;
    private static final int FILL_COLOR = 0xF0FFFFFF;
    private static final long HIDE_AFTER_MS = 5000;

    private static HWGManager hwg = null;
    private static Composite composite = null;
    private static Bitmap texture = null;
    private static boolean attached = false;
    private static boolean failed = false;
    private static Timer hideTimer = null;
    private static ATIPEventListener lastReceiver = null;

    private TouchCursor() {
    }

    /** Shows the cursor with its centre at x, y in screen pixels. */
    static void show(ATIPEventListener receiver, int x, int y) {
        if (failed || !(receiver instanceof AbstractWindow)) {
            return;
        }
        try {
            if (texture == null && !create((AbstractWindow) receiver)) {
                return;
            }
            texture.setPosition(x - SIZE / 2, y - SIZE / 2);
            if (!attached) {
                attached = hwg.getRootComposite().addDrawable(composite, DEPTH_FRONT);
                TouchDiag.log("D cursor shown attached=" + attached);
            }
            ((IRootWindow) receiver).repaint();
            lastReceiver = receiver;
            restartHideTimer();
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void restartHideTimer() {
        if (hideTimer == null) {
            TimerListener onTimeout = new TimerListener() {
                public void fireTimer(Timer timer) {
                    hide(lastReceiver);
                }

                public void cancelTimer(Timer timer) {
                }
            };
            hideTimer = new Timer("TouchCursorHideTimer", HIDE_AFTER_MS, true, new TimerSyncer(onTimeout));
        }
        hideTimer.restart();
    }

    static void hide(ATIPEventListener receiver) {
        if (!attached || failed) {
            return;
        }
        try {
            hwg.getRootComposite().removeDrawable(composite);
            attached = false;
            TouchDiag.log("D cursor hidden");
            if (receiver instanceof IRootWindow) {
                ((IRootWindow) receiver).repaint();
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    static boolean isShown() {
        return attached;
    }

    private static boolean create(AbstractWindow window) {
        HMITerminal terminal = window.getFramework().getHMITerminalRegistry().getTerminal(window.getTerminalID());
        IGUIManager manager = terminal.getGUIManager();
        if (!(manager instanceof HWGManager)) {
            TouchDiag.log("D no HWGManager: " + manager);
            failed = true;
            return false;
        }
        hwg = (HWGManager) manager;
        composite = hwg.getComposite(SCREEN_WIDTH, SCREEN_HEIGHT);
        composite.setPosition(0, 0);
        texture = hwg.getTexture(TEXTURE_FORMAT, SIZE, SIZE);
        composite.addDrawable(texture, 0);

        Graphics2D g = Graphics2D.getInstance();
        g.getTransform().setIdentity();
        g.bindTarget(texture);
        g.clear(FILL_COLOR);
        g.setStroke(RedrawContext.BLACK_PAINT);
        g.strokeRect(0, 0, SIZE, SIZE);
        g.releaseTarget();
        TouchDiag.log("D cursor created");
        return true;
    }

    private static void fail(Throwable t) {
        // Stop drawing for the rest of this HMI run, touches still work
        failed = true;
        TouchDiag.log("D cursor failed: " + t);
    }
}
