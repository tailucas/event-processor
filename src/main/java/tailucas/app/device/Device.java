package tailucas.app.device;

import java.time.Instant;
import java.util.List;

import org.apache.commons.lang3.text.WordUtils;
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
        DETECTOR,
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
    @JsonProperty("event_detail")
    protected String eventDetail;
    @JsonProperty("state")
    protected String state;
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
    protected Long timestamp;
    @JsonProperty
    protected Integer uptime;
    @JsonIgnore
    protected Config config;
    @JsonIgnore
    protected String triggerStateDescription;
    public Device() {
        if (log == null) {
            log = LoggerFactory.getLogger(Device.class);
        }
    }
    protected void setFieldsFrom(Device device) {
        this.deviceId = device.deviceId;
        this.deviceKey = device.deviceKey;
        this.deviceLabel = device.deviceLabel;
        this.deviceType = device.deviceType;
        this.eventDetail = device.eventDetail;
        this.state = device.state;
        this.groupName = device.groupName;
        this.location = device.location;
        this.image = device.image;
        this.inputLocation = device.inputLocation;
        this.name = device.name;
        this.sampleValue = device.sampleValue;
        this.storageUrl = device.storageUrl;
        this.storagePath = device.storagePath;
        this.type = device.type;
        this.timestamp = device.timestamp;
        this.uptime = device.uptime;
    }
    @Override
    public Config getConfig() {
        return config;
    }
    @JsonIgnore
    public void setConfig(Config config) {
        this.config = config;
    }
    @Override
    public String getDeviceKey() {
        if (deviceKey == null) {
            final String devType = getDeviceType();
            if (deviceLabel != null) {
                if (location != null) {
                    deviceKey = WordUtils.capitalizeFully(String.format("%s %s", location, deviceLabel));
                } else {
                    return deviceLabel;
                }
            } else if (devType != null) {
                deviceKey = WordUtils.capitalizeFully(String.format("%s %s", location, devType));
            } else {
                log.warn("No identifying information for: {}", this.toString());
            }
        }
        return deviceKey;
    }
    @JsonIgnore // because it tampers with the field representation
    @Override
    public String getDeviceLabel() {
        if (deviceLabel != null) {
            return deviceLabel;
        }
        return getDeviceKey();
    }
    @Override
    public String getDeviceType() {
        if (deviceType == null) {
            final String legacyType = getType().name();
            log.warn("{} using legacy device type {}", this.toString(), legacyType);
            return legacyType.toLowerCase();
        }
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
    public void setLocation(String location) {
        this.location = location;
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
    @Override
    public boolean isHeartbeat() {
        return false;
    }
    @Override
    public boolean wouldTriggerOutput(InputConfig deviceConfig) {
        throw new UnsupportedOperationException("Missing override on 'wouldTriggerOutput' for type "+type);
    }
    @Override
    public String getTriggerStateDescription() {
        if (triggerStateDescription == null) {
            return "unspecified";
        }
        return triggerStateDescription;
    }
    @Override
    public Instant lastTriggered() {
        throw new UnsupportedOperationException("Missing override on 'lastTriggered' for type "+type);
    }
    @Override
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
            String uType = type.toUpperCase();
            if (uType.endsWith("DETECTOR")) {
                deviceType = Type.DETECTOR;
            } else {
                deviceType = Type.valueOf(uType);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e);
        }
        return deviceType;
    }
    @JsonIgnore
    public Device getDeviceByType() {
        Type typeType = getType();
        var device = switch (typeType) {
            case Type.BASE -> this;
            case Type.CAMERA -> new Camera(this);
            case Type.DETECTOR -> new Detector(this);
            default -> null;
        };
        return device;
    }
    @Override
    public Boolean isInput() {
        return Boolean.TRUE;
    }
    @Override
    public Boolean isOutput() {
        return Boolean.FALSE;
    }
    public static Logger getLog() {
        return log;
    }
    public String getDeviceId() {
        return deviceId;
    }
    public String getEventDetail() {
        return eventDetail;
    }
    public byte[] getImage() {
        return image;
    }
    public String getInputLocation() {
        return inputLocation;
    }
    public String getName() {
        return name;
    }
    public Double getSampleValue() {
        return sampleValue;
    }
    public String getStorageUrl() {
        return storageUrl;
    }
    public String getStoragePath() {
        return storagePath;
    }
    public Integer getUptime() {
        return uptime;
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
    private Object input_1;
    @JsonIgnore
    @JsonProperty
    private Object input_2;
    @JsonIgnore
    @JsonProperty
    private Object input_3;
    @JsonIgnore
    @JsonProperty
    private Object input_4;
}
