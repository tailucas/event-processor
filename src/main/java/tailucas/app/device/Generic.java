package tailucas.app.device;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import tailucas.app.device.config.Config;
import tailucas.app.device.config.InputConfig;

public interface Generic {
    @JsonProperty("device_key")
    public String getDeviceKey();
    @JsonProperty("device_label")
    public String getDeviceLabel();
    @JsonProperty("device_type")
    public String getDeviceType();
    @JsonProperty("group_name")
    public String getGroupName();
    @JsonProperty("location")
    public String getLocation();
    @JsonProperty("is_input")
    public boolean isInput();
    @JsonProperty("is_output")
    public boolean isOutput();
    @JsonIgnore
    public Config getConfig();
    @JsonIgnore
    public Instant lastTriggered();
    @JsonIgnore
    public boolean isHeartbeat();
    @JsonIgnore
    public boolean wouldTriggerOutput(InputConfig config);
    @JsonIgnore
    public String getTriggerStateDescription();
    @JsonIgnore
    public List<Device> triggerGroup();
}
