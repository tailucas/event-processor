package tailucas.app.device;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import tailucas.app.device.config.Config;

public class Ring implements Generic {
    public String acStatus;
    public String alarmState;
    public int batteryLevel;
    public String batteryStatus;
    public float brightness;
    public String chirps;
    public String commStatus;
    public String entrySecondsLeft;
    public String exitSecondsLeft;
    public String firmwareStatus;
    public String lastArmedBy;
    public String lastCommTime;
    public String lastUpdate;
    public String linkQuality;
    public String powerSave;
    public String serialNumber;
    public String tamperStatus;
    public float volume;
    @JsonIgnore
    protected String componentId;
    @JsonIgnore
    protected String componentName;
    @JsonIgnore
    protected String deviceId;
    @JsonIgnore
    protected String mqttTopic;
    @JsonIgnore
    protected String state;
    @JsonIgnore
    protected String updateType;
    @JsonIgnore
    protected String updateSubject;
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
            updateType = topicKey;
        } else if (topicParts.length == 6) {
            updateSubject = topicParts[4];
            updateType = topicParts[5];
        } else {
            throw new AssertionError(String.format("%s topic has unexpected elements.", mqttTopic));
        }
        state = mqttPayload;
    }
    @Override
    public String getDeviceKey() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getDeviceKey'");
    }
    @Override
    public String getDeviceLabel() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getDeviceLabel'");
    }
    @Override
    public Config getConfig() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getConfig'");
    }
    @Override
    public Instant lastTriggered() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'lastTriggered'");
    }
    @Override
    public boolean mustTriggerOutput(Config config) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'mustTriggerOutput'");
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
        return "Ring [acStatus=" + acStatus + ", alarmState=" + alarmState + ", batteryLevel=" + batteryLevel
                + ", batteryStatus=" + batteryStatus + ", brightness=" + brightness + ", chirps=" + chirps
                + ", commStatus=" + commStatus + ", entrySecondsLeft=" + entrySecondsLeft + ", exitSecondsLeft="
                + exitSecondsLeft + ", firmwareStatus=" + firmwareStatus + ", lastArmedBy=" + lastArmedBy
                + ", lastCommTime=" + lastCommTime + ", lastUpdate=" + lastUpdate + ", linkQuality=" + linkQuality
                + ", powerSave=" + powerSave + ", serialNumber=" + serialNumber + ", tamperStatus=" + tamperStatus
                + ", volume=" + volume + ", componentId=" + componentId + ", componentName=" + componentName
                + ", deviceId=" + deviceId + ", mqttTopic=" + mqttTopic + ", state=" + state + ", updateType="
                + updateType + ", updateSubject=" + updateSubject + "]";
    }
}
