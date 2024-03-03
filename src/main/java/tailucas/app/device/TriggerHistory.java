package tailucas.app.device;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TriggerHistory {

    private static Logger log = null;
    private static TriggerHistory singleton = null;

    private Map<String, Instant> triggerHistory;

    private TriggerHistory() {
        log = LoggerFactory.getLogger(TriggerHistory.class);
        triggerHistory = new HashMap<>(100);
    }

    public static synchronized TriggerHistory getInstance() {
        if (singleton == null) {
            singleton = new TriggerHistory();
        }
        return singleton;
    }

    public Instant lastTriggered(String deviceKey) {
        return triggerHistory.get(deviceKey);
    }

    public void triggered(String deviceKey) {
        triggerHistory.put(deviceKey, Instant.now());
    }
}
