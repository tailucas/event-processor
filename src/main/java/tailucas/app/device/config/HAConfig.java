package tailucas.app.device.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class HAConfig extends Config {
    @JsonProperty
    private String name;
    @JsonProperty("unique_id")
    private String uniqueId;
    @JsonProperty("state_topic")
    private String stateTopic;
    @JsonProperty("device_class")
    private String deviceClass;
    @JsonProperty("command_topic")
    private String commandTopic;
    @JsonProperty
    private String icon;
    @JsonProperty("json_attributes_topic")
    private String jsonAttributesTopic;
    @JsonProperty
    private List<String> options;
    @JsonProperty
    private String min;
    @JsonProperty
    private String max;
    @JsonProperty
    private String mode;
    @JsonProperty("availability_topic")
    private String availabilityTopic;
    @JsonProperty("payload_available")
    private String payloadAvailable;
    @JsonProperty("payload_not_available")
    private String payloadNotAvailable;
    @JsonProperty("state_class")
    private String stateClass;
    @JsonProperty("supported_features")
    private String supportedFeatures;
    @JsonProperty("unit_of_measurement")
    private String unitOfMeasurement;
    @JsonProperty("value_template")
    private String valueTemplate;
    @JsonProperty
    private HADevice device;
    @JsonIgnore
    private String mqttTopic;
    public class HADevice {
        @JsonProperty
        private List<String> ids;
        @JsonProperty
        private String name;
        @JsonProperty
        private String mf;
        @JsonProperty
        private String mdl;
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
        return uniqueId;
    }
    public String getStateTopic() {
        return stateTopic;
    }
    public String getDeviceClass() {
        return deviceClass;
    }
    public String getCommandTopic() {
        return commandTopic;
    }
    public String getIcon() {
        return icon;
    }
    public String getJsonAttributesTopic() {
        return jsonAttributesTopic;
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
        return availabilityTopic;
    }
    public String getPayloadAvailable() {
        return payloadAvailable;
    }
    public String getPayloadNotAvailable() {
        return payloadNotAvailable;
    }
    public String getStateClass() {
        return stateClass;
    }
    public String getSupportedFeatures() {
        return supportedFeatures;
    }
    public String getUnitOfMeasurement() {
        return unitOfMeasurement;
    }
    public String getValueTemplate() {
        return valueTemplate;
    }
    public HADevice getDevice() {
        return device;
    }
    @JsonIgnore
    public String getMqttTopic() {
        return mqttTopic;
    }
    @Override
    public String toString() {
        return "HAConfig [name=" + name + ", unique_id=" + uniqueId + ", state_topic=" + stateTopic
                + ", device_class=" + deviceClass + ", command_topic=" + commandTopic + ", icon=" + icon
                + ", json_attributes_topic=" + jsonAttributesTopic + ", options=" + options + ", min=" + min
                + ", max=" + max + ", mode=" + mode + ", availability_topic=" + availabilityTopic
                + ", payload_available=" + payloadAvailable + ", payload_not_available=" + payloadNotAvailable
                + ", state_class=" + stateClass + ", supported_features=" + supportedFeatures
                + ", unit_of_measurement=" + unitOfMeasurement + ", value_template=" + valueTemplate + ", device="
                + device + ", mqttTopic=" + mqttTopic + "]";
    }
}
