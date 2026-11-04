package tailucas.app.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import tailucas.app.TestStatics;
import tailucas.app.device.Ring;

class DeviceStateTest {

    private static final String LOCATION = "location";

    private MockedStatic<DeviceConfig> deviceConfigStatic;

    @BeforeEach
    void setUp() {
        final DeviceConfig deviceConfig = mock(DeviceConfig.class);
        deviceConfigStatic = mockStatic(DeviceConfig.class);
        deviceConfigStatic.when(DeviceConfig::getInstance).thenReturn(deviceConfig);
    }

    @AfterEach
    void tearDown() {
        deviceConfigStatic.close();
    }

    /** Builds the Ring a bridge message would produce for the given topic. */
    private static Ring ring(String component, String deviceId, String subject, String updateType, String payload) {
        final Ring ring = new Ring();
        final String topic = subject == null
            ? String.format("ring/%s/%s/%s/%s", LOCATION, component, deviceId, updateType)
            : String.format("ring/%s/%s/%s/%s/%s", LOCATION, component, deviceId, subject, updateType);
        ring.setMqttTopic(topic, payload);
        return ring;
    }

    @Test
    void mergesFieldsAcrossTopics() {
        final DeviceState deviceState = new DeviceState();
        deviceState.record(ring("alarm", "device-1", null, "status", "online"));

        final Ring info = ring("alarm", "device-1", "info", "state", null);
        TestStatics.setField(info, "batteryLevel", Integer.valueOf(90));
        TestStatics.setField(info, "batteryStatus", "full");
        TestStatics.setField(info, "tamperStatus", "ok");
        deviceState.record(info);

        final Ring battery = ring("alarm", "device-1", "battery", "attributes", null);
        TestStatics.setField(battery, "batteryLevel", Integer.valueOf(80));
        deviceState.record(battery);

        deviceState.record(ring("alarm", "device-1", "contact", "state", "OFF"));

        final List<DeviceState.DeviceSnapshot> devices = deviceState.getDevices();
        assertEquals(1, devices.size());
        final DeviceState.DeviceSnapshot snapshot = devices.getFirst();
        assertEquals("device-1", snapshot.deviceKey());
        assertEquals("alarm", snapshot.component());
        // the newest value wins; values the later message did not carry are retained
        assertEquals(Integer.valueOf(80), snapshot.batteryLevel());
        assertEquals("full", snapshot.batteryStatus());
        assertEquals("ok", snapshot.tamperStatus());
        assertEquals("online", snapshot.availability());
        assertEquals("OFF", snapshot.states().get("contact"));
        assertNotNull(snapshot.lastSeen());
    }

    @Test
    void labelFallsBackToTheDeviceKeyUntilDiscoveryArrives() {
        final DeviceState deviceState = new DeviceState();
        deviceState.record(ring("alarm", "device-2", null, "status", "online"));
        assertEquals("device-2", deviceState.getDevices().getFirst().label());
        assertNull(deviceState.getDevices().getFirst().model());
    }

    @Test
    void devicesAreOrderedByLabel() {
        final DeviceState deviceState = new DeviceState();
        deviceState.record(ring("alarm", "b-device", null, "status", "online"));
        deviceState.record(ring("alarm", "a-device", null, "status", "online"));
        final List<DeviceState.DeviceSnapshot> devices = deviceState.getDevices();
        assertEquals("a-device", devices.get(0).label());
        assertEquals("b-device", devices.get(1).label());
    }

    @Test
    void binaryPayloadsAreNotRecordedAsState() {
        final DeviceState deviceState = new DeviceState();
        final Ring image = new Ring();
        image.setMqttTopic(String.format("ring/%s/camera/camera-1/snapshot/image", LOCATION), "x".repeat(4096));
        deviceState.record(image);
        assertTrue(deviceState.getDevices().getFirst().states().isEmpty());
    }

    @Test
    void deviceWithoutKeyIsIgnored() {
        final DeviceState deviceState = new DeviceState();
        deviceState.record(new Ring());
        deviceState.record(null);
        assertEquals(0, deviceState.size());
    }

    @Test
    void everyDomainIsReported() {
        final DeviceState deviceState = new DeviceState();
        deviceState.record(ring("camera", "camera-1", null, "status", "online"));
        deviceState.record(ring("chime", "chime-1", null, "status", "online"));
        assertEquals(2, deviceState.size());
        assertTrue(deviceState.getDevices().stream().anyMatch(device -> "camera".equals(device.component())));
        assertTrue(deviceState.getDevices().stream().anyMatch(device -> "chime".equals(device.component())));
    }
}
