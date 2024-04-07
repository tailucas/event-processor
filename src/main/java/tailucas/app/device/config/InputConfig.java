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
    @JsonProperty("auto_schedule")
    protected Boolean autoSchedule;
    @JsonProperty("auto_schedule_enable")
    protected String autoScheduleEnable;
    @JsonProperty("auto_schedule_disable")
    protected String autoScheduleDisable;
    @JsonProperty("device_enabled")
    protected Boolean deviceEnabled;
    @JsonProperty("trigger_latch_duration")
    protected Integer triggerLatchDuration;
    @JsonProperty("multi_trigger_rate")
    protected Integer multiTriggerRate;
    @JsonProperty("multi_trigger_interval")
    protected Integer multiTriggerInterval;
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
    public String getGroupName() {
        return groupName;
    }
    public boolean isInfoNotify() {
        if (infoNotify == null) {
            return false;
        }
        return infoNotify.booleanValue();
    }
    public Integer getTriggerLatchDuration() {
        return triggerLatchDuration;
    }
    public Integer getMultiTriggerRate() {
        return multiTriggerRate;
    }
    public Integer getMultiTriggerInterval() {
        return multiTriggerInterval;
    }
    @Override
    public String toString() {
        return "InputConfig [deviceKey=" + deviceKey + ", deviceType=" + deviceType + ", deviceLabel=" + deviceLabel
                + ", customized=" + customized + ", autoSchedule=" + autoSchedule + ", autoScheduleEnable="
                + autoScheduleEnable + ", autoScheduleDisable=" + autoScheduleDisable + ", deviceEnabled="
                + deviceEnabled + ", triggerLatchDuration=" + triggerLatchDuration
                + ", multiTriggerRate=" + multiTriggerRate
                + ", multiTriggerInterval=" + multiTriggerInterval + ", groupName=" + groupName + ", infoNotify="
                + infoNotify + "]";
    }
}
