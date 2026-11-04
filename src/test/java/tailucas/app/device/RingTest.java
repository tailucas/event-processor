package tailucas.app.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import tailucas.app.TestStatics;
import tailucas.app.device.config.HAConfig;
import tailucas.app.provider.DeviceConfig;

class RingTest {

    private static final String STATUS_TOPIC = "ring/component/alarm/device-123/status";
    private static final String MOTION_TOPIC = "ring/component/alarm/device-123/motion/state";

    private MockedStatic<DeviceConfig> deviceConfigStatic;
    private DeviceConfig deviceConfig;

    @BeforeAll
    static void initStatics() {
        TestStatics.configureTestMetrics();
    }

    @BeforeEach
    void setUp() {
        deviceConfig = mock(DeviceConfig.class);
        deviceConfigStatic = mockStatic(DeviceConfig.class);
        deviceConfigStatic.when(DeviceConfig::getInstance).thenReturn(deviceConfig);
    }

    @AfterEach
    void tearDown() {
        deviceConfigStatic.close();
    }

    @Test
    void parsesFivePartStatusTopic() {
        final Ring ring = new Ring();
        ring.setMqttTopic(STATUS_TOPIC);
        assertEquals(STATUS_TOPIC, ring.getMqttTopic());
        assertEquals("component", ring.getComponentId());
        assertEquals("alarm", ring.getComponentName());
        assertEquals("device-123", ring.getDeviceId());
        assertEquals("status", ring.getUpdateType());
        assertNull(ring.getUpdateSubject());
        assertNull(ring.getState());
    }

    @Test
    void parsesSixPartSubjectTopic() {
        final Ring ring = new Ring();
        ring.setMqttTopic(MOTION_TOPIC, "ON");
        assertEquals("motion", ring.getUpdateSubject());
        assertEquals("state", ring.getUpdateType());
        assertEquals("ON", ring.getState());
        assertEquals("device-123", ring.getDeviceKey());
    }

    @Test
    void rejectsInvalidTopics() {
        final Ring ring = new Ring();
        assertThrows(AssertionError.class, () -> ring.setMqttTopic("ring/only/three"));
        assertThrows(AssertionError.class, () -> ring.setMqttTopic("other/component/alarm/device-123/status"));
        assertThrows(AssertionError.class, () -> ring.setMqttTopic("ring/component/alarm/device-123/notstatus"));
        assertThrows(AssertionError.class, () -> ring.setMqttTopic("ring/component/alarm/device-123/motion/state/extra"));
    }

    @Test
    void topicDescription() {
        final Ring ring = new Ring();
        assertThrows(IllegalStateException.class, ring::getTopicDescription);
        ring.setMqttTopic(STATUS_TOPIC);
        assertEquals("Ring device device-123 (status)", ring.getTopicDescription());
        ring.setMqttTopic(MOTION_TOPIC, "ON");
        assertEquals("Ring device device-123 (state motion)", ring.getTopicDescription());
    }

    @Test
    void deviceCharacteristics() {
        final Ring ring = new Ring();
        ring.setMqttTopic(STATUS_TOPIC);
        assertEquals("ring", ring.getDeviceType());
        assertEquals("Ring", ring.getGroupName());
        assertNull(ring.getLocation());
        assertTrue(ring.isInput());
        assertFalse(ring.isOutput());
        assertNull(ring.getEventDetail());
        ring.setMqttTopic(MOTION_TOPIC, "ON");
        assertEquals("motion", ring.getDeviceType());
    }

    @Test
    void heartbeatForStatusUpdates() {
        final Ring ring = new Ring();
        ring.setMqttTopic(STATUS_TOPIC, "online");
        assertTrue(ring.isHeartbeat());
    }

    @Test
    void heartbeatForAttributeUpdates() {
        final Ring ring = new Ring();
        ring.setMqttTopic("ring/component/alarm/device-123/motion/attributes", "{}");
        assertTrue(ring.isHeartbeat());
    }

    @Test
    void heartbeatForInfoStateUpdates() {
        final Ring ring = new Ring();
        ring.setMqttTopic("ring/component/alarm/device-123/info/state", "junk");
        assertTrue(ring.isHeartbeat());
    }

    @Test
    void noHeartbeatForTriggerStateUpdates() {
        final Ring ring = new Ring();
        ring.setMqttTopic(MOTION_TOPIC, "ON");
        assertFalse(ring.isHeartbeat());
    }

    @Test
    void noHeartbeatForUnknownUpdateTypes() {
        final Ring ring = new Ring();
        ring.setMqttTopic("ring/component/alarm/device-123/motion/brightness", "50");
        assertFalse(ring.isHeartbeat());
    }

    @Test
    void triggersOnActiveAlarmSubjects() {
        final Ring ring = new Ring();
        ring.setMqttTopic(MOTION_TOPIC, "ON");
        assertTrue(ring.wouldTriggerOutput(null));
        assertEquals("Motion is ON", ring.getTriggerStateDescription());
    }

    @Test
    void noTriggerForInactiveStates() {
        final Ring ring = new Ring();
        ring.setMqttTopic("ring/component/alarm/device-123/alarm/state", "DISARMED");
        assertFalse(ring.wouldTriggerOutput(null));
        ring.setMqttTopic(MOTION_TOPIC, "OFF");
        assertFalse(ring.wouldTriggerOutput(null));
        ring.setMqttTopic("ring/component/alarm/device-123/siren/state", "ARMED_AWAY");
        assertFalse(ring.wouldTriggerOutput(null));
    }

    @Test
    void noTriggerForNonTriggerSubjects() {
        final Ring ring = new Ring();
        ring.setMqttTopic("ring/component/alarm/device-123/brightness/state", "ON");
        assertFalse(ring.wouldTriggerOutput(null));
        ring.setMqttTopic("ring/component/alarm/device-123/info/state", "ON");
        assertFalse(ring.wouldTriggerOutput(null));
    }

    @Test
    void noTriggerWithoutStateInformation() {
        final Ring ring = new Ring();
        // JSON-style updates carry no plain payload state
        ring.setMqttTopic(MOTION_TOPIC);
        assertFalse(ring.wouldTriggerOutput(null));
    }

    @Test
    void noTriggerForStatusOrAttributeUpdates() {
        final Ring ring = new Ring();
        ring.setMqttTopic(STATUS_TOPIC, "online");
        assertFalse(ring.wouldTriggerOutput(null));
        ring.setMqttTopic("ring/component/alarm/device-123/motion/attributes", "x");
        assertFalse(ring.wouldTriggerOutput(null));
    }

    @Test
    void configLookupMatchesDeviceId() {
        final HAConfig haConfig = haConfigWithIds("Front Door", List.of("device-123"));
        when(deviceConfig.getHaConfig(any(Ring.class))).thenReturn(haConfig);
        final Ring ring = new Ring();
        ring.setMqttTopic(STATUS_TOPIC);
        assertSame(haConfig, ring.getConfig());
        assertEquals("Front Door", ring.getDeviceLabel());
        assertEquals("Ring Alarm (Front Door)", ring.getDeviceDescription());
        // a second lookup uses the cached config
        ring.getConfig();
        verify(deviceConfig, times(1)).getHaConfig(any(Ring.class));
    }

    @Test
    void configLookupRejectsMismatchedIds() {
        when(deviceConfig.getHaConfig(any(Ring.class)))
            .thenReturn(haConfigWithIds("Other", List.of("device-999")));
        final Ring ring = new Ring();
        ring.setMqttTopic(STATUS_TOPIC);
        assertNull(ring.getConfig());
        assertNull(ring.getDeviceLabel());
        assertNull(ring.getDeviceDescription());
    }

    @Test
    void configLookupHandlesMissingDiscoveryInfo() {
        when(deviceConfig.getHaConfig(any(Ring.class))).thenReturn(null);
        final Ring ring = new Ring();
        ring.setMqttTopic(STATUS_TOPIC);
        assertNull(ring.getConfig());
    }

    @Test
    void eventTagsCarryMessageStreamContext() {
        final Ring ring = new Ring();
        ring.setMqttTopic("ring/location-1/alarm/device-123/motion/state", "ON");
        assertEquals(Map.of(
            "ring_component_id", "location-1",
            "ring_component_name", "alarm",
            "ring_device_id", "device-123",
            "ring_update_type", "state",
            "ring_update_subject", "motion",
            "ring_state", "ON",
            "ring_message_type", "unknown"), ring.getEventTags());
    }

    @Test
    void eventTagsAreCompleteWithoutSubjectOrState() {
        // a Prometheus counter's label set is fixed when it registers, so every
        // message shape has to supply the same keys
        final Ring ring = new Ring();
        ring.setMqttTopic(STATUS_TOPIC);
        final Map<String, String> tags = ring.getEventTags();
        assertEquals(7, tags.size());
        assertEquals("status", tags.get("ring_update_type"));
        assertEquals("unknown", tags.get("ring_update_subject"));
        assertEquals("unknown", tags.get("ring_state"));
    }

    @Test
    void eventTagsCarryJsonMessageType() {
        final Ring ring = new Ring();
        ring.setMqttTopic(MOTION_TOPIC, "ON");
        TestStatics.setField(ring, "messageType", "power_save");
        assertEquals("power_save", ring.getEventTags().get("ring_message_type"));
    }

    @Test
    void operationalEventsAreTriggerStatesAndUnavailability() {
        // Ring republishes 'status online' for every device on each refresh sweep;
        // only a state change of a trigger-capable entity or a device going
        // unavailable is an operational event
        final Ring online = new Ring();
        online.setMqttTopic(STATUS_TOPIC, "online");
        assertFalse(online.isOperationalEvent());
        final Ring offline = new Ring();
        offline.setMqttTopic(STATUS_TOPIC, "offline");
        assertTrue(offline.isOperationalEvent());
        final Ring contact = new Ring();
        contact.setMqttTopic("ring/component/alarm/device-123/contact/state", "OFF");
        assertTrue(contact.isOperationalEvent());
        final Ring volume = new Ring();
        volume.setMqttTopic("ring/component/alarm/device-123/volume/state", "67");
        assertFalse(volume.isOperationalEvent());
        final Ring info = new Ring();
        info.setMqttTopic("ring/component/alarm/device-123/info/state", "{}");
        assertFalse(info.isOperationalEvent());
        final Ring attributes = new Ring();
        attributes.setMqttTopic("ring/component/alarm/device-123/motion/attributes", "{}");
        assertFalse(attributes.isOperationalEvent());
    }

    @Test
    void binaryPayloadIsSummarised() {
        // ring-mqtt publishes camera snapshots as raw image bytes
        final Ring ring = new Ring();
        ring.setMqttTopic("ring/component/camera/54e0191a1d8d/snapshot/image", "x".repeat(4096));
        assertEquals(Ring.BINARY_PAYLOAD_STATE, ring.getState());
        assertEquals(Ring.BINARY_PAYLOAD_STATE, ring.getEventTags().get("ring_state"));
        assertTrue(ring.getEventTags().get("ring_state").length() <= Ring.MAX_PAYLOAD_TEXT_CHARS);
    }

    @Test
    void triggerSubjectsCoverEveryDomainInTheBridge() {
        // chime/camera entities publish 'ding' on a doorbell press; the alarm domain
        // publishes the rest
        final Ring doorbell = new Ring();
        doorbell.setMqttTopic("ring/component/camera/54e0191a1d8d/ding/state", "ON");
        assertTrue(doorbell.wouldTriggerOutput(null));
        assertEquals("Ding is ON", doorbell.getTriggerStateDescription());
        assertTrue(doorbell.isOperationalEvent());
        final Ring cameraMotion = new Ring();
        cameraMotion.setMqttTopic("ring/component/camera/54e0191a1d8d/motion/state", "ON");
        assertTrue(cameraMotion.wouldTriggerOutput(null));
        final Ring chime = new Ring();
        chime.setMqttTopic("ring/component/chime/90486cbb3b2c/snooze/state", "OFF");
        assertFalse(chime.wouldTriggerOutput(null));
    }

    @Test
    void chimeInfoCarriesWirelessHealth() {
        // a chime reports no battery, comm or tamper state
        final Ring chime = new Ring();
        chime.setMqttTopic("ring/component/chime/90486cbb3b2c/info/state");
        TestStatics.setField(chime, "wirelessNetwork", "No");
        TestStatics.setField(chime, "wirelessSignal", Integer.valueOf(-52));
        assertTrue(chime.isHeartbeat());
        assertNull(chime.getBatteryLevel());
        assertEquals("90486cbb3b2c", chime.getDeviceKey());
        assertEquals("info", chime.getDeviceType());
    }

    @Test
    void cameraComponentTopicsParse() {
        // exact topic the bridge publishes for a camera
        final Ring ring = new Ring();
        ring.setMqttTopic("ring/cfee52e0-fb85-46a7-868e-22436bef6123/camera/54e0191a1d8d/wireless/attributes");
        assertEquals("camera", ring.getComponentName());
        assertEquals("54e0191a1d8d", ring.getDeviceKey());
        assertEquals("wireless", ring.getDeviceType());
        assertEquals("attributes", ring.getUpdateType());
    }

    @Test
    void cameraHealthCountersComeFromAttributesTopics() {
        // wireless/attributes and battery/attributes carry the health values on a
        // camera; info/state repeats them
        final Ring wireless = new Ring();
        wireless.setMqttTopic("ring/component/camera/54e0191a1d8d/wireless/attributes");
        TestStatics.setField(wireless, "wirelessNetwork", "NotNo");
        TestStatics.setField(wireless, "wirelessSignal", Integer.valueOf(-67));
        assertTrue(wireless.isHeartbeat());
        assertFalse(wireless.wouldTriggerOutput(null));
        assertFalse(wireless.isOperationalEvent());
        final Ring battery = new Ring();
        battery.setMqttTopic("ring/component/camera/54e0191a1d8d/battery/attributes");
        TestStatics.setField(battery, "batteryLevel", Integer.valueOf(100));
        TestStatics.setField(battery, "batteryLife", Integer.valueOf(100));
        assertTrue(battery.isHeartbeat());
        assertEquals(Integer.valueOf(100), battery.getBatteryLife());
    }

    @Test
    void postsBatteryLifePerSlot() {
        final Ring ring = new Ring();
        ring.setMqttTopic("ring/component/camera/device-slot/info/state");
        TestStatics.setField(ring, "batteryLife", Integer.valueOf(58));
        TestStatics.setField(ring, "batteryLife2", Integer.valueOf(0));
        ring.reportEvent();
        // slot 1 reports health; slot 2 reports 0 because it is empty, which must not
        // look like a flat battery
        final Double life = TestStatics.metricValue(
            Ring.RING_BATTERY_LIFE_METRIC, "ring_device_id", "device-slot");
        assertNotNull(life);
        assertEquals(58.0, life.doubleValue());
        assertEquals(1, TestStatics.metricLabelSets(Ring.RING_BATTERY_LIFE_METRIC).stream()
            .filter(labels -> "device-slot".equals(labels.get("ring_device_id")))
            .count());
        assertEquals("1", TestStatics.metricLabelSets(Ring.RING_BATTERY_LIFE_METRIC).stream()
            .filter(labels -> "device-slot".equals(labels.get("ring_device_id")))
            .findFirst().orElseThrow().get("slot"));
    }

    @Test
    void postsBatteryLevelAsHealthGauge() {
        final Ring ring = new Ring();
        ring.setMqttTopic("ring/component/alarm/device-batt/info/state");
        TestStatics.setField(ring, "batteryLevel", Integer.valueOf(35));
        ring.reportEvent();
        final Double batteryLevel = TestStatics.metricValue(
            Ring.RING_BATTERY_LEVEL_METRIC, "ring_device_id", "device-batt");
        assertNotNull(batteryLevel);
        assertEquals(35.0, batteryLevel.doubleValue());
        assertEquals(TestStatics.TEST_APP_NAME, TestStatics.metricLabelSets(Ring.RING_BATTERY_LEVEL_METRIC)
            .stream().filter(labels -> "device-batt".equals(labels.get("ring_device_id")))
            .findFirst().orElseThrow().get("application"));
    }

    @Test
    void healthGaugeJoinsToTheDiscoveredDeviceLabel() {
        when(deviceConfig.getHaConfig(any(Ring.class)))
            .thenReturn(haConfigWithIds("Bedroom Motion", List.of("device-joined")));
        final Ring ring = new Ring();
        ring.setMqttTopic("ring/component/alarm/device-joined/info/state");
        TestStatics.setField(ring, "batteryLevel", Integer.valueOf(40));
        ring.reportEvent();
        final Double batteryLevel = TestStatics.metricValue(
            Ring.RING_BATTERY_LEVEL_METRIC, "input_label", "Bedroom Motion");
        assertNotNull(batteryLevel);
        assertEquals(40.0, batteryLevel.doubleValue());
    }

    @Test
    void postsWirelessSignalAsHealthGauge() {
        final Ring ring = new Ring();
        ring.setMqttTopic("ring/component/camera/device-signal/info/state");
        TestStatics.setField(ring, "wirelessSignal", Integer.valueOf(-67));
        ring.reportEvent();
        final Double signal = TestStatics.metricValue(
            Ring.RING_WIRELESS_SIGNAL_METRIC, "ring_device_id", "device-signal");
        assertNotNull(signal);
        assertEquals(-67.0, signal.doubleValue());
    }

    @Test
    void absentBatteryReadingPostsNoGauge() {
        // mains-powered devices report no battery level; it must not read as 0%
        final Ring ring = new Ring();
        ring.setMqttTopic("ring/component/alarm/device-mains/info/state");
        ring.reportEvent();
        assertNull(ring.getBatteryLevel());
        assertNull(TestStatics.metricValue(
            Ring.RING_BATTERY_LEVEL_METRIC, "ring_device_id", "device-mains"));
    }

    @Test
    void repeatedBinaryPayloadsReuseOneMetricSeries() {
        // a size or checksum in the label would create a new series per snapshot
        final Ring first = new Ring();
        first.setMqttTopic("ring/component/camera/54e0191a1d8d/snapshot/image", "x".repeat(4096));
        final Ring second = new Ring();
        second.setMqttTopic("ring/component/camera/54e0191a1d8d/snapshot/image", "y".repeat(9000));
        assertEquals(first.getEventTags(), second.getEventTags());
    }

    @Test
    void shortPayloadIsKeptAsState() {
        final Ring ring = new Ring();
        ring.setMqttTopic(MOTION_TOPIC, "ON");
        assertEquals("ON", ring.getState());
    }

    @Test
    void imageUpdateTypeIsNotAnUnmappedWarning() {
        final Ring ring = new Ring();
        ring.setMqttTopic("ring/component/camera/54e0191a1d8d/snapshot/image", "x".repeat(4096));
        // neither heartbeat nor trigger logic maps this, but it is a routine topic
        assertFalse(ring.isHeartbeat());
        assertFalse(ring.wouldTriggerOutput(null));
        assertFalse(ring.isOperationalEvent());
    }

    @Test
    void reportEventPostsDistinctRingEventMetrics() {
        final Ring stateRing = new Ring();
        stateRing.setMqttTopic(MOTION_TOPIC, "ON");
        stateRing.reportEvent();
        final Ring statusRing = new Ring();
        statusRing.setMqttTopic(STATUS_TOPIC);
        statusRing.reportEvent();

        final List<Map<String, String>> labelSets = TestStatics.metricLabelSets(Ring.RING_EVENT_METRIC);
        final Map<String, String> stateLabels = labelSet(labelSets, "ring_state", "ON");
        assertEquals(TestStatics.TEST_APP_NAME, stateLabels.get("application"));
        assertEquals(TestStatics.TEST_DEVICE_NAME, stateLabels.get("device"));
        assertEquals("component", stateLabels.get("ring_component_id"));
        assertEquals("alarm", stateLabels.get("ring_component_name"));
        assertEquals("device-123", stateLabels.get("ring_device_id"));
        assertEquals("motion", stateLabels.get("ring_update_subject"));
        final Map<String, String> statusLabels = labelSet(labelSets, "ring_update_type", "status");
        assertEquals("unknown", statusLabels.get("ring_state"));
        assertEquals("unknown", statusLabels.get("ring_update_subject"));
    }

    @Test
    void reportEventKeepsOneLabelSetPerPhysicalMessage() {
        final Ring ring = new Ring();
        ring.setMqttTopic(MOTION_TOPIC, "ON");
        ring.reportEvent();
        ring.reportEvent();

        final List<Map<String, String>> labelSets = TestStatics.metricLabelSets(Ring.RING_EVENT_METRIC);
        // a repeated message reuses its series instead of creating a new one
        assertEquals(labelSets.size(), new HashSet<>(labelSets).size());
        assertEquals(Set.of(
            "application", "device",
            "ring_component_id", "ring_component_name", "ring_device_id",
            "ring_update_type", "ring_update_subject", "ring_state", "ring_message_type"),
            labelSet(labelSets, "ring_state", "ON").keySet());
    }

    private static Map<String, String> labelSet(List<Map<String, String>> labelSets, String key, String value) {
        return labelSets.stream()
            .filter(labels -> value.equals(labels.get(key)))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                String.format("No ring event label set with %s=%s in %s", key, value, labelSets)));
    }

    private static HAConfig haConfigWithIds(String name, List<String> ids) {
        return TestStatics.haConfig(name, ids);
    }
}
