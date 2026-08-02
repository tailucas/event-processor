package tailucas.app.device;

import java.util.Locale;

import tailucas.app.device.config.InputConfig;

public class Camera extends Device {
    public Camera(Device device) {
        super();
        setFieldsFrom(device);
    }
    @Override
    public String getDeviceType() {
        return Type.CAMERA.name().toLowerCase(Locale.ROOT);
    }
    @Override
    public boolean wouldTriggerOutput(InputConfig deviceConfig) {
        // any image data is relevant
        if (image == null) {
            log.atWarn().setMessage("No image data").addKeyValue("device_label", getDeviceLabel()).log();
            return false;
        }
        triggerStateDescription = String.format("%s bytes of image data present", image.length);
        return true;
    }
    @Override
    public Type getType() {
        return Type.CAMERA;
    }
    @Override
    public Boolean isOutput() {
        return Boolean.TRUE;
    }
    @Override
    public String toString() {
        return "Camera [" + getDeviceLabel() + "]";
    }
}
