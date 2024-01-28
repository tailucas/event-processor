package tailucas.app.device.config;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class OutputConfig extends Config {
    public String device_key;
    public String device_type;
    public String device_label;
    public String device_params;
    @JsonIgnore
    public String getDeviceKey() {
        return device_key;
    }
    @JsonIgnore
    public String getDeviceLabel() {
        if (device_label != null) {
            return device_label;
        }
        return device_key;
    }
    @Override
    public String toString() {
        return "OutputConfig [device_key=" + device_key + ", device_type=" + device_type + ", device_label="
                + device_label + ", device_params=" + device_params + "]";
    }
}
