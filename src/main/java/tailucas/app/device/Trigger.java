package tailucas.app.device;

import java.util.List;
import java.time.Instant;

import tailucas.app.device.config.Config;

interface Trigger {
    public Instant lastTriggered();
    public boolean mustTriggerOutput(Config deviceConfig);
    public List<Device> triggerGroup();
}
