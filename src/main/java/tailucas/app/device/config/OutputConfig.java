package tailucas.app.device.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public class OutputConfig extends Config {
    @JsonProperty("device_key")
    public String deviceKey;
    @JsonProperty("device_type")
    public String deviceType;
    @JsonProperty("device_label")
    public String deviceLabel;
    @JsonProperty("device_params")
    public String deviceParams;
    public String getDeviceKey() {
        return deviceKey;
    }
    public String getDeviceType() {
        return deviceType;
    }
    public String getDeviceLabel() {
        if (deviceLabel != null) {
            return deviceLabel;
        }
        return deviceKey;
    }
    @Override
    public String toString() {
        return "OutputConfig [device_key=" + deviceKey + ", device_type=" + deviceType + ", device_label="
                + deviceLabel + ", device_params=" + deviceParams + "]";
    }
}
