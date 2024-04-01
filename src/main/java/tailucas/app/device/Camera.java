package tailucas.app.device;

import tailucas.app.device.config.Config;

public class Camera extends Device {
    public Camera(Device device) {
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
    public boolean mustTriggerOutput(Config deviceConfig) {
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
