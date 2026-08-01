package tailucas.app.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import java.util.List;

import org.junit.jupiter.api.AfterEach;
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

    private static HAConfig haConfigWithIds(String name, List<String> ids) {
        return TestStatics.haConfig(name, ids);
    }
}
