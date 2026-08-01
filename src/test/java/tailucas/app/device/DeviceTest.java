package tailucas.app.device;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class DeviceTest {

    private static Device deviceWith(String label, String location, String deviceType) {
        final Device device = new Device();
        device.deviceLabel = label;
        device.location = location;
        device.deviceType = deviceType;
        return device;
    }

    @Test
    void deviceKeyFromLabelAndLocation() {
        assertEquals("Porch Front Door", deviceWith("front door", "Porch", null).getDeviceKey());
    }

    @Test
    void deviceKeyFromLabelOnlyWhenLocationMissing() {
        assertEquals("front door", deviceWith("front door", null, null).getDeviceKey());
    }

    @Test
    void deviceKeyFromLocationAndTypeWhenLabelMissing() {
        assertEquals("Porch Camera", deviceWith(null, "Porch", "camera").getDeviceKey());
    }

    @Test
    void deviceKeyFallsBackToLegacyBaseType() {
        // no label, location or type information at all
        assertEquals("Null Base", new Device().getDeviceKey());
    }

    @Test
    void deviceKeyCachedOnceComputed() {
        final Device device = deviceWith("front door", "Porch", null);
        assertSame(device.getDeviceKey(), device.getDeviceKey());
    }

    @Test
    void explicitDeviceKeyIsUsed() {
        final Device device = deviceWith("front door", "Porch", null);
        device.deviceKey = "Custom Key";
        assertEquals("Custom Key", device.getDeviceKey());
    }

    @Test
    void deviceLabelFallsBackToDeviceKey() {
        final Device device = deviceWith(null, "Porch", "camera");
        assertEquals(device.getDeviceKey(), device.getDeviceLabel());
    }

    @Test
    void deviceTypeLegacyFallback() {
        final Device device = new Device();
        device.type = "camera";
        assertEquals("camera", device.getDeviceType());
    }

    @Test
    void typeEnumMappings() {
        final Device device = new Device();
        assertEquals(Device.Type.BASE, device.getType());
        device.type = "camera";
        assertEquals(Device.Type.CAMERA, device.getType());
        device.type = "contact";
        assertEquals(Device.Type.CONTACT, device.getType());
        device.type = "vibration_detector";
        assertEquals(Device.Type.DETECTOR, device.getType());
        device.type = "smoke_detector";
        assertEquals(Device.Type.DETECTOR, device.getType());
        device.type = "meter";
        assertEquals(Device.Type.METER, device.getType());
        device.type = "sensor";
        assertEquals(Device.Type.SENSOR, device.getType());
    }

    @Test
    void unknownTypeThrows() {
        final Device device = new Device();
        device.type = "bogus";
        assertThrows(IllegalStateException.class, device::getType);
    }

    @Test
    void deviceByTypeMapping() {
        final Device base = deviceWith("label", "loc", null);
        assertSame(base, base.getDeviceByType());

        base.type = "camera";
        assertInstanceOf(Camera.class, base.getDeviceByType());

        base.type = "glass_detector";
        assertInstanceOf(Detector.class, base.getDeviceByType());

        base.type = "sensor";
        assertNull(base.getDeviceByType());
        base.type = "meter";
        assertNull(base.getDeviceByType());
    }

    @Test
    void deviceByTypeCopiesFields() {
        final Device base = deviceWith("Doorbell", "Porch", null);
        base.deviceId = "id-1";
        base.type = "camera";
        final Device camera = base.getDeviceByType();
        assertEquals("Doorbell", camera.deviceLabel);
        assertEquals("id-1", camera.getDeviceId());
        assertEquals("Porch", camera.getLocation());
    }

    @Test
    void timestampDefaultsToNow() {
        final Device device = new Device();
        final Instant before = Instant.now().minusSeconds(1);
        final Instant timestamp = device.getTimestamp();
        final Instant after = Instant.now().plusSeconds(1);
        assertTrue(timestamp.isAfter(before) && timestamp.isBefore(after));
    }

    @Test
    void timestampFromEpochSeconds() {
        final Device device = new Device();
        device.timestamp = Long.valueOf(1_700_000_000L);
        assertEquals(Instant.ofEpochSecond(1_700_000_000L), device.getTimestamp());
    }

    @Test
    void uptimeSecondsNullSafe() {
        final Device device = new Device();
        assertEquals(0, device.getUptimeSeconds());
        device.timestamp = Long.valueOf(1_000L);
        assertEquals(0, device.getUptimeSeconds());
        device.uptime = Integer.valueOf(100);
        assertEquals(900, device.getUptimeSeconds());
    }

    @Test
    void defaults() {
        final Device device = new Device();
        assertTrue(device.isInput());
        assertFalse(device.isOutput());
        assertFalse(device.isHeartbeat());
        assertEquals("unspecified", device.getTriggerStateDescription());
        assertNull(device.getConfig());
    }

    @Test
    void unsupportedOperations() {
        final Device device = new Device();
        assertThrows(UnsupportedOperationException.class, () -> device.wouldTriggerOutput(null));
        assertThrows(UnsupportedOperationException.class, device::lastTriggered);
        assertThrows(UnsupportedOperationException.class, device::triggerGroup);
    }

    @Test
    void imageIsDefensivelyCopied() {
        final Device device = new Device();
        device.image = new byte[] {1, 2, 3};
        final byte[] image = device.getImage();
        assertArrayEquals(new byte[] {1, 2, 3}, image);
        image[0] = 99;
        assertEquals(1, device.getImage()[0]);
        assertNull(new Device().getImage());
    }

    @Test
    void testToString() {
        final Device device = deviceWith("label", "loc", "camera");
        final String s = device.toString();
        assertTrue(s.contains("label"));
        assertTrue(s.contains("loc"));
    }
}
