package tailucas.app.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SensorTest {

    @Test
    void isActiveNullSafe() {
        final Sensor sensor = new Sensor();
        assertFalse(sensor.isActive());
        sensor.active = Boolean.FALSE;
        assertFalse(sensor.isActive());
        sensor.active = Boolean.TRUE;
        assertTrue(sensor.isActive());
    }

    @Test
    void updateFromUsesDeviceLocation() {
        final Device device = new Device();
        device.deviceId = "id-9";
        device.location = "garage";
        device.timestamp = Long.valueOf(1_700_000_000L);
        device.uptime = Integer.valueOf(60);
        device.type = "sensor";
        final Sensor sensor = new Sensor();
        sensor.inputLabel = "temperature";
        sensor.updateFrom(device);
        assertEquals("id-9", sensor.getDeviceId());
        assertEquals("garage", sensor.getLocation());
        assertEquals("Garage Temperature", sensor.getDeviceKey());
        assertEquals(1_700_000_000L, sensor.timestamp.longValue());
        assertEquals(Integer.valueOf(60), sensor.getUptime());
        assertEquals("sensor", sensor.type);
    }

    @Test
    void updateFromPreservesMessageType() {
        final Device device = new Device();
        device.deviceId = "id-9";
        device.location = "garage";
        device.timestamp = Long.valueOf(1_700_000_000L);
        device.uptime = Integer.valueOf(60);
        device.type = "sensor";
        device.messageType = Generic.MESSAGE_TYPE_HEARTBEAT;
        final Sensor sensor = new Sensor();
        sensor.inputLabel = "temperature";
        sensor.updateFrom(device);
        assertEquals(Generic.MESSAGE_TYPE_HEARTBEAT, sensor.getMessageType());
        assertTrue(sensor.isHeartbeat());
    }

    @Test
    void updateFromFallsBackToInputLocation() {
        final Device device = new Device();
        device.inputLocation = "shed";
        final Sensor sensor = new Sensor();
        sensor.inputLabel = "humidity";
        sensor.updateFrom(device);
        assertEquals("shed", sensor.getLocation());
        assertEquals("Shed Humidity", sensor.getDeviceKey());
    }

    @Test
    void wouldTriggerOutputMirrorsActiveFlag() {
        final Sensor sensor = new Sensor();
        sensor.inputLabel = "temperature";
        sensor.sampleValue = Double.valueOf(25.5);
        sensor.normalValue = Integer.valueOf(20);
        sensor.active = Boolean.TRUE;
        assertTrue(sensor.wouldTriggerOutput(null));
        assertEquals("temperature sample value 25.5 outside normal value 20",
            sensor.getTriggerStateDescription());
        sensor.active = Boolean.FALSE;
        assertFalse(sensor.wouldTriggerOutput(null));
    }

    @Test
    void deviceCharacteristics() {
        final Sensor sensor = new Sensor();
        assertEquals("sensor", sensor.getDeviceType());
        assertTrue(sensor.toString().contains("Sensor"));
    }
}
