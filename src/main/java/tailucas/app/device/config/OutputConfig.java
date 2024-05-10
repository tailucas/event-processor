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
    @JsonProperty("trigger_interval")
    private Integer trigger_interval;
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
    public Integer getTriggerInterval() {
        return trigger_interval;
    }
    @Override
    public String toString() {
        return "OutputConfig [deviceKey=" + deviceKey + ", deviceType=" + deviceType + ", deviceLabel=" + deviceLabel
                + ", deviceParams=" + deviceParams + ", triggerTopic=" + triggerTopic + ", trigger_interval="
                + trigger_interval + "]";
    }
}
