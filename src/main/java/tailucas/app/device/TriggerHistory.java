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

    private Map<String, Stack<Instant>> triggerHistory;
    private static final int maxTriggerHistory = 100;

    public TriggerHistory() {
        if (log == null) {
            log = LoggerFactory.getLogger(TriggerHistory.class);
        }
        triggerHistory = new ConcurrentHashMap<>(100);
    }

    public Instant lastTriggered(String deviceKey) {
        var history = triggerHistory.get(deviceKey);
        if (history == null) {
            return null;
        }
        return history.peek();
    }

    public Long secondsSinceLastTriggered(String deviceKey) {
        var lastTriggered = lastTriggered(deviceKey);
        if (lastTriggered == null) {
            return null;
        }
        return Long.valueOf(Duration.between(lastTriggered, Instant.now()).toSeconds());
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
            log.debug("{} has no trigger history.", deviceKey);
            return false;
        }
        var history = triggerHistory.get(deviceKey);
        final int historyLenth = history.size();
        if (historyLenth < times) {
            log.debug("{} has triggered {} times, fewer than {}.", deviceKey, historyLenth, times);
            return false;
        }
        var moment = history.get(history.size()-times);
        final long interval = Duration.between(moment, Instant.now()).toSeconds();
        if (interval > seconds) {
            log.info("{} has triggered {} times across {}s interval beyond {}s.", deviceKey, times, interval, seconds);
            return false;
        } else {
            log.info("{} has triggered {} times across {}s interval within {}s.", deviceKey, times, interval, seconds);
        }
        return true;
    }
}
