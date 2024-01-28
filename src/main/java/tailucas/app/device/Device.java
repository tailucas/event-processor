package tailucas.app.device;

import java.time.Instant;

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
    public String getDeviceKey() {
        return device_key;
    }
    public Instant getTimestamp() {
        if (timestamp == null) {
            return Instant.now();
        }
        return Instant.ofEpochSecond(timestamp.longValue());
    }
    public long getUptimeSeconds() {
        return Double.valueOf(timestamp - uptime).longValue();
    }
    @Override
    public String toString() {
        return "Device [" + getDeviceKey() + "]";
    }
    @JsonIgnore
    public Object input_1;
    @JsonIgnore
    public Object input_2;
    @JsonIgnore
    public Object input_3;
    @JsonIgnore
    public Object input_4;

    @Override
    public boolean mustTriggerOutput(Config deviceConfig) {
        throw new UnsupportedOperationException("Unimplemented method 'triggerOutput'");
    }
}
