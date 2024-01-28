package tailucas.app.device.config;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class InputConfig extends Config {
    public String device_key;
    public String device_type;
    public String device_label;
    public Boolean customized;
    public Integer activation_interval;
    public Boolean auto_schedule;
    public String auto_schedule_enable;
    public String auto_schedule_disable;
    public Boolean device_enabled;
    public Boolean multi_trigger;
    public String group_name;
    public Boolean info_notify;
    @JsonIgnore
    public String getDeviceLabel() {
        if (device_label != null) {
            return device_label;
        }
        return device_key;
    }
    @Override
    public String toString() {
        return "InputConfig [device_key=" + device_key + ", device_type=" + device_type + ", device_label=" + device_label
                + ", customized=" + customized + ", activation_interval=" + activation_interval + ", auto_schedule="
                + auto_schedule + ", auto_schedule_enable=" + auto_schedule_enable + ", auto_schedule_disable="
                + auto_schedule_disable + ", device_enabled=" + device_enabled + ", multi_trigger=" + multi_trigger
                + ", group_name=" + group_name + ", info_notify=" + info_notify + "]";
    }
}
