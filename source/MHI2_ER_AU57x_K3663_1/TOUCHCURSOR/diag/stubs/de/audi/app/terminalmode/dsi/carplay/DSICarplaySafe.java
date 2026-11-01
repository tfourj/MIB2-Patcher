package de.audi.app.terminalmode.dsi.carplay;

import org.dsi.ifc.carplay.AppState;
import org.dsi.ifc.carplay.AppStateRequest;
import org.dsi.ifc.carplay.Resource;
import org.dsi.ifc.carplay.ResourceRequest;
import org.dsi.ifc.carplay.ServiceConfiguration;
import org.dsi.ifc.carplay.TouchEvent;

/** Compile-only stub. The real interface lives in lsd.jxe. */
public interface DSICarplaySafe {
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

    void requestSIRIAction(SiriAction action);

    void responseUpdateMainAudioType(int type);
}
