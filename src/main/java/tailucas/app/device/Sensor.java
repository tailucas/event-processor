package tailucas.app.device;

import org.apache.commons.lang3.text.WordUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import tailucas.app.device.config.InputConfig;

public class Sensor extends Device {

    @JsonIgnore
    protected static Logger log = null;

    @JsonProperty("normal_value")
    protected Integer normalValue;
    @JsonProperty("sample_value")
    protected Double sampleValue;
    @JsonProperty
    protected Boolean active;
    @JsonProperty("input_label")
    protected String inputLabel;
    public Sensor() {
        if (log == null) {
            log = LoggerFactory.getLogger(Sensor.class);
        }
    }
    @JsonIgnore
    public boolean isActive() {
        if (active == null) {
            return false;
        }
        return active.booleanValue();
    }
    public void updateFrom(Device device) {
        deviceId = device.deviceId;
        // device label derived by super class
        location = device.location;
        if (location == null) {
            location = device.inputLocation;
        }
        deviceKey = WordUtils.capitalizeFully(String.format("%s %s", location, inputLabel));
        timestamp = device.timestamp;
        uptime = device.uptime;
        type = device.type;
    }
    @Override
    public String getDeviceType() {
        return Type.SENSOR.name().toLowerCase();
    }
    @Override
    public boolean wouldTriggerOutput(InputConfig deviceConfig) {
        triggerStateDescription = String.format("%s sample value %s outside normal value %s", inputLabel, sampleValue, normalValue);
        return isActive();
    }
    @Override
    public String toString() {
        return "Sensor [normalValue=" + normalValue + ", sampleValue=" + sampleValue + ", active=" + active
                + ", inputLabel=" + inputLabel + "]: " + super.toString();
    }
}
