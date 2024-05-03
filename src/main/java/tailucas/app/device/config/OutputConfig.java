package tailucas.app.device.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public class OutputConfig extends Config {
    @JsonProperty("device_key")
    private String deviceKey;
    @JsonProperty("device_type")
    private String deviceType;
    @JsonProperty("device_label")
    private String deviceLabel;
    @JsonProperty("device_params")
    private String deviceParams;
    @JsonProperty("trigger_topic")
    private String triggerTopic;
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
    public String getDeviceParams() {
        return deviceParams;
    }
    public String getTriggerTopic() {
        return triggerTopic;
    }
    @Override
    public String toString() {
        return "OutputConfig [device_key=" + deviceKey + ", device_type=" + deviceType + ", device_label="
                + deviceLabel + ", device_params=" + deviceParams + "]";
    }
}
