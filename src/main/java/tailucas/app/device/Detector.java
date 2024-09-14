package tailucas.app.device;


import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;

import tailucas.app.device.config.InputConfig;

public class Detector extends Device {

    @JsonIgnore
    protected static final Set<String> activeStates = Set.of(
        "active",
        "on",
        "triggered");

    public Detector(Device device) {
        super();
        setFieldsFrom(device);
    }
    @Override
    public String getDeviceType() {
        return Type.DETECTOR.name().toLowerCase();
    }
    @Override
    public boolean wouldTriggerOutput(InputConfig deviceConfig) {
        if (state != null && activeStates.contains(state.toLowerCase())) {
            triggerStateDescription = String.format("%s is in an active state %s", getDeviceLabel(), state);
            return true;
        }
        return false;
    }
    @Override
    public Type getType() {
        return Type.DETECTOR;
    }
    @Override
    public String toString() {
        return "Detector [" + getDeviceLabel() + "]";
    }
}
