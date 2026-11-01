package de.audi.app.terminalmode.dsi.carplay;

import de.audi.app.terminalmode.dsi.NotificationProxy;
import de.audi.atip.hmi.event.TouchDiag;
import de.audi.atip.log.LogChannel;
import org.dsi.ifc.base.DSIBase;
import org.dsi.ifc.base.DSIListener;
import org.dsi.ifc.carplay.AppState;
import org.dsi.ifc.carplay.AppStateRequest;
import org.dsi.ifc.carplay.DSICarplay;
import org.dsi.ifc.carplay.DSICarplayListener;
import org.dsi.ifc.carplay.Resource;
import org.dsi.ifc.carplay.ResourceRequest;
import org.dsi.ifc.carplay.ServiceConfiguration;
import org.dsi.ifc.carplay.TouchEvent;

/**
 * TOUCHCURSOR diagnostic replacement of the firmware's
 * de.audi.app.terminalmode.dsi.carplay.DSICarplaySafeProxy, the terminal mode
 * app's handle on the CarPlay DSI service (dio_manager).
 *
 * Same as the firmware class (MU1425), loaded in front of lsd.jxe with
 * -Xbootclasspath/p:, plus:
 * - startService, postButtonEvent, postRotaryEvent and postTouchEvent are
 *   written to the TouchDiag log, so the log shows what the touchpad turns
 *   into on the way to the iPhone.
 * - While /ramdisk/touchdiag.inject exists when CarPlay starts, startService
 *   adds INPUTFEATURE_HIGHFIDELITYTOUCH, so dio_manager also registers a
 *   touchscreen with the iPhone (stock: knob and touchpad only).
 * - It gives TouchDiag a sink that sends touchscreen touches.
 */
public class DSICarplaySafeProxy extends NotificationProxy implements DSICarplaySafe {

    // org.dsi.ifc.carplay.DSICarplay constants
    private static final int TOUCHSOURCE_TOUCHSCREEN = 1;
    private static final int INPUTFEATURE_HIGHFIDELITYTOUCH = 4;
    private static final int SCREENRESOLUTION_CUSTOM = 3;

    private final DSICarplay nullService;
    private volatile DSICarplay dsi;
    private final DSICarplayListener dsiListener;

    public DSICarplaySafeProxy(LogChannel log, DSICarplayListener dsiListener) {
        super();
        this.dsiListener = dsiListener;
        this.nullService = new NullDSICarplay(log);
        this.dsi = this.nullService;
        this.setNotification(new int[] {3, 4, 5, 7, 9, 10, 12}, this.dsiListener);
        TouchDiag.setSink(new TouchDiag.Sink() {
            public void touch(int fingers, int x, int y) {
                DSICarplaySafeProxy.this.dsi.postTouchEvent(TOUCHSOURCE_TOUCHSCREEN, fingers,
                        new TouchEvent[] {new TouchEvent(x, y)});
            }
        });
        TouchDiag.log("C proxy created");
    }

    protected synchronized void addDSIService(DSICarplay dsi) {
        this.dsi = dsi;
        this.newDSIAvailable();
        TouchDiag.log("C dsi added");
    }

    protected synchronized void removeDSIService() {
        this.dsi = this.nullService;
        TouchDiag.log("C dsi removed");
    }

    protected DSIBase getDSI() {
        return this.dsi;
    }

    protected DSIListener getDSIListener() {
        return this.dsiListener;
    }

    public void startService(ServiceConfiguration config) {
        try {
            if (TouchDiag.isInjectOn()) {
                config.inputFeatures |= INPUTFEATURE_HIGHFIDELITYTOUCH;
            }
            if (config.screenResolution == SCREENRESOLUTION_CUSTOM) {
                TouchDiag.setWindow(config.xResolution, config.yResolution);
            }
            TouchDiag.setPad(config.touchpadXResolution, config.touchpadYResolution);
            TouchDiag.log("C startService inputFeatures=" + config.inputFeatures
                    + " res=" + config.screenResolution + " window=" + config.xResolution + "x" + config.yResolution
                    + " offset=" + config.xOffset + "," + config.yOffset
                    + " pad=" + config.touchpadXResolution + "x" + config.touchpadYResolution
                    + " physical=" + config.physicalDisplayWidth + "x" + config.physicalDisplayHeight);
        } catch (Throwable t) {
            // Start CarPlay as configured
        }
        this.dsi.startService(config);
    }

    public void postButtonEvent(int button, int state) {
        TouchDiag.log("C button " + button + " state=" + state);
        this.dsi.postButtonEvent(button, state);
    }

    public void postTouchEvent(int source, int fingers, TouchEvent[] events) {
        TouchDiag.log("C touch source=" + source + " fingers=" + fingers
                + " events=" + (events == null ? -1 : events.length));
        this.dsi.postTouchEvent(source, fingers, events);
    }

    public void postRotaryEvent(int ticks) {
        TouchDiag.log("C rotary " + ticks);
        this.dsi.postRotaryEvent(ticks);
    }

    public void postCharacterEvent(int alternatives, String[] characters) {
        this.dsi.postCharacterEvent(alternatives, characters);
    }

    public void requestModeChange(ResourceRequest[] resources, AppStateRequest[] appStates, String reason) {
        this.dsi.requestModeChange(resources, appStates, reason);
    }

    public void responseUpdateMode(Resource[] resources, AppState[] appStates) {
        this.dsi.responseUpdateMode(resources, appStates);
    }

    public void responseBTDeactivation() {
        this.dsi.responseBTDeactivation();
    }

    public void requestUI(int ui) {
        this.dsi.requestUI(ui);
    }

    public void requestNightMode(boolean night) {
        this.dsi.requestNightMode(night);
    }

    public void requestSIRIAction(SiriAction action) {
        this.dsi.requestSIRIAction(action.getDSIConstantValue());
    }

    public void responseUpdateMainAudioType(int type) {
        this.dsi.responseUpdateMainAudioType(type);
    }
}
