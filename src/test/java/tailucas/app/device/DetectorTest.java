package tailucas.app.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DetectorTest {

    private static Detector detectorInState(String state) {
        final Device device = new Device();
        device.deviceLabel = "Kitchen Smoke";
        device.state = state;
        return new Detector(device);
    }

    @ParameterizedTest
    @ValueSource(strings = {"active", "on", "triggered", "ACTIVE", "On", "TRIGGERED"})
    void activeStatesTrigger(String state) {
        final Detector detector = detectorInState(state);
        assertTrue(detector.wouldTriggerOutput(null));
        assertEquals(String.format("Kitchen Smoke is in an active state %s", state),
            detector.getTriggerStateDescription());
    }

    @ParameterizedTest
    @ValueSource(strings = {"off", "idle", "clear", "inactive"})
    void inactiveStatesDoNotTrigger(String state) {
        assertFalse(detectorInState(state).wouldTriggerOutput(null));
    }

    @Test
    void nullStateDoesNotTrigger() {
        assertFalse(detectorInState(null).wouldTriggerOutput(null));
    }

    @Test
    void deviceCharacteristics() {
        final Detector detector = detectorInState(null);
        assertEquals("detector", detector.getDeviceType());
        assertEquals(Device.Type.DETECTOR, detector.getType());
        assertFalse(detector.isOutput());
        assertTrue(detector.isInput());
        assertEquals("Detector [Kitchen Smoke]", detector.toString());
    }
}
