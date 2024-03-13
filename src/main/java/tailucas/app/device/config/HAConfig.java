package tailucas.app.device.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class HAConfig extends Config {
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
    @JsonIgnore
    protected String mqttTopic;
    public class HADevice {
        public List<String> ids;
        public String name;
        public String mf;
        public String mdl;
        @Override
        public String toString() {
            return "HADevice [ids=" + ids + ", name=" + name + ", mf=" + mf + ", mdl=" + mdl + "]";
        }
        public List<String> getIds() {
            return ids;
        }
        public String getName() {
            return name;
        }
        public String getMf() {
            return mf;
        }
        public String getMdl() {
            return mdl;
        }
    }
    @JsonIgnore
    public void setMqttTopic(String mqttTopic) {
        this.mqttTopic = mqttTopic;
    }

    public String getName() {
        return name;
    }
    public String getUniqueId() {
        return unique_id;
    }
    public String getStateTopic() {
        return state_topic;
    }
    public String getDeviceClass() {
        return device_class;
    }
    public String getCommandTopic() {
        return command_topic;
    }
    public String getIcon() {
        return icon;
    }
    public String getJsonAttributesTopic() {
        return json_attributes_topic;
    }
    public List<String> getOptions() {
        return options;
    }
    public String getMin() {
        return min;
    }
    public String getMax() {
        return max;
    }
    public String getMode() {
        return mode;
    }
    public String getAvailabilityTopic() {
        return availability_topic;
    }
    public String getPayloadAvailable() {
        return payload_available;
    }
    public String getPayloadNotAvailable() {
        return payload_not_available;
    }
    public String getStateClass() {
        return state_class;
    }
    public String getSupportedFeatures() {
        return supported_features;
    }
    public String getUnitOfMeasurement() {
        return unit_of_measurement;
    }
    public String getValueTemplate() {
        return value_template;
    }
    public HADevice getDevice() {
        return device;
    }
    public String getMqttTopic() {
        return mqttTopic;
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
                + device + ", mqttTopic=" + mqttTopic + "]";
    }
}
