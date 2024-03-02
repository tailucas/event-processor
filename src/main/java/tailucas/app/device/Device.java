package tailucas.app.device;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import tailucas.app.device.config.Config;

public class Device implements Trigger {

    public enum Type {
        BASE,
        CAMERA,
        METER,
        SENSOR
    }

    public String device_key;
    public String device_id;
    public String device_label;
    public byte[] image;
    public String location;
    public String input_location;
    public String name;
    public Double sample_value;
    public String storage_url;
    public String storage_path;
    public String type;
    public Double timestamp;
    public Double uptime;

    public Device() { }

    @JsonIgnore
    private Config config;

    @JsonIgnore
    public Config getConfig() {
        return config;
    }

    @JsonIgnore
    public void setConfig(Config config) {
        this.config = config;
    }

    @JsonIgnore
    public String getDeviceKey() {
        return device_key;
    }
    @JsonIgnore
    public String getDeviceLabel() {
        if (device_label != null) {
            return device_label;
        }
        return getDeviceKey();
    }
    @JsonIgnore
    public Instant getTimestamp() {
        if (timestamp == null) {
            return Instant.now();
        }
        return Instant.ofEpochSecond(timestamp.longValue());
    }
    @JsonIgnore
    public long getUptimeSeconds() {
        return Double.valueOf(timestamp - uptime).longValue();
    }
    @JsonIgnore
    public boolean mustTriggerOutput(Config deviceConfig) {
        throw new UnsupportedOperationException("Unimplemented method 'mustTriggerOutput'");
    }
    @JsonIgnore
    public Instant lastTriggered() {
        throw new UnsupportedOperationException("Unimplemented method 'lastTriggered'");
    }
    @JsonIgnore
    public List<Device> triggerGroup() {
        throw new UnsupportedOperationException("Unimplemented method 'triggerGroup'");
    }
    @Override
    public String toString() {
        return "Device [" + getDeviceLabel() + "]";
    }
    /*
     * Legacy message fields not used here
     */
    @JsonIgnore
    public Object input_1;
    @JsonIgnore
    public Object input_2;
    @JsonIgnore
    public Object input_3;
    @JsonIgnore
    public Object input_4;
}
