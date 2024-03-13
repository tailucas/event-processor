package tailucas.app.device;

import java.time.Instant;
import java.util.List;

import tailucas.app.device.config.Config;

public interface Generic {
    public String getDeviceKey();
    public String getDeviceLabel();
    public Config getConfig();
    public Instant lastTriggered();
    public boolean mustTriggerOutput(Config config);
    public List<Device> triggerGroup();
}
