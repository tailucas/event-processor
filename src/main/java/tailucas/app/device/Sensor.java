package tailucas.app.device;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import tailucas.app.device.config.Config;

public class Sensor extends Device {
    @JsonProperty("normal_value")
    protected Integer normalValue;
    @JsonProperty("sample_value")
    protected Double sampleValue;
    @JsonProperty
    protected Boolean active;
    @JsonProperty("input_label")
    protected String inputLabel;
    @JsonIgnore
    public boolean isActive() {
        return active;
    }
    public void updateFrom(Device device) {
        this.deviceId = device.deviceId;
        this.inputLocation = device.inputLocation;
        this.timestamp = device.timestamp;
        this.uptime = device.uptime;
        this.type = device.type;
    }
    @Override
    public String getDeviceKey() {
        return StringUtils.capitalize(String.format("%s %s", inputLocation, inputLabel));
    }
    @Override
    public boolean mustTriggerOutput(Config deviceConfig) {
        return true;
    }
    @Override
    public String toString() {
        return "Sensor [normalValue=" + normalValue + ", sampleValue=" + sampleValue + ", active=" + active
                + ", inputLabel=" + inputLabel + "]: " + super.toString();
    }
}
