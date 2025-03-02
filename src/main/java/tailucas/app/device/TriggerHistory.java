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

    private Map<String, Instant> triggeredSince;
    private Map<String, Stack<Instant>> triggerHistory;
    private static final int maxTriggerHistory = 120;

    public TriggerHistory() {
        if (log == null) {
            log = LoggerFactory.getLogger(TriggerHistory.class);
        }
        triggeredSince = new ConcurrentHashMap<>(100);
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
            // remove from the head of the list
            final var oldest = history.removeFirst();
            log.debug("{} oldest event trimmed: {}", deviceKey, oldest);
        }
        history.push(Instant.now());
        triggeredSince.computeIfAbsent(deviceKey, s -> Instant.now());
    }

    public void unTriggered(String deviceKey) {
        triggeredSince.remove(deviceKey);
    }

    public Long getTriggeredDuration(String deviceKey) {
        final Instant moment = triggeredSince.get(deviceKey);
        if (moment == null) {
            return null;
        }
        final Instant now = Instant.now();
        return Long.valueOf(Duration.between(moment, now).toSeconds());
    }

    public boolean isTriggeredFor(String deviceKey, int seconds) {
        final Long interval = getTriggeredDuration(deviceKey);
        if (interval == null) {
            return false;
        }
        log.debug("{} has been triggered for {}s (comparing against {}s)", deviceKey, interval, seconds);
        if (interval >= seconds) {
            return true;
        }
        return false;
    }

    public boolean triggeredWithin(String deviceKey, int seconds) {
        return isMultiTriggered(deviceKey, 1, seconds);
    }

    public boolean isMultiTriggered(String deviceKey, int times, int seconds) {
        if (times <= 0 || seconds <= 0 || times > maxTriggerHistory) {
            throw new RuntimeException(String.format("Invalid inputs for times %s and seconds %s."));
        }
        if (!triggerHistory.containsKey(deviceKey)) {
            log.debug("{} has no trigger history.", deviceKey);
            return false;
        }
        var history = triggerHistory.get(deviceKey);
        final int historyLenth = history.size();
        // Stack is still an underlying list so indexing works from the tail.
        // Make "times" properly zero-index the list so that 1-times appropriately
        // selects the most recent event
        final int desiredIndex = (historyLenth-1)-(times-1);
        if (desiredIndex < 0) {
            log.debug("{} has triggered {} times, fewer than {}.", deviceKey, historyLenth, times);
            return false;
        }
        log.debug("{} first event {}, last {}", deviceKey, history.firstElement(), history.lastElement());
        Instant moment = null;
        try {
            moment = history.get(desiredIndex);
        } catch (ArrayIndexOutOfBoundsException e) {
            log.debug("{} history index {} (times is {}) invalid relative to history length {}.", deviceKey, desiredIndex, times, historyLenth);
            return false;
        }
        final Instant now = Instant.now();
        final long interval = Duration.between(moment, now).toSeconds();
        log.debug("{} comparing moment {} with now {} against history ({} items)", deviceKey, moment, now, history.size());
        if (interval >= seconds) {
            log.debug("{} has triggered {} times over a {}s interval in at least {}s.", deviceKey, times, interval, seconds);
            return false;
        } else {
            log.debug("{} has triggered {} times over a {}s interval in under {}s.", deviceKey, times, interval, seconds);
        }
        return true;
    }
}
