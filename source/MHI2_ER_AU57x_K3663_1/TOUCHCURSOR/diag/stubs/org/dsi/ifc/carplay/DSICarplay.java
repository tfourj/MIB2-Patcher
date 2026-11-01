package org.dsi.ifc.carplay;

import org.dsi.ifc.base.DSIBase;

/** Compile-only stub. The real interface lives in lsd.jxe. */
public interface DSICarplay extends DSIBase {
    void startService(ServiceConfiguration config);

    void postButtonEvent(int button, int state);

    void postTouchEvent(int source, int fingers, TouchEvent[] events);

    void postRotaryEvent(int ticks);

    void postCharacterEvent(int alternatives, String[] characters);

    void requestModeChange(ResourceRequest[] resources, AppStateRequest[] appStates, String reason);

    void responseUpdateMode(Resource[] resources, AppState[] appStates);

    void responseBTDeactivation();

    void requestUI(int ui);

    void requestNightMode(boolean night);

    void requestSIRIAction(int action);

    void responseUpdateMainAudioType(int type);
}
