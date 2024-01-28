package tailucas.app.device;

import tailucas.app.device.config.Config;

public class Camera extends Device {
    @Override
    public String toString() {
        return "Camera [" + getDeviceKey() + "]";
    }
    @Override
    public boolean mustTriggerOutput(Config deviceConfig) {
        return false;
    }
}
