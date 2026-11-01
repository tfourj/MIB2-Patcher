package org.dsi.ifc.carplay;

/**
 * NAVIGNORE replacement of the firmware's org.dsi.ifc.carplay.AppState.
 *
 * CarPlay tells the HMI which "app states" the iPhone owns: navigation (2),
 * phone (1) and speech (3), each with an owner (1 main unit, 2 device). When a
 * CarPlay route starts, the iPhone takes the navigation state and the MMI ends
 * its own route guidance; the cluster then falls back to the compass.
 *
 * Like the NavActiveIgnore mod of M.I.B., this class reports every app state
 * and owner as unknown (0), so the HMI never sees the iPhone take one over and
 * the MMI navigation keeps running. The fields keep the real values, so the
 * serializer and toString show what the iPhone sent.
 *
 * Same fields, constructors and methods as the firmware class (MU1425), loaded
 * in front of lsd.jxe with -Xbootclasspath/p:.
 */
public class AppState {
    public int appStateID;
    public int owner;
    public int speechMode;

    public AppState() {
        this.appStateID = 0;
        this.owner = 0;
        this.speechMode = 0;
    }

    public AppState(int appStateID, int owner, int speechMode) {
        this.appStateID = appStateID;
        this.owner = owner;
        this.speechMode = speechMode;
    }

    /** Always unknown (0): the HMI never sees which app state the iPhone sent. */
    public int getAppStateID() {
        return 0;
    }

    /** Always unknown (0): the HMI never sees the iPhone own an app state. */
    public int getOwner() {
        return 0;
    }

    public int getSpeechMode() {
        return this.speechMode;
    }

    public String toString() {
        StringBuffer buffer = new StringBuffer(150);
        buffer.append("AppState");
        buffer.append('(');
        buffer.append("appStateID");
        buffer.append('=');
        buffer.append(this.appStateID);
        buffer.append(',');
        buffer.append("owner");
        buffer.append('=');
        buffer.append(this.owner);
        buffer.append(',');
        buffer.append("speechMode");
        buffer.append('=');
        buffer.append(this.speechMode);
        buffer.append(')');
        return buffer.toString();
    }
}
