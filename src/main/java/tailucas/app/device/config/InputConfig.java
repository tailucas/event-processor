package tailucas.app.device.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public class InputConfig extends Config {
    @JsonProperty("device_key")
    protected String deviceKey;
    @JsonProperty("device_type")
    protected String deviceType;
    @JsonProperty("device_label")
    protected String deviceLabel;
    @JsonProperty
    protected Boolean customized;
    @JsonProperty("activation_interval")
    protected Integer activationInterval;
    @JsonProperty("auto_schedule")
    protected Boolean autoSchedule;
    @JsonProperty("auto_schedule_enable")
    protected String autoScheduleEnable;
    @JsonProperty("auto_schedule_disable")
    protected String autoScheduleDisable;
    @JsonProperty("device_enabled")
    protected Boolean deviceEnabled;
    @JsonProperty("multi_trigger")
    protected Boolean multiTrigger;
    // bad name, should be called trigger rate
    @JsonProperty("trigger_window")
    protected Integer triggerWindow;
    @JsonProperty("group_name")
    protected String groupName;
    @JsonProperty("info_notify")
    protected Boolean infoNotify;
    public String getDeviceKey() throws IllegalStateException {
        if (deviceKey == null) {
            throw new IllegalStateException(String.format("No device key present on %s", toString()));
        }
        return deviceKey;
    }
    public String getDeviceType() {
        return deviceType;
    }
    public String getDeviceLabel() {
        return deviceLabel;
    }
    public boolean isCustomized() {
        if (customized == null) {
            return false;
        }
        return customized.booleanValue();
    }
    public Integer getActivationInterval() {
        return activationInterval;
    }
    public boolean isAutoSchedule() {
        if (autoSchedule == null) {
            return false;
        }
        return autoSchedule.booleanValue();
    }
    public String getAutoScheduleEnable() {
        return autoScheduleEnable;
    }
    public String getAutoScheduleDisable() {
        return autoScheduleDisable;
    }
    public boolean isDeviceEnabled() {
        if (deviceEnabled == null) {
            return false;
        }
        return deviceEnabled.booleanValue();
    }
    public boolean isMultiTrigger() {
        if (multiTrigger == null) {
            return false;
        }
        return multiTrigger.booleanValue();
    }
    public Integer getTriggerWindow() {
        return triggerWindow;
    }
    public String getGroupName() {
        return groupName;
    }
    public boolean isInfoNotify() {
        if (infoNotify == null) {
            return false;
        }
        return infoNotify.booleanValue();
    }
    @Override
    public String toString() {
        return "InputConfig [deviceKey=" + deviceKey + ", deviceType=" + deviceType + ", deviceLabel=" + deviceLabel
                + ", customized=" + customized + ", activationInterval=" + activationInterval + ", autoSchedule="
                + autoSchedule + ", autoScheduleEnable=" + autoScheduleEnable + ", autoScheduleDisable="
                + autoScheduleDisable + ", deviceEnabled=" + deviceEnabled + ", multiTrigger=" + multiTrigger
                + ", triggerWindow=" + triggerWindow + ", groupName=" + groupName + ", infoNotify=" + infoNotify + "]";
    }
}