package tailucas.app.device;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.WordUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import tailucas.app.device.config.HAConfig;
import tailucas.app.device.config.InputConfig;
import tailucas.app.provider.DeviceConfig;
import tailucas.app.provider.DeviceState;
import tailucas.app.provider.Metrics;
import tailucas.app.provider.PayloadType;

public class Ring implements Generic {

    public enum TriggerSubjects {
        ALARM, // pending, disarmed
        CONTACT,
        DING, // doorbell press, published by cameras
        MOTION,
        FIRE,
        POLICE,
        SIREN
    }

    @JsonIgnore
    protected static final Map<String, TriggerSubjects> triggers = Map.of(
        TriggerSubjects.ALARM.name().toLowerCase(Locale.ROOT), TriggerSubjects.ALARM,
        TriggerSubjects.CONTACT.name().toLowerCase(Locale.ROOT), TriggerSubjects.CONTACT,
        TriggerSubjects.DING.name().toLowerCase(Locale.ROOT), TriggerSubjects.DING,
        TriggerSubjects.MOTION.name().toLowerCase(Locale.ROOT), TriggerSubjects.MOTION,
        TriggerSubjects.FIRE.name().toLowerCase(Locale.ROOT), TriggerSubjects.FIRE,
        TriggerSubjects.POLICE.name().toLowerCase(Locale.ROOT), TriggerSubjects.POLICE,
        TriggerSubjects.SIREN.name().toLowerCase(Locale.ROOT), TriggerSubjects.SIREN);

    public enum InactiveStates {
        OFF,
        ARMING,
        DISARMED,
        ARMED_HOME,
        ARMED_AWAY,
        PENDING
    }

    @JsonIgnore
    protected static final Map<String, InactiveStates> nonTriggerStates = Map.of(
        InactiveStates.OFF.name(), InactiveStates.OFF,
        InactiveStates.ARMING.name(), InactiveStates.ARMING,
        InactiveStates.DISARMED.name(), InactiveStates.DISARMED,
        InactiveStates.ARMED_HOME.name(), InactiveStates.ARMED_HOME,
        InactiveStates.ARMED_AWAY.name(), InactiveStates.ARMED_AWAY,
        InactiveStates.PENDING.name(), InactiveStates.PENDING);

    @JsonIgnore
    private static final Logger log = LoggerFactory.getLogger(Ring.class);

    /** Prometheus counter incremented once per inbound Ring MQTT message. */
    @JsonIgnore
    public static final String RING_EVENT_METRIC = "ring_event";

    /**
     * Placeholder for context a message shape does not carry. The Prometheus
     * sanitizer rejects empty label values, and a counter's label set is fixed
     * when it registers, so every ring event uses this instead of an absent key.
     */
    @JsonIgnore
    static final String UNKNOWN_TAG_VALUE = "unknown";

    /**
     * Longest payload text kept as state, and longest value accepted as a metric
     * label or log field. Ring publishes camera snapshots as raw image bytes, and
     * a single unbounded label would poison the metric store and the log.
     */
    @JsonIgnore
    static final int MAX_PAYLOAD_TEXT_CHARS = 64;

    /**
     * State reported instead of an oversized payload. Constant so that repeated
     * camera snapshots reuse one metric series instead of creating one per size.
     */
    @JsonIgnore
    public static final String BINARY_PAYLOAD_STATE = "binary_payload";

    /** Gauge for the battery percentage a device reports (0-100). */
    @JsonIgnore
    public static final String RING_BATTERY_LEVEL_METRIC = "ring_battery_level";

    /** Gauge for the wireless signal strength a camera or chime reports (dBm). */
    @JsonIgnore
    public static final String RING_WIRELESS_SIGNAL_METRIC = "ring_wireless_signal";

    /**
     * Gauge for the battery health a battery-powered device reports per slot
     * (0-100), labelled {@code slot}. Distinct from {@link #RING_BATTERY_LEVEL_METRIC},
     * which is the charge percentage.
     */
    @JsonIgnore
    public static final String RING_BATTERY_LIFE_METRIC = "ring_battery_life";

    /**
     * Update types that never describe device state: {@code image} carries a
     * camera snapshot and {@code command} is an inbound instruction. Neither is
     * an unmapped topic worth warning about.
     */
    @JsonIgnore
    private static final Set<String> nonEventUpdateTypes = Set.of("image", "command");

    private static final Metrics metrics = Metrics.getInstance();

    // inconsistent casing
    @JsonProperty("ArmedBy")
    private String armedBy;
    @JsonProperty("ArmedTime")
    private String armedTime;
    @JsonProperty("DisarmedBy")
    private String disarmedBy;
    @JsonProperty("DisarmedTime")
    private String disarmedTime;
    private String acStatus;
    private String alarmState;
    private String alarmClearedBy;
    private String alarmClearedTime;
    private String auxBatteryLevel;
    private String auxBatteryStatus;
    /** Absent for mains-powered devices, so it must not default to a percentage. */
    private Integer batteryLevel;
    private String batteryStatus;
    /** Battery health per slot; ring-mqtt also publishes this as batteryLife/batteryLife2. */
    private Integer batteryLife;
    private Integer batteryLife2;
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
    /** Reported by cameras and chimes only, in dBm (negative). */
    private Integer wirelessSignal;
    /** Reported by cameras and chimes only. */
    private String wirelessNetwork;
    /**
     * Raw bytes of a summarised payload, i.e. a camera snapshot. Kept out of state,
     * logs and metrics (it is a picture), but published to outputs as
     * {@code image} so an input linked to an output can forward it.
     */
    private byte[] image;
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
    @JsonIgnore
    private String triggerStateDescription;
    @JsonProperty("message_type")
    private String messageType;

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
        state = summarizePayload(mqttPayload);
    }
    /**
     * Keeps a payload usable as state. Ring publishes camera snapshots as raw
     * image bytes on {@code <camera>/snapshot/image}, which would otherwise be
     * copied verbatim into logs, spans and metric labels. The replacement is a
     * constant, not a size, so repeated snapshots cannot create new metric series.
     */
    @JsonIgnore
    private static String summarizePayload(String mqttPayload) {
        if (mqttPayload == null || mqttPayload.length() <= MAX_PAYLOAD_TEXT_CHARS) {
            return mqttPayload;
        }
        return BINARY_PAYLOAD_STATE;
    }
    /**
     * Event context as carried by the MQTT message stream: the topic parts that
     * identify the Ring location, entity and device, plus the payload state.
     * Every key is always present because a Prometheus counter's label set is
     * fixed when it is registered; context a message does not carry is reported
     * as {@link #UNKNOWN_TAG_VALUE}.
     */
    @JsonIgnore
    public Map<String, String> getEventTags() {
        final Map<String, String> eventTags = new LinkedHashMap<>();
        eventTags.put("ring_component_id", tagValue(componentId));
        eventTags.put("ring_component_name", tagValue(componentName));
        eventTags.put("ring_device_id", tagValue(deviceId));
        eventTags.put("ring_update_type", tagValue(updateType));
        eventTags.put("ring_update_subject", tagValue(updateSubject));
        eventTags.put("ring_state", tagValue(state));
        eventTags.put("ring_message_type", tagValue(messageType));
        return eventTags;
    }
    /**
     * True when a message carries something worth seeing in normal operation: a
     * state change of a trigger-capable entity, or a device becoming unavailable.
     * Ring republishes {@code status online} for every device on each refresh
     * sweep, so a healthy availability heartbeat is not an operational event;
     * losing availability is. Device telemetry and attribute dumps repeat on the
     * same sweeps, so they are logged at DEBUG while still counted by
     * {@link #RING_EVENT_METRIC}.
     */
    @JsonIgnore
    public boolean isOperationalEvent() {
        if ("status".equals(updateType)) {
            return !"online".equalsIgnoreCase(state);
        }
        // a snapshot is an event in its own right: it carries the picture that
        // linked outputs forward
        if ("image".equals(updateType)) {
            return true;
        }
        return "state".equals(updateType) && updateSubject != null && triggers.containsKey(updateSubject);
    }
    /**
     * Reports one inbound Ring MQTT message: a structured record plus a distinct
     * increment of the {@code ring_event} counter, so every Ring event is visible
     * whether or not it ends up triggering an output.
     */
    @JsonIgnore
    public void reportEvent() {
        final Map<String, String> eventTags = getEventTags();
        LoggingEventBuilder eventLog = (isOperationalEvent() ? log.atInfo() : log.atDebug())
            .setMessage("Ring event")
            .addKeyValue("ring_topic", tagValue(mqttTopic));
        if (image != null) {
            // say what the withheld blob is, so a snapshot is diagnosable from the
            // log without logging the picture
            eventLog = eventLog
                .addKeyValue("ring_payload_type", PayloadType.detect(image))
                .addKeyValue("ring_payload_bytes", image.length);
        }
        final LoggingEventBuilder sink = eventLog;
        eventTags.forEach(sink::addKeyValue);
        sink.log();
        metrics.postMetric(RING_EVENT_METRIC, eventTags);
        postHealthMetrics();
        // keep the per-device view current for GET /devices and the Telegram /ring
        // command; this is a single map merge on the MQTT callback thread
        DeviceState.getInstance().record(this);
    }
    /**
     * Posts the health values Ring reports alongside device data, so battery drain
     * and link quality can be tracked and alerted on without parsing logs.
     *
     * <p>Ring spreads these across topics ({@code info/state} for every device,
     * {@code battery/attributes} on sensors, {@code wireless/attributes} on
     * cameras and chimes) and omits the fields a device does not have, so each
     * value is posted on its own and only when the message carried it.
     */
    @JsonIgnore
    private void postHealthMetrics() {
        final Map<String, String> healthTags = getHealthTags();
        if (batteryLevel != null) {
            metrics.postMetric(RING_BATTERY_LEVEL_METRIC, Double.valueOf(batteryLevel.doubleValue()), healthTags);
        }
        postBatteryLife("1", batteryLife);
        postBatteryLife("2", batteryLife2);
        if (wirelessSignal != null) {
            metrics.postMetric(RING_WIRELESS_SIGNAL_METRIC, Double.valueOf(wirelessSignal.doubleValue()), healthTags);
        }
    }
    /**
     * Publishes battery health for one slot. Ring reports 0 for a slot with no
     * battery fitted, which must not look like a flat battery.
     */
    @JsonIgnore
    private void postBatteryLife(String slot, Integer value) {
        if (value == null || value.intValue() <= 0) {
            return;
        }
        final Map<String, String> batteryLifeTags = getHealthTags();
        batteryLifeTags.put("slot", slot);
        metrics.postMetric(RING_BATTERY_LIFE_METRIC, Double.valueOf(value.doubleValue()), batteryLifeTags);
    }
    @JsonIgnore
    private Map<String, String> getHealthTags() {
        // input_label matches the event/triggered counters, so health joins to
        // activity per device without a lookup table. Discovery is read without
        // the "No discovery information" WARN, which would otherwise repeat for
        // every message; the event path already reports a missing config once.
        final Map<String, String> healthTags = new LinkedHashMap<>();
        healthTags.put("input_label", tagValue(getDiscoveredDeviceLabel()));
        healthTags.put("ring_device_id", tagValue(deviceId));
        healthTags.put("ring_component_name", tagValue(componentName));
        return healthTags;
    }
    /**
     * Discovery information already cached for this device, or null when the device
     * has not been discovered. Unlike {@link #getConfig()} this never logs: it is
     * used on the per-message path, where a missing config must not repeat a
     * warning for every message.
     */
    @JsonIgnore
    public HAConfig getDiscoveredConfig() {
        final HAConfig discovered = haConfig != null ? haConfig : DeviceConfig.getInstance().getHaConfig(this);
        if (discovered == null || discovered.getDevice() == null) {
            return null;
        }
        return discovered;
    }
    @JsonIgnore
    private String getDiscoveredDeviceLabel() {
        final HAConfig discovered = getDiscoveredConfig();
        return discovered == null ? null : discovered.getDevice().getName();
    }
    @JsonIgnore
    private static String tagValue(String value) {
        if (StringUtils.isEmpty(value)) {
            return UNKNOWN_TAG_VALUE;
        }
        if (value.length() > MAX_PAYLOAD_TEXT_CHARS) {
            return value.substring(0, MAX_PAYLOAD_TEXT_CHARS);
        }
        return value;
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
        final var ringConfig = getConfig();
        if (ringConfig == null) {
            return null;
        }
        final var ringDevice = ringConfig.getDevice();
        return String.format("%s %s (%s)", ringDevice.getMf(), ringDevice.getMdl(), ringDevice.getName());
    }
    @Override
    public String getDeviceKey() {
        return deviceId;
    }
    @Override
    public String getDeviceLabel() {
        HAConfig config = getConfig();
        if (config == null) {
            return null;
        }
        return config.getDevice().getName();
    }
    @Override
    public String getDeviceType() {
        if (updateSubject == null) {
            return "ring";
        }
        return updateSubject;
    }
    @Override
    public String getMessageType() {
        return messageType;
    }
    @Override
    public String getGroupName() {
        return this.getClass().getSimpleName();
    }
    @Override
    public String getLocation() {
        // TODO
        return null;
    }
    @JsonIgnore
    @Override
    public HAConfig getConfig() {
        final String description = getTopicDescription();
        if (haConfig == null) {
            final HAConfig discovered = DeviceConfig.getInstance().getHaConfig(this);
            if (discovered == null) {
                log.atWarn().setMessage("No discovery information").addKeyValue("description", description).log();
                return null;
            }
            var matchedIds = new ArrayList<>();
            discovered.getDevice().getIds().forEach(id -> {
                if (id.equals(deviceId)) {
                    matchedIds.add(id);
                }
            });
            if (matchedIds.isEmpty()) {
                log.atWarn().setMessage("No matched discovery information").addKeyValue("description", description).log();
                return null;
            }
            // only cache discovery information once it has been matched to this device
            haConfig = discovered;
        }
        return haConfig;
    }
    @JsonIgnore
    @Override
    public Instant lastTriggered() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'lastTriggered'");
    }
    @JsonIgnore
    private String getUpdateSubjectDescription() {
        if (updateSubject == null) {
            return null;
        }
        String updateSubjectDescription = updateSubject.replace('_', ' ');
        updateSubjectDescription = WordUtils.capitalizeFully(updateSubjectDescription);
        return updateSubjectDescription;
    }
    @Override
    @JsonIgnore
    public boolean carriesEvent() {
        // an image update is the event; it mirrors wouldTriggerOutput so the standard
        // output link forwards the snapshot even if the message is also a heartbeat
        return "image".equals(updateType);
    }
    @JsonIgnore
    public boolean isHeartbeat() {
        boolean statusUpdate = false;
        String updateSubjectDescription = getDeviceDescription();
        if (updateSubjectDescription == null) {
            updateSubjectDescription = getUpdateSubjectDescription();
        }
        final String updateType = getUpdateType();
        switch(updateType) {
            case "attributes":
                statusUpdate = true;
                break;
            case "status":
                statusUpdate = true;
                // 'online' is republished for every device on each sweep, so only a
                // change away from available is worth an operational record
                final LoggingEventBuilder statusLog = isOperationalEvent() ? log.atInfo() : log.atDebug();
                statusLog.setMessage("Status update")
                    .addKeyValue("subject", updateSubjectDescription)
                    .addKeyValue("ring_device_id", StringUtils.defaultString(deviceId))
                    .addKeyValue("state", state)
                    .log();
                break;
            case "state":
                if (updateSubject == null) {
                    break;
                }
                switch (updateSubject) {
                    case "info":
                        statusUpdate = true;
                        StringBuilder sb = new StringBuilder();
                        // cameras report a charge level without a battery status
                        if (batteryLevel != null || batteryStatus != null) {
                            sb.append(String.format(" Battery Level is %s%% (%s).",
                                getBatteryLevel() == null ? "unknown" : getBatteryLevel(),
                                getBatteryStatus() == null ? "unknown" : getBatteryStatus()));
                        }
                        if (batteryLife != null) {
                            sb.append(String.format(" Battery life is %s%%.", getBatteryLife()));
                        }
                        if (commStatus != null && linkQuality != null) {
                            sb.append(String.format(" Link quality is %s (%s).", getLinkQuality(), getCommStatus()));
                        }
                        if (tamperStatus != null) {
                            sb.append(String.format(" Tamper status is %s.", getTamperStatus()));
                        }
                        // cameras and chimes report no battery, comm or tamper state,
                        // so without these their health update would be empty
                        if (wirelessNetwork != null) {
                            sb.append(String.format(" Wireless network is %s.", getWirelessNetwork()));
                        }
                        if (wirelessSignal != null) {
                            sb.append(String.format(" Wireless signal is %s dBm.", getWirelessSignal()));
                        }
                        if (sb.length() > 0) {
                            log.atDebug().setMessage("Health update")
                                .addKeyValue("subject", updateSubjectDescription)
                                .addKeyValue("ring_device_id", StringUtils.defaultString(deviceId))
                                .addKeyValue("health", sb.toString())
                                .log();
                        }
                        break;
                    default:
                        break;
                }
                break;
            default:
                logUnmappedUpdateType();
                break;
        }
        return statusUpdate;
    }
    /**
     * Logs an update type that neither the trigger nor the heartbeat logic maps.
     * Known non-event topics (camera snapshots, inbound commands) are routine, so
     * they are logged at DEBUG and only an unrecognised type is a WARNING.
     */
    @JsonIgnore
    private void logUnmappedUpdateType() {
        final LoggingEventBuilder unmappedLog = nonEventUpdateTypes.contains(updateType) ? log.atDebug() : log.atWarn();
        unmappedLog.setMessage("Unmapped update type")
            .addKeyValue("update_type", StringUtils.defaultString(updateType))
            .addKeyValue("ring_update_subject", StringUtils.defaultString(updateSubject))
            .addKeyValue("ring_device_id", StringUtils.defaultString(deviceId))
            .log();
    }
    @JsonIgnore
    @Override
    public boolean wouldTriggerOutput(InputConfig config) {
        boolean triggerOutput = false;
        final String deviceDescription = getDeviceDescription();
        String updateSubjectDescription = getUpdateSubjectDescription();
        if (deviceDescription != null) {
            updateSubjectDescription = String.format("%s: %s", deviceDescription, updateSubjectDescription);
        }
        final String updateType = getUpdateType();
        switch(updateType) {
            case "attributes":
                break;
            case "status":
                break;
            case "image":
                // a snapshot carries the picture itself, so forwarding it is the
                // trigger: an input config plus a linked output is all it needs
                log.atInfo().setMessage("Subject is in a trigger state")
                    .addKeyValue("subject", updateSubjectDescription)
                    .addKeyValue("ring_device_id", StringUtils.defaultString(deviceId))
                    .addKeyValue("ring_update_type", updateType)
                    .log();
                triggerOutput = true;
                triggerStateDescription = String.format("%s available", updateSubjectDescription);
                break;
            case "state":
                if (updateSubject == null) {
                    break;
                }
                switch (updateSubject) {
                    case "info":
                        break;
                    default:
                        if (state == null) {
                            log.atDebug().setMessage("No state information")
                                .addKeyValue("subject", updateSubjectDescription)
                                .addKeyValue("ring_device_id", StringUtils.defaultString(deviceId))
                                .addKeyValue("ring_update_type", updateType)
                                .log();
                            break;
                        }
                        if (triggers.containsKey(updateSubject)) {
                            if (!nonTriggerStates.containsKey(state.toUpperCase(Locale.ROOT))) {
                                log.atInfo().setMessage("Subject is in a trigger state")
                                    .addKeyValue("subject", updateSubjectDescription)
                                    .addKeyValue("ring_device_id", StringUtils.defaultString(deviceId))
                                    .addKeyValue("ring_update_type", updateType)
                                    .addKeyValue("state", state)
                                    .log();
                                triggerOutput = true;
                                triggerStateDescription = String.format("%s is %s", updateSubjectDescription, state);
                            } else {
                                log.atDebug().setMessage("Subject is not in a trigger state")
                                    .addKeyValue("subject", updateSubjectDescription)
                                    .addKeyValue("ring_device_id", StringUtils.defaultString(deviceId))
                                    .addKeyValue("ring_update_type", updateType)
                                    .addKeyValue("state", state)
                                    .log();
                            }
                        } else {
                            log.atDebug().setMessage("Subject is not a trigger")
                                .addKeyValue("subject", updateSubjectDescription)
                                .addKeyValue("ring_device_id", StringUtils.defaultString(deviceId))
                                .addKeyValue("ring_update_type", updateType)
                                .addKeyValue("state", state)
                                .log();
                        }
                        break;
                }
                break;
            default:
                logUnmappedUpdateType();
                break;
        }
        return triggerOutput;
    }
    @Override
    public String getEventDetail() {
        // a snapshot names the picture in the forwarded message, e.g. "Front Gate
        // snapshot (14:02)"; everything else is already described by the label
        return "image".equals(updateType) ? updateSubject : null;
    }
    @JsonIgnore
    @Override
    public String getTriggerStateDescription() {
        return triggerStateDescription;
    }
    @JsonIgnore
    @Override
    public List<Device> triggerGroup() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'triggerGroup'");
    }
    @Override
    public Boolean isInput() {
        return Boolean.TRUE;
    }
    @Override
    public Boolean isOutput() {
        return Boolean.FALSE;
    }
    public String getAcStatus() {
        return acStatus;
    }
    public String getAlarmState() {
        return alarmState;
    }
    public String getAlarmClearedBy() {
        return alarmClearedBy;
    }
    public String getAlarmClearedTime() {
        return alarmClearedTime;
    }
    public String getAuxBatteryLevel() {
        return auxBatteryLevel;
    }
    public String getAuxBatteryStatus() {
        return auxBatteryStatus;
    }
    public Integer getBatteryLevel() {
        return batteryLevel;
    }
    public Integer getWirelessSignal() {
        return wirelessSignal;
    }
    public String getWirelessNetwork() {
        return wirelessNetwork;
    }
    /**
     * Snapshot bytes for the output payload. Read-only: the setter is the only way
     * in, and the array is copied on both sides so no caller can mutate it.
     */
    @JsonProperty("image")
    public byte[] getImage() {
        return image == null ? null : image.clone();
    }
    /**
     * Records a message whose payload is a picture rather than a state value. The
     * bytes are kept so a linked output can forward them, and {@code state} is
     * summarised so nothing binary reaches a log, span or metric label.
     *
     * @param payload the raw payload; the array is copied because the MQTT client
     *                owns the original buffer
     */
    @JsonIgnore
    public void setBinaryPayload(byte[] payload) {
        image = payload == null ? null : payload.clone();
        if (payload != null) {
            state = BINARY_PAYLOAD_STATE;
        }
    }
    public Integer getBatteryLife() {
        return batteryLife;
    }
    public Integer getBatteryLife2() {
        return batteryLife2;
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
    public String getArmedBy() {
        return armedBy;
    }
    public String getArmedTime() {
        return armedTime;
    }
    public String getDisarmedBy() {
        return disarmedBy;
    }
    public String getDisarmedTime() {
        return disarmedTime;
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
        return "Ring [acStatus=" + acStatus + ", alarmState=" + alarmState + ", alarmClearedBy=" + alarmClearedBy
                + ", alarmClearedTime=" + alarmClearedTime + ", auxBatteryLevel=" + auxBatteryLevel
                + ", auxBatteryStatus=" + auxBatteryStatus + ", batteryLevel=" + batteryLevel + ", batteryStatus="
                + batteryStatus + ", brightness=" + brightness + ", chirps=" + chirps + ", commStatus=" + commStatus
                + ", entrySecondsLeft=" + entrySecondsLeft + ", exitSecondsLeft=" + exitSecondsLeft
                + ", firmwareStatus=" + firmwareStatus + ", armedBy=" + armedBy + ", armedTime=" + armedTime
                + ", disarmedBy=" + disarmedBy + ", disarmedTime=" + disarmedTime + ", lastArmedBy=" + lastArmedBy
                + ", lastArmedTime=" + lastArmedTime + ", lastDisarmedBy=" + lastDisarmedBy + ", lastDisarmedTime="
                + lastDisarmedTime + ", lastCommTime=" + lastCommTime + ", lastUpdate=" + lastUpdate + ", linkQuality="
                + linkQuality + ", maxVolume=" + maxVolume + ", powerSave=" + powerSave + ", serialNumber="
                + serialNumber + ", tamperStatus=" + tamperStatus + ", targetState=" + targetState + ", volume="
                + volume + ", wirelessSignal=" + wirelessSignal + ", wirelessNetwork=" + wirelessNetwork
                + ", batteryLife=" + batteryLife + ", batteryLife2=" + batteryLife2
                + ", componentId=" + componentId + ", componentName=" + componentName + ", deviceId="
                + deviceId + ", mqttTopic=" + mqttTopic + ", state="
                // never let an object dump carry a payload into a log
                + StringUtils.abbreviate(state, MAX_PAYLOAD_TEXT_CHARS)
                + ", updateType=" + updateType
                + ", updateSubject=" + updateSubject + ", haConfig=" + haConfig + ", triggerStateDescription="
                + triggerStateDescription + "]";
    }
}