package tailucas.app.device.config;

import java.util.List;

public class HAConfig {
    public String name;
    public String unique_id;
    public String state_topic;
    public String device_class;
    public String command_topic;
    public String icon;
    public String json_attributes_topic;
    public List<String> options;
    public String min;
    public String max;
    public String mode;
    public String availability_topic;
    public String payload_available;
    public String payload_not_available;
    public String state_class;
    public String supported_features;
    public String unit_of_measurement;
    public String value_template;
    public HADevice device;
    public class HADevice {
        public List<String> ids;
        public String name;
        public String mf;
        public String mdl;
        @Override
        public String toString() {
            return "HADevice [ids=" + ids + ", name=" + name + ", mf=" + mf + ", mdl=" + mdl + "]";
        }
    }
    @Override
    public String toString() {
        return "HAConfig [name=" + name + ", unique_id=" + unique_id + ", state_topic=" + state_topic
                + ", device_class=" + device_class + ", command_topic=" + command_topic + ", icon=" + icon
                + ", json_attributes_topic=" + json_attributes_topic + ", options=" + options + ", min=" + min
                + ", max=" + max + ", mode=" + mode + ", availability_topic=" + availability_topic
                + ", payload_available=" + payload_available + ", payload_not_available=" + payload_not_available
                + ", state_class=" + state_class + ", supported_features=" + supported_features
                + ", unit_of_measurement=" + unit_of_measurement + ", value_template=" + value_template + ", device="
                + device + "]";
    }
}
