package de.audi.tghu.fwhmi;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.HMIService;
import de.audi.atip.hmi.IRootWindow;
import de.audi.atip.hmi.event.EventDispatcher;
import de.audi.atip.hmi.event.KeyEvent;

/**
 * Splits the steering wheel voice (PTT) button into a short and a long press.
 * New class, not part of lsd.jxe. Used by the patched SDSManagerGUI.
 *
 * While a function is set for the voice button, a press is held back until
 * the button is released:
 * - released before the key panel reports a long press: the chosen key is
 *   pressed and released instead (for example "next track")
 * - long press reported first: the held press goes on to voice control as
 *   usual, followed by the long press, so starting Siri or the phone's voice
 *   assistant still works
 * - double press: the chosen key again, so a quick double tap skips twice
 *
 * The chosen key is posted as a normal KeyEvent with the voice button's
 * terminal and key source, the same way KbdListener posts real key events,
 * so every app handles it like the real button.
 */
final class JokerBtnVoiceKey {

    // de.audi.atip.hmi.event.KeyEvent ids
    private static final int KEY_PRESSED = 10401;
    private static final int KEY_RELEASED = 10402;

    private static final int IDLE = 0;
    private static final int HELD = 1;
    private static final int FORWARDED = 2;

    private final IFrameworkAccess framework;
    private final JokerBtnConfig config;

    private int state = IDLE;
    private KeyEvent heldPress = null;
    private int heldKey = JokerBtnConfig.STOCK;

    JokerBtnVoiceKey(IFrameworkAccess framework, JokerBtnConfig config) {
        this.framework = framework;
        this.config = config;
    }

    /** Voice button pressed. True if the press is held back; false means handle it as usual. */
    boolean holdPress(KeyEvent event) {
        int key = this.config.getVoiceKey();
        if (key == JokerBtnConfig.STOCK) {
            this.state = FORWARDED;
            return false;
        }
        this.state = HELD;
        this.heldPress = event;
        this.heldKey = key;
        return true;
    }

    /** Long press reported. Returns the held press so it can be handled first, or null. */
    KeyEvent takeHeldPress() {
        if (this.state != HELD) {
            return null;
        }
        KeyEvent press = this.heldPress;
        this.state = FORWARDED;
        this.heldPress = null;
        return press;
    }

    /** Double press reported. True if it was turned into the chosen key. */
    boolean doublePress(KeyEvent event) {
        int key = this.config.getVoiceKey();
        if (key == JokerBtnConfig.STOCK) {
            return false;
        }
        // The release that follows has no press of its own and is dropped
        this.state = IDLE;
        pressKey(key, event);
        return true;
    }

    /** Voice button released. True if the release was used here; false means handle it as usual. */
    boolean release(KeyEvent event) {
        int state = this.state;
        this.state = IDLE;
        if (state == HELD) {
            this.heldPress = null;
            pressKey(this.heldKey, event);
            return true;
        }
        if (state == FORWARDED) {
            return false;
        }
        // No press was passed on for this release
        return this.config.getVoiceKey() != JokerBtnConfig.STOCK;
    }

    /** Posts a press and a release of the key. Never throws. */
    private void pressKey(int key, KeyEvent source) {
        try {
            HMIService hmi = this.framework.getHMIService();
            int terminalId = source.getTerminalID();
            int keySource = source.getKeySource();
            IRootWindow root = hmi.getRootWindow(terminalId);
            EventDispatcher dispatcher = hmi.getEventDispatcher();
            long now = this.framework.getMonotonicTime();
            dispatcher.postEvent(new KeyEvent(root, KEY_PRESSED, now, key, terminalId, keySource));
            dispatcher.postEvent(new KeyEvent(root, KEY_RELEASED, now, key, terminalId, keySource));
        } catch (Throwable t) {
            // A lost key press must not break key handling
        }
    }
}
