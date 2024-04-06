package tailucas.app.device;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import tailucas.app.device.config.Config;
import tailucas.app.device.config.InputConfig;

public class Device implements Generic {

    @JsonIgnore
    protected static Logger log = null;

    public enum Type {
        BASE,
        CAMERA,
        CONTACT,
        METER,
        MOTION,
        SENSOR
    }

    @JsonProperty("device_id")
    protected String deviceId;
    @JsonProperty("device_key")
    protected String deviceKey;
    @JsonProperty("device_label")
    protected String deviceLabel;
    @JsonProperty("device_type")
    protected String deviceType;
    @JsonProperty("group_name")
    protected String groupName;
    @JsonProperty("location")
    protected String location;
    @JsonProperty("image")
    protected byte[] image;
    @JsonProperty("input_location")
    protected String inputLocation;
    @JsonProperty
    protected String name;
    @JsonProperty("sample_value")
    protected Double sampleValue;
    @JsonProperty("storage_url")
    protected String storageUrl;
    @JsonProperty("storage_path")
    protected String storagePath;
    @JsonProperty
    protected String type;
    @JsonProperty
    protected Double timestamp;
    @JsonProperty
    protected Double uptime;
    @JsonIgnore
    protected Config config;
    @JsonIgnore
    protected String triggerStateDescription;
    public Device() {
        if (log == null) {
            log = LoggerFactory.getLogger(Device.class);
        }
    }
    @JsonIgnore
    public Config getConfig() {
        return config;
    }
    @JsonIgnore
    public void setConfig(Config config) {
        this.config = config;
    }
    @JsonIgnore
    public String getDeviceKey() {
        return deviceKey;
    }
    @JsonIgnore
    public void setDeviceKey(String deviceKey) {
        this.deviceKey = deviceKey;
    }
    @JsonIgnore
    public String getDeviceLabel() {
        if (deviceLabel != null) {
            return deviceLabel;
        }
        return getDeviceKey();
    }
    @Override
    public String getDeviceType() {
        return deviceType;
    }
    @Override
    public String getGroupName() {
        return groupName;
    }
    @Override
    public String getLocation() {
        return location;
    }
    @JsonIgnore
    public Instant getTimestamp() {
        if (timestamp == null) {
            return Instant.now();
        }
        return Instant.ofEpochSecond(timestamp.longValue());
    }
    @JsonIgnore
    public long getUptimeSeconds() {
        return Double.valueOf(timestamp - uptime).longValue();
    }
    @JsonIgnore
    public boolean isHeartbeat() {
        return false;
    }
    @JsonIgnore
    public boolean mustTriggerOutput(InputConfig deviceConfig) {
        if (!deviceConfig.isDeviceEnabled()) {
            return false;
        }
        final TriggerHistory triggerHistory = TriggerHistory.getInstance();
        final Integer triggerInterval = deviceConfig.getActivationInterval();
        if (triggerInterval != null) {
            final Boolean checkMultiTrigger = deviceConfig.getMultiTrigger();
            if (checkMultiTrigger != null && checkMultiTrigger.booleanValue()) {
                final Integer triggerRate = deviceConfig.getTriggerWindow();
                if (triggerRate == null) {
                    log.warn("Multi-trigger set for {} but no trigger rate set.", deviceKey);
                    return true;
                } else if (triggerHistory.isMultiTriggered(deviceKey, triggerRate, triggerInterval)) {
                    return false;
                }
            } else if (triggerHistory.triggeredWithin(deviceKey, triggerInterval.intValue())) {
                return false;
            }
            return false;
        }
        return true;
    }
    @Override
    public String getTriggerStateDescription() {
        if (triggerStateDescription == null) {
            return "unspecified";
        }
        return triggerStateDescription;
    }
    @JsonIgnore
    public Instant lastTriggered() {
        throw new UnsupportedOperationException("Missing override on 'lastTriggered' for type "+type);
    }
    @JsonIgnore
    public List<Device> triggerGroup() {
        throw new UnsupportedOperationException("Missing override on 'triggerGroup' for type "+type);
    }
    @JsonIgnore
    public Type getType() {
        if (type == null) {
            return Type.BASE;
        }
        Type deviceType = null;
        try {
            deviceType = Type.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e);
        }
        return deviceType;
    }
    @JsonIgnore
    public Device getDeviceByType() {
        Type type = getType();
        var device = switch (type) {
            case Type.BASE -> this;
            case Type.CAMERA -> new Camera(this);
            default -> null;
        };
        return device;
    }
    @Override
    public boolean isInput() {
        return true;
    }
    @Override
    public boolean isOutput() {
        return false;
    }
    @Override
    public String toString() {
        return "Device [device_key=" + deviceKey + ", device_id=" + deviceId + ", device_label=" + deviceLabel
                + ", location=" + location + ", input_location=" + inputLocation + ", name=" + name + ", sample_value="
                + sampleValue + ", storage_url=" + storageUrl + ", storage_path=" + storagePath + ", type=" + type
                + ", timestamp=" + timestamp + ", uptime=" + uptime + "]";
    }
    /*
     * Legacy message fields not used here
     */
    @JsonIgnore
    @JsonProperty
    protected Object input_1;
    @JsonIgnore
    @JsonProperty
    protected Object input_2;
    @JsonIgnore
    @JsonProperty
    protected Object input_3;
    @JsonIgnore
    @JsonProperty
    protected Object input_4;
}
