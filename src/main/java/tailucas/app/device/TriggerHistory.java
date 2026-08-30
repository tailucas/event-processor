package tailucas.app.device;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TriggerHistory {

    private static final Logger log = LoggerFactory.getLogger(TriggerHistory.class);

    private Map<String, Instant> triggeredSince;
    private Map<String, Stack<Instant>> triggerHistory;
    private static final int maxTriggerHistory = 120;

    public TriggerHistory() {
        triggeredSince = new ConcurrentHashMap<>(100);
        triggerHistory = new ConcurrentHashMap<>(100);
    }

    public synchronized Instant lastTriggered(String deviceKey) {
        var history = triggerHistory.get(deviceKey);
        if (history == null) {
            return null;
        }
        return history.peek();
    }

    public synchronized Long secondsSinceLastTriggered(String deviceKey) {
        var lastTriggered = lastTriggered(deviceKey);
        if (lastTriggered == null) {
            return null;
        }
        return Long.valueOf(Duration.between(lastTriggered, Instant.now()).toSeconds());
    }

    public synchronized void triggered(String deviceKey) {
        var history = triggerHistory.computeIfAbsent(deviceKey, s -> new Stack<>());
        if (history.size() >= maxTriggerHistory) {
            // remove from the head of the list
            final var oldest = history.removeFirst();
            log.atDebug().setMessage("Oldest event trimmed")
                .addKeyValue("device_key", deviceKey)
                .addKeyValue("oldest", oldest)
                .log();
        }
        final Instant now = Instant.now();
        // Staleness guard: if the time since the last trigger exceeds a
        // threshold, reset the ongoing trigger episode. This catches
        // scenarios where a device stops sending events without an explicit
        // untrigger signal (e.g. heartbeats with active=false that bypass
        // the trigger evaluation path, or an actual device offline event).
        if (!history.isEmpty()) {
            final Instant previous = history.peek();
            final long secondsSinceLastEvent = Duration.between(previous, now).toSeconds();
            if (secondsSinceLastEvent > 600) {
                log.atDebug().setMessage("Resetting stale trigger duration start")
                    .addKeyValue("device_key", deviceKey)
                    .addKeyValue("seconds_since_last_event", secondsSinceLastEvent)
                    .log();
                triggeredSince.put(deviceKey, now);
            } else {
                triggeredSince.computeIfAbsent(deviceKey, s -> now);
            }
        } else {
            triggeredSince.put(deviceKey, now);
        }
        history.push(now);
    }

    public synchronized void unTriggered(String deviceKey) {
        triggeredSince.remove(deviceKey);
    }

    public synchronized Long getTriggeredDuration(String deviceKey) {
        final Instant moment = triggeredSince.get(deviceKey);
        if (moment == null) {
            return null;
        }
        final Instant now = Instant.now();
        return Long.valueOf(Duration.between(moment, now).toSeconds());
    }

    public synchronized boolean isTriggeredFor(String deviceKey, int seconds) {
        final Long interval = getTriggeredDuration(deviceKey);
        if (interval == null) {
            return false;
        }
        log.atDebug().setMessage("Device has been triggered for an interval")
            .addKeyValue("device_key", deviceKey)
            .addKeyValue("interval_seconds", interval)
            .addKeyValue("compare_seconds", seconds)
            .log();
        if (interval >= seconds) {
            return true;
        }
        return false;
    }

    public boolean triggeredWithin(String deviceKey, int seconds) {
        return isMultiTriggered(deviceKey, 1, seconds);
    }

    public synchronized boolean isMultiTriggered(String deviceKey, int times, int seconds) {
        if (times <= 0 || seconds <= 0 || times > maxTriggerHistory) {
            throw new IllegalArgumentException(String.format("Invalid inputs for times %s and seconds %s.", times, seconds));
        }
        if (!triggerHistory.containsKey(deviceKey)) {
            log.atDebug().setMessage("Device has no trigger history").addKeyValue("device_key", deviceKey).log();
            return false;
        }
        var history = triggerHistory.get(deviceKey);
        final int historyLenth = history.size();
        // Stack is still an underlying list so indexing works from the tail.
        // Make "times" properly zero-index the list so that 1-times appropriately
        // selects the most recent event
        final int desiredIndex = (historyLenth-1)-(times-1);
        if (desiredIndex < 0) {
            log.atDebug().setMessage("Device has triggered fewer times than required")
                .addKeyValue("device_key", deviceKey)
                .addKeyValue("history_length", historyLenth)
                .addKeyValue("times", times)
                .log();
            return false;
        }
        log.atDebug().setMessage("Trigger history bounds")
            .addKeyValue("device_key", deviceKey)
            .addKeyValue("first_event", history.firstElement())
            .addKeyValue("last_event", history.lastElement())
            .log();
        Instant moment = null;
        try {
            moment = history.get(desiredIndex);
        } catch (ArrayIndexOutOfBoundsException e) {
            log.atDebug().setMessage("History index invalid relative to history length")
                .addKeyValue("device_key", deviceKey)
                .addKeyValue("history_index", desiredIndex)
                .addKeyValue("times", times)
                .addKeyValue("history_length", historyLenth)
                .log();
            return false;
        }
        final Instant now = Instant.now();
        final long interval = Duration.between(moment, now).toSeconds();
        log.atDebug().setMessage("Comparing trigger moment against history")
            .addKeyValue("device_key", deviceKey)
            .addKeyValue("moment", moment)
            .addKeyValue("now", now)
            .addKeyValue("history_items", history.size())
            .log();
        if (interval >= seconds) {
            log.atDebug().setMessage("Device triggers fall outside the required interval")
                .addKeyValue("device_key", deviceKey)
                .addKeyValue("times", times)
                .addKeyValue("interval_seconds", interval)
                .addKeyValue("compare_seconds", seconds)
                .log();
            return false;
        } else {
            log.atDebug().setMessage("Device triggers fall within the required interval")
                .addKeyValue("device_key", deviceKey)
                .addKeyValue("times", times)
                .addKeyValue("interval_seconds", interval)
                .addKeyValue("compare_seconds", seconds)
                .log();
        }
        return true;
    }
}
