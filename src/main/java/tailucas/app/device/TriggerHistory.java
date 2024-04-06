package tailucas.app.device;

import java.time.Duration;
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
    private static final int maxTriggerHistory = 100;

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

    public synchronized void triggered(String deviceKey) {
        var history = triggerHistory.computeIfAbsent(deviceKey, s -> new Stack<Instant>());
        if (history.size() >= maxTriggerHistory) {
            history.removeLast();
        }
        history.push(Instant.now());
    }

    public boolean triggeredWithin(String deviceKey, int seconds) {
        return isMultiTriggered(deviceKey, 1, seconds);
    }

    public boolean isMultiTriggered(String deviceKey, int times, int seconds) {
        if (times <= 0 && seconds <= 0) {
            throw new RuntimeException(String.format("Invalid inputs for times %s and seconds %s."));
        }
        if (!triggerHistory.containsKey(deviceKey)) {
            log.info("{} has no trigger history.", deviceKey);
            return false;
        }
        var history = triggerHistory.get(deviceKey);
        final int historyLenth = history.size();
        if (historyLenth < times) {
            log.info("{} has triggered less than {} times.", deviceKey, historyLenth);
            return false;
        }
        var moment = history.get(times-1);
        final long interval = Duration.between(Instant.now(), moment).toSeconds();
        if (interval > seconds) {
            log.info("{} has triggered {} times across an interval ({}s) greater than {}s.", deviceKey, historyLenth, interval, seconds);
            return false;
        }
        return true;
    }
}
