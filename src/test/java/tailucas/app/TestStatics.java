package tailucas.app;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.DataPointSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import tailucas.app.device.Event;
import tailucas.app.device.TriggerHistory;
import tailucas.app.device.config.HAConfig;
import tailucas.app.provider.Metrics;

/**
 * Shared helpers for wiring static and singleton collaborators in tests,
 * plus reflective access where production code deliberately keeps state private.
 */
public final class TestStatics {

    public static final String TEST_APP_NAME = "test_app";
    public static final String TEST_DEVICE_NAME = "test_device";

    private TestStatics() { }

    /**
     * Gives the shared Metrics instance usable label values.
     * APP_NAME/DEVICE_NAME env vars are unset under surefire and the
     * Prometheus sanitizer rejects null label values.
     */
    public static void configureTestMetrics() {
        final Metrics metrics = Metrics.getInstance();
        setField(metrics, "appName", TEST_APP_NAME);
        setField(metrics, "deviceName", TEST_DEVICE_NAME);
    }

    /** Clears Event's static trigger histories and escalation map between tests. */
    public static void clearEventState() {
        for (String fieldName : new String[] {"triggerLatchHistory", "triggerMultiHistory", "triggerOutputHistory"}) {
            clearTriggerHistory((TriggerHistory) getStaticField(Event.class, fieldName));
        }
        escalations().clear();
        unconfiguredDevices().clear();
    }

    @SuppressWarnings("unchecked")
    public static Set<String> unconfiguredDevices() {
        return (Set<String>) getStaticField(Event.class, "unconfiguredDeviceKeys");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, String> escalations() {
        return (Map<String, String>) getStaticField(Event.class, "recentEscalations");
    }

    @SuppressWarnings("unchecked")
    public static void clearTriggerHistory(TriggerHistory history) {
        ((Map<String, Instant>) getField(history, "triggeredSince")).clear();
        ((Map<String, Stack<Instant>>) getField(history, "triggerHistory")).clear();
    }

    /**
     * Injects a historical trigger event without waiting for wall-clock time to pass.
     * Push the oldest events first so that the most recent event ends up on top.
     */
    @SuppressWarnings("unchecked")
    public static void injectTriggerAt(TriggerHistory history, String deviceKey, Instant when) {
        ((Map<String, Stack<Instant>>) getField(history, "triggerHistory"))
            .computeIfAbsent(deviceKey, k -> new Stack<>()).push(when);
        ((Map<String, Instant>) getField(history, "triggeredSince")).putIfAbsent(deviceKey, when);
    }

    /** Builds a Home Assistant discovery config with a nested device (Jackson cannot
     *  instantiate the non-static inner HADevice class, so reflection is used). */
    public static HAConfig haConfig(String deviceName, List<String> deviceIds) {
        final HAConfig haConfig = new HAConfig();
        final var haDevice = new HAConfig.HADevice();
        setField(haDevice, "ids", deviceIds);
        setField(haDevice, "name", deviceName);
        setField(haDevice, "mf", "Ring");
        setField(haDevice, "mdl", "Alarm");
        setField(haConfig, "device", haDevice);
        return haConfig;
    }

    /**
     * Scrapes the shared Prometheus registry and returns the label set of every
     * data point of the named metric, as label name/value pairs. This asserts on
     * what actually leaves the process rather than on internal counter state.
     */
    public static List<Map<String, String>> metricLabelSets(String metricName) {
        final List<Map<String, String>> labelSets = new ArrayList<>();
        for (MetricSnapshot snapshot : PrometheusRegistry.defaultRegistry.scrape()) {
            if (!snapshot.getMetadata().getName().equals(metricName)) {
                continue;
            }
            for (DataPointSnapshot dataPoint : snapshot.getDataPoints()) {
                labelSets.add(labelsOf(dataPoint));
            }
        }
        return labelSets;
    }

    /**
     * Returns the value of the named metric's data point carrying the given label,
     * or null when no such series has been observed. Handles counters and gauges.
     */
    public static Double metricValue(String metricName, String labelName, String labelValue) {
        for (MetricSnapshot snapshot : PrometheusRegistry.defaultRegistry.scrape()) {
            if (!snapshot.getMetadata().getName().equals(metricName)) {
                continue;
            }
            for (DataPointSnapshot dataPoint : snapshot.getDataPoints()) {
                if (!labelValue.equals(dataPoint.getLabels().get(labelName))) {
                    continue;
                }
                if (dataPoint instanceof CounterSnapshot.CounterDataPointSnapshot counter) {
                    return counter.getValue();
                }
                if (dataPoint instanceof GaugeSnapshot.GaugeDataPointSnapshot gauge) {
                    return gauge.getValue();
                }
            }
        }
        return null;
    }

    private static Map<String, String> labelsOf(DataPointSnapshot dataPoint) {
        final Labels dataPointLabels = dataPoint.getLabels();
        final Map<String, String> labels = new LinkedHashMap<>();
        for (int i = 0; i < dataPointLabels.size(); i++) {
            labels.put(dataPointLabels.getName(i), dataPointLabels.getValue(i));
        }
        return labels;
    }

    public static Object getStaticField(Class<?> clazz, String fieldName) {
        try {
            final Field field = findField(clazz, fieldName);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Object getField(Object target, String fieldName) {
        try {
            final Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void setField(Object target, String fieldName, Object value) {
        try {
            final Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void setStaticField(Class<?> clazz, String fieldName, Object value) {
        try {
            final Field field = findField(clazz, fieldName);
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
