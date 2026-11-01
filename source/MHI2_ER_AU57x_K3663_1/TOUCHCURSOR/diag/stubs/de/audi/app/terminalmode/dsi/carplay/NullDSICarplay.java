package de.audi.app.terminalmode.dsi.carplay;

import de.audi.atip.log.LogChannel;
import org.dsi.ifc.carplay.AppState;
import org.dsi.ifc.carplay.AppStateRequest;
import org.dsi.ifc.carplay.DSICarplay;
import org.dsi.ifc.carplay.Resource;
import org.dsi.ifc.carplay.ResourceRequest;
import org.dsi.ifc.carplay.ServiceConfiguration;
import org.dsi.ifc.carplay.TouchEvent;

/** Compile-only stub. The real class lives in lsd.jxe. */
public class NullDSICarplay implements DSICarplay {
    public NullDSICarplay(LogChannel log) {
    }

    public void startService(ServiceConfiguration config) {
    }

    public void postButtonEvent(int button, int state) {
    }

    public void postTouchEvent(int source, int fingers, TouchEvent[] events) {
    }

    public void postRotaryEvent(int ticks) {
    }

    public void postCharacterEvent(int alternatives, String[] characters) {
    }

    public void requestModeChange(ResourceRequest[] resources, AppStateRequest[] appStates, String reason) {
    }

    public void responseUpdateMode(Resource[] resources, AppState[] appStates) {
    }

    public void responseBTDeactivation() {
    }

    public void requestUI(int ui) {
    }

    public void requestNightMode(boolean night) {
    }

    public void requestSIRIAction(int action) {
    }

    public void responseUpdateMainAudioType(int type) {
    }
}
