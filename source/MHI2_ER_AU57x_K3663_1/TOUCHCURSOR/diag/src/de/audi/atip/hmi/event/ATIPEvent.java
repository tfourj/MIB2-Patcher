package de.audi.atip.hmi.event;

/**
 * TOUCHCURSOR diagnostic replacement of the firmware's
 * de.audi.atip.hmi.event.ATIPEvent, the base class of every HMI event.
 *
 * HMIEventDispatcher runs dispatch() on the HMI thread for every event. Before
 * passing an event on, this version hands it to TouchDiag, which logs touchpad
 * and key events and can swallow them (see TouchDiag). Everything else is the
 * same as the firmware class (MU1425), loaded in front of lsd.jxe with
 * -Xbootclasspath/p:.
 */
public class ATIPEvent {
    public static final int ATIP_FIRST_ID = 10001;

    private ATIPEventListener receiver;
    private ATIPEventListener[] receivers;
    private int id;
    private long posted;
    private boolean consumed;

    protected ATIPEvent(ATIPEventListener receiver, int id) {
        this.receiver = receiver;
        this.id = id;
    }

    protected ATIPEvent(ATIPEventListener[] receivers, int id) {
        this.receivers = receivers;
        this.id = id;
    }

    public void dispatch() {
        if (TouchDiag.intercept(this)) {
            return;
        }
        if (this.receiver != null) {
            this.receiver.processEvent(this);
        } else if (this.receivers != null) {
            int count = this.receivers.length;
            for (int i = 0; i < count; i++) {
                if (this.receivers[i] != null) {
                    this.receivers[i].processEvent(this);
                }
            }
        }
    }

    public ATIPEventListener getReceiver() {
        return this.receiver;
    }

    public int getID() {
        return this.id;
    }

    public long getPosted() {
        return this.posted;
    }

    public void setPostedNow(long posted) {
        this.posted = posted;
    }

    public void consume() {
        this.consumed = true;
    }

    public boolean isConsumed() {
        return this.consumed;
    }

    public void setConsumed(boolean consumed) {
        this.consumed = consumed;
    }
}
