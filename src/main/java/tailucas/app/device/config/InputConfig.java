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
    public Boolean getCustomized() {
        return customized;
    }
    public Integer getActivationInterval() {
        return activationInterval;
    }
    public Boolean getAutoSchedule() {
        return autoSchedule;
    }
    public String getAutoScheduleEnable() {
        return autoScheduleEnable;
    }
    public String getAutoScheduleDisable() {
        return autoScheduleDisable;
    }
    public Boolean isDeviceEnabled() {
        return deviceEnabled;
    }
    public Boolean getMultiTrigger() {
        return multiTrigger;
    }
    public String getGroupName() {
        return groupName;
    }
    public Boolean getInfoNotify() {
        return infoNotify;
    }
    @Override
    public String toString() {
        return "InputConfig [device_key=" + deviceKey + ", device_type=" + deviceType + ", device_label=" + deviceLabel
                + ", customized=" + customized + ", activation_interval=" + activationInterval + ", auto_schedule="
                + autoSchedule + ", auto_schedule_enable=" + autoScheduleEnable + ", auto_schedule_disable="
                + autoScheduleDisable + ", device_enabled=" + deviceEnabled + ", multi_trigger=" + multiTrigger
                + ", group_name=" + groupName + ", info_notify=" + infoNotify + "]";
    }
}