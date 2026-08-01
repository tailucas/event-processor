package tailucas.app.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CameraTest {

    private static Camera cameraWithImage(byte[] image) {
        final Device device = new Device();
        device.deviceLabel = "doorbell";
        device.location = "Porch";
        device.image = image;
        return new Camera(device);
    }

    @Test
    void noImageDataDoesNotTrigger() {
        final Camera camera = cameraWithImage(null);
        assertFalse(camera.wouldTriggerOutput(null));
        assertEquals("unspecified", camera.getTriggerStateDescription());
    }

    @Test
    void imageDataTriggers() {
        final Camera camera = cameraWithImage(new byte[1024]);
        assertTrue(camera.wouldTriggerOutput(null));
        assertEquals("1024 bytes of image data present", camera.getTriggerStateDescription());
    }

    @Test
    void deviceCharacteristics() {
        final Camera camera = cameraWithImage(null);
        assertEquals("camera", camera.getDeviceType());
        assertEquals(Device.Type.CAMERA, camera.getType());
        assertTrue(camera.isOutput());
        assertTrue(camera.isInput());
        assertEquals("Camera [doorbell]", camera.toString());
    }
}
