package tailucas.app.device;

import tailucas.app.device.config.Config;

interface Trigger {
    public boolean mustTriggerOutput(Config deviceConfig);
}
