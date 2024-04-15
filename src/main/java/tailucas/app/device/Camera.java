package tailucas.app.device;

import tailucas.app.device.config.InputConfig;

public class Camera extends Device {
    public Camera(Device device) {
        super();
        this.deviceId = device.deviceId;
        this.deviceKey = device.deviceKey;
        this.deviceLabel = device.deviceLabel;
        this.location = device.location;
        this.name = device.name;
        this.image = device.image;
        this.storageUrl = device.storageUrl;
        this.timestamp = device.timestamp;
    }
    @Override
    public String getDeviceType() {
        return Type.CAMERA.name().toLowerCase();
    }
    @Override
    public boolean wouldTriggerOutput(InputConfig deviceConfig) {
        // any image data is relevant
        if (image == null) {
            log.warn("{} has no image data.", getDeviceLabel());
            return false;
        }
        triggerStateDescription = String.format("%s bytes of image data present.", image.length);
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
