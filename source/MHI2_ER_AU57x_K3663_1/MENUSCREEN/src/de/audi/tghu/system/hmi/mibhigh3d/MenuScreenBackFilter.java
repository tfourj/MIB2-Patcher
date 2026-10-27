package de.audi.tghu.system.hmi.mibhigh3d;

import de.audi.atip.hmi.HMIService;
import de.audi.atip.hmi.event.StateMachineEvent;
import de.esolutions.fw.util.commons.job.BaseJobFilter;
import de.esolutions.fw.util.commons.job.Job;

/**
 * Makes BACK close the MENUSCREEN screen. New class, not part of lsd.jxe.
 *
 * The BACK key never reaches the screen: ApplicationManagerGUI turns it into
 * the state machine event HK_RETURN. The state machine is still in the MENU
 * state while the screen shows, and MENU has no HK_RETURN transition, so BACK
 * would do nothing. This filter sits in the HMI event queue (like the
 * firmware's own HMIEventQueue filters) and, only while the MENUSCREEN screen
 * is showing, swaps HK_RETURN for HK_MENU, which brings the main menu back.
 *
 * Every HMI event passes here, so the check is cheap and never throws.
 */
final class MenuScreenBackFilter extends BaseJobFilter {

    // SMEventSystem
    private static final int HK_RETURN = 121;
    private static final int HK_MENU = 1400;

    private final HMIService hmi;

    MenuScreenBackFilter(HMIService hmi) {
        this.hmi = hmi;
    }

    public void enqueue(Job job, int priority) {
        try {
            Object payload = job.getPayload();
            if (payload instanceof StateMachineEvent) {
                StateMachineEvent event = (StateMachineEvent) payload;
                if (event.getSMEventID() == HK_RETURN && isMenuScreenShowing(event.getStateMachineID())) {
                    backToMenu(event.getStateMachineID());
                    return;
                }
            }
        } catch (Throwable t) {
            MenuScreen.log("back filter failed", t);
        }
        super.enqueue(job, priority);
    }

    private boolean isMenuScreenShowing(int terminalId) {
        int screenId = MenuScreen.screenId();
        return screenId >= 0 && terminalId == MenuScreen.getTerminalId() && this.hmi.getCurrentScreenID() == screenId;
    }

    // Posted from its own thread: this runs inside the event queue, which a
    // new event from here would enter again
    private void backToMenu(final int terminalId) {
        MenuScreen.log("BACK on the screen, going back to MENU");
        Thread poster = new Thread(new Runnable() {
            public void run() {
                try {
                    hmi.fireSMEvent(terminalId, HK_MENU);
                } catch (Throwable t) {
                    MenuScreen.log("BACK to MENU failed", t);
                }
            }
        }, "MenuScreenBack");
        poster.setDaemon(true);
        poster.start();
    }
}
