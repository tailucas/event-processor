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
        this.storageUrl = device.storageUrl;
        this.timestamp = device.timestamp;
    }
    @Override
    public String getDeviceType() {
        return Type.CAMERA.name().toLowerCase();
    }
    @Override
    public boolean mustTriggerOutput(InputConfig deviceConfig) {
        if (!deviceConfig.isDeviceEnabled()) {
            return false;
        }
        // any image data is relevant
        return true;
    }
    @Override
    public Type getType() {
        return Type.CAMERA;
    }
    @Override
    public boolean isOutput() {
        return true;
    }
    @Override
    public String toString() {
        return "Camera [" + getDeviceLabel() + "]";
    }
}
