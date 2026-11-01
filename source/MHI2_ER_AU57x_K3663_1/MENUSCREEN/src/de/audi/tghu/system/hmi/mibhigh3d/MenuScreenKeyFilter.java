package de.audi.tghu.system.hmi.mibhigh3d;

import de.audi.atip.hmi.event.RunnableEvent;
import de.audi.atip.hmi.event.StateMachineEvent;
import de.esolutions.fw.util.commons.job.BaseJobFilter;
import de.esolutions.fw.util.commons.job.Job;

/**
 * Makes BACK and MENU close the MENUSCREEN screen. New class, not part of
 * lsd.jxe.
 *
 * The state machine is still in the MENU state while the screen shows. There
 * HK_MENU is a transition back into the same state, which the state machine
 * doesn't redraw, and HK_RETURN has no transition at all, so neither key
 * would leave the screen. This filter sits in the HMI event queue (like the
 * firmware's own HMIEventQueue filters) and, only while the MENUSCREEN screen
 * is showing, queues MenuScreen.close right behind the key's event. HK_RETURN
 * is dropped, HK_MENU goes on to the state machine as usual.
 *
 * Every HMI event passes here, so the check is cheap and never throws.
 */
final class MenuScreenKeyFilter extends BaseJobFilter {

    // SMEventSystem
    private static final int HK_RETURN = 121;
    private static final int HK_MENU = 1400;

    public void enqueue(Job job, int priority) {
        Runnable close = null;
        boolean drop = false;
        try {
            Object payload = job.getPayload();
            if (payload instanceof StateMachineEvent) {
                StateMachineEvent event = (StateMachineEvent) payload;
                int id = event.getSMEventID();
                int terminalId = event.getStateMachineID();
                if ((id == HK_RETURN || id == HK_MENU) && MenuScreen.isShowing(terminalId)) {
                    close = closeTask(terminalId, id == HK_RETURN ? "BACK" : "MENU");
                    drop = id == HK_RETURN;
                }
            }
        } catch (Throwable t) {
            MenuScreen.log("key filter failed", t);
        }
        if (!drop) {
            super.enqueue(job, priority);
        }
        if (close != null) {
            // A RunnableEvent runs on the HMI event thread, where the state
            // machine runs. Queued here it comes right after the key's event
            super.enqueue(new Job(new RunnableEvent(close)), priority);
        }
    }

    private static Runnable closeTask(final int terminalId, final String key) {
        return new Runnable() {
            public void run() {
                MenuScreen.close(terminalId, key);
            }
        };
    }
}
