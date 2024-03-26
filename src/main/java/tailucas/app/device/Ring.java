package tailucas.app.device;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.text.WordUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;

import tailucas.app.device.config.Config;
import tailucas.app.device.config.HAConfig;
import tailucas.app.provider.DeviceConfig;

public class Ring implements Generic {

    public enum TriggerSubjects {
        CONTACT,
        MOTION,
        FIRE,
        POLICE,
        SIREN
    }

    @JsonIgnore
    protected static final Map<String, TriggerSubjects> triggers = Map.of(
        TriggerSubjects.CONTACT.name().toLowerCase(), TriggerSubjects.CONTACT,
        TriggerSubjects.MOTION.name().toLowerCase(), TriggerSubjects.MOTION,
        TriggerSubjects.FIRE.name().toLowerCase(), TriggerSubjects.FIRE,
        TriggerSubjects.POLICE.name().toLowerCase(), TriggerSubjects.POLICE,
        TriggerSubjects.SIREN.name().toLowerCase(), TriggerSubjects.SIREN);

    @JsonIgnore
    private static Logger log = null;

    private String acStatus;
    private String alarmState;
    private String auxBatteryLevel;
    private String auxBatteryStatus;
    private int batteryLevel;
    private String batteryStatus;
    private float brightness;
    private String chirps;
    private String commStatus;
    private String entrySecondsLeft;
    private String exitSecondsLeft;
    private String firmwareStatus;
    private String lastArmedBy;
    private String lastArmedTime;
    private String lastDisarmedBy;
    private String lastDisarmedTime;
    private String lastCommTime;
    private String lastUpdate;
    private String linkQuality;
    private float maxVolume;
    private String powerSave;
    private String serialNumber;
    private String tamperStatus;
    private String targetState;
    private float volume;
    @JsonIgnore
    private String componentId;
    @JsonIgnore
    private String componentName;
    @JsonIgnore
    private String deviceId;
    @JsonIgnore
    private String mqttTopic;
    @JsonIgnore
    private String state;
    @JsonIgnore
    private String updateType;
    @JsonIgnore
    private String updateSubject;
    @JsonIgnore
    private HAConfig haConfig;

    public Ring() {
        if (log == null) {
            log = LoggerFactory.getLogger(Ring.class);
        }
    }

    @JsonIgnore
    public String getMqttTopic() {
        return mqttTopic;
    }
    /**
     * Matches against the format:
     * ring/xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx/alarm/yyyyyyyy-yyyy-yyyy-yyyy-yyyyyyyyyyyy/status
     * per https://www.home-assistant.io/integrations/mqtt/#mqtt-discovery
     * @param mqttTopic
     */
    @JsonIgnore
    public void setMqttTopic(String mqttTopic) {
        setMqttTopic(mqttTopic, null);
    }
    @JsonIgnore
    public void setMqttTopic(String mqttTopic, String mqttPayload) {
        this.mqttTopic = mqttTopic;
        final String[] topicParts = mqttTopic.split("/", 0);
        if (topicParts.length < 5) {
            throw new AssertionError(String.format("%s is an invalid MQTT topic.", mqttTopic));
        }
        if (!topicParts[0].equals("ring")) {
            throw new AssertionError(String.format("%s must start with ring/", mqttTopic));
        }
        componentId = topicParts[1];
        componentName = topicParts[2];
        deviceId = topicParts[3];
        if (topicParts.length == 5) {
            final String topicKey = "status";
            if (!topicParts[4].equals(topicKey)) {
                throw new AssertionError(String.format("%s unexpected topic key %s.", mqttTopic, topicKey));
            }
            updateSubject = null;
            updateType = topicKey;
        } else if (topicParts.length == 6) {
            updateSubject = topicParts[4];
            updateType = topicParts[5];
        } else {
            throw new AssertionError(String.format("%s topic has unexpected elements.", mqttTopic));
        }
        state = mqttPayload;
    }
    @JsonIgnore
    public String getTopicDescription() {
        if (deviceId == null || updateType == null) {
            throw new IllegalStateException("No device information is set.");
        }
        if (updateSubject != null) {
            return String.format("Ring device %s (%s %s)", deviceId, updateType, updateSubject);
        }
        return String.format("Ring device %s (%s)", deviceId, updateType);
    }
    @JsonIgnore
    public String getDeviceDescription() {
        final var ringConfig = (HAConfig) getConfig();
        final var ringDevice = ringConfig.getDevice();
        return String.format("%s %s (%s)", ringDevice.getMf(), ringDevice.getMdl(), ringDevice.getName());
    }
    @Override
    public String getDeviceKey() {
        return deviceId;
    }
    @Override
    public String getDeviceLabel() {
        HAConfig config = (HAConfig) getConfig();
        return config.getDevice().getName();
    }
    @Override
    public Config getConfig() {
        final String description = getTopicDescription();
        if (haConfig == null) {
            haConfig = DeviceConfig.getInstance().getHaConfig(this);
            if (haConfig == null) {
                throw new IllegalStateException(String.format("%s has no discovery information.", description));
            }
            var matchedIds = new ArrayList<>();
            haConfig.getDevice().getIds().forEach(id -> {
                if (id.equals(deviceId)) {
                    matchedIds.add(id);
                }
            });
            if (matchedIds.isEmpty()) {
                throw new IllegalStateException(String.format("%s has no matched discovery information.", description));
            }
        }
        return haConfig;
    }
    @Override
    public Instant lastTriggered() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'lastTriggered'");
    }
    public boolean isHeartbeat() {
        boolean statusUpdate = false;
        final String deviceDescripion = getDeviceDescription();
        final String updateType = getUpdateType();
        log.debug("{} {}, config {}.", toString(), getConfig());
        switch(updateType) {
            case "attributes":
                statusUpdate = true;
                break;
            case "status":
                statusUpdate = true;
                log.info("{} status is {}.", deviceDescripion, state);
                break;
            case "state":
                switch (updateSubject) {
                    case "info":
                        statusUpdate = true;
                        StringBuilder sb = new StringBuilder();
                        if (batteryStatus != null) {
                            sb.append(String.format(" Battery Level is %s%% (%s).", getBatteryLevel(), getBatteryStatus()));
                        }
                        if (commStatus != null && linkQuality != null) {
                            sb.append(String.format(" Link quality is %s (%s).", getLinkQuality(), getCommStatus()));
                        }
                        if (tamperStatus != null) {
                            sb.append(String.format(" Tamper status is %s.", getTamperStatus()));
                        }
                        if (sb.length() > 0) {
                            log.info("{} health:{}", deviceDescripion, sb.toString());
                        }
                        break;
                    default:
                        break;
                }
                break;
            default:
                log.warn("Unmapped update type {} for {}.", updateType, toString());
                break;
        }
        return statusUpdate;
    }
    @Override
    public boolean mustTriggerOutput(Config config) {
        boolean triggerOutput = false;
        log.debug("Evaluating trigger for {} based on configs {} and {}.", toString(), getConfig(), config);
        var ringConfig = (HAConfig) getConfig();
        var ringDevice = ringConfig.getDevice();
        final String deviceDescripion = getDeviceDescription();
        final String updateType = getUpdateType();
        log.debug("{} {}, config {}.", toString(), getConfig(), config);
        switch(updateType) {
            case "attributes":
                break;
            case "status":
                break;
            case "state":
                switch (updateSubject) {
                    case "info":
                        break;
                    default:
                        String updateSubjectDescription = updateSubject.replace('_', ' ');
                        updateSubjectDescription = WordUtils.capitalizeFully(updateSubjectDescription);
                        if (triggers.containsKey(updateSubject)) {
                            triggerOutput = true;
                            log.info("{}: {} ({}) is {}.", deviceDescripion, updateSubjectDescription, updateSubject, state);
                        } else {
                            log.warn("{}: {} ({}) is {} but not a trigger.", deviceDescripion, updateSubjectDescription, updateSubject, state);
                        }
                        break;
                }
                break;
            default:
                log.warn("Unmapped update type {} for {}.", updateType, toString());
                break;
        }
        return triggerOutput;
    }
    @Override
    public List<Device> triggerGroup() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'triggerGroup'");
    }
    public String getAcStatus() {
        return acStatus;
    }
    public String getAlarmState() {
        return alarmState;
    }
    public String getAuxBatteryLevel() {
        return auxBatteryLevel;
    }
    public String getAuxBatteryStatus() {
        return auxBatteryStatus;
    }
    public int getBatteryLevel() {
        return batteryLevel;
    }
    public String getBatteryStatus() {
        return batteryStatus;
    }
    public float getBrightness() {
        return brightness;
    }
    public String getChirps() {
        return chirps;
    }
    public String getCommStatus() {
        return commStatus;
    }
    public String getEntrySecondsLeft() {
        return entrySecondsLeft;
    }
    public String getExitSecondsLeft() {
        return exitSecondsLeft;
    }
    public String getFirmwareStatus() {
        return firmwareStatus;
    }
    public String getLastArmedBy() {
        return lastArmedBy;
    }
    public String getLastArmedTime() {
        return lastArmedTime;
    }
    public String getLastDisarmedBy() {
        return lastDisarmedBy;
    }
    public String getLastDisarmedTime() {
        return lastDisarmedTime;
    }
    public String getLastCommTime() {
        return lastCommTime;
    }
    public String getLastUpdate() {
        return lastUpdate;
    }
    public String getLinkQuality() {
        return linkQuality;
    }
    public String getPowerSave() {
        return powerSave;
    }
    public String getSerialNumber() {
        return serialNumber;
    }
    public String getTamperStatus() {
        return tamperStatus;
    }
    public String getTargetState() {
        return targetState;
    }
    public float getMaxVolume() {
        return maxVolume;
    }
    public float getVolume() {
        return volume;
    }
    public String getComponentId() {
        return componentId;
    }
    public String getComponentName() {
        return componentName;
    }
    public String getDeviceId() {
        return deviceId;
    }
    public String getState() {
        return state;
    }
    public String getUpdateType() {
        return updateType;
    }
    public String getUpdateSubject() {
        return updateSubject;
    }
    @Override
    public String toString() {
        return "Ring [acStatus=" + acStatus + ", alarmState=" + alarmState + ", auxBatteryLevel=" + auxBatteryLevel
                + ", auxBatteryStatus=" + auxBatteryStatus + ", batteryLevel=" + batteryLevel + ", batteryStatus="
                + batteryStatus + ", brightness=" + brightness + ", chirps=" + chirps + ", commStatus=" + commStatus
                + ", entrySecondsLeft=" + entrySecondsLeft + ", exitSecondsLeft=" + exitSecondsLeft
                + ", firmwareStatus=" + firmwareStatus + ", lastArmedBy=" + lastArmedBy + ", lastArmedTime="
                + lastArmedTime + ", lastDisarmedBy=" + lastDisarmedBy + ", lastDisarmedTime=" + lastDisarmedTime
                + ", lastCommTime=" + lastCommTime + ", lastUpdate=" + lastUpdate + ", linkQuality=" + linkQuality
                + ", maxVolume=" + maxVolume + ", powerSave=" + powerSave + ", serialNumber=" + serialNumber
                + ", tamperStatus=" + tamperStatus + ", targetState=" + targetState + ", volume=" + volume
                + ", componentId=" + componentId + ", componentName=" + componentName + ", deviceId=" + deviceId
                + ", mqttTopic=" + mqttTopic + ", state=" + state + ", updateType=" + updateType + ", updateSubject="
                + updateSubject + ", haConfig=" + haConfig + "]";
    }
}
