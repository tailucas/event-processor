package tailucas.app.device;

import java.time.Instant;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TriggerHistory {

    private static Logger log = null;
    private static TriggerHistory singleton = null;

    private Map<String, Stack<Instant>> triggerHistory;

    private TriggerHistory() {
        log = LoggerFactory.getLogger(TriggerHistory.class);
        triggerHistory = new ConcurrentHashMap<>(100);
    }

    public static synchronized TriggerHistory getInstance() {
        if (singleton == null) {
            singleton = new TriggerHistory();
        }
        return singleton;
    }

    public Instant lastTriggered(String deviceKey) {
        var history = triggerHistory.get(deviceKey);
        if (history == null) {
            return null;
        }
        return history.peek();
    }

    public void triggered(String deviceKey) {
        var history = triggerHistory.computeIfAbsent(deviceKey, s -> new Stack<Instant>());
        history.push(Instant.now());
    }

    public boolean isMultiTriggered(int times, int seconds) {
        if (times <= 0 && seconds <= 0) {
            throw new RuntimeException(String.format("Invalid inputs for times %s and seconds %s."));
        }
        // TODO
        return false;
    }
}
