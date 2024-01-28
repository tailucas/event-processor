package tailucas.app.device;

import tailucas.app.device.config.Config;

public class Camera extends Device {
    @Override
    public boolean mustTriggerOutput(Config deviceConfig) {
        return false;
    }
    @Override
    public String toString() {
        return "Camera [" + getDeviceLabel() + "]";
    }
}
