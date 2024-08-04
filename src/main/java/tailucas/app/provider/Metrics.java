package tailucas.app.provider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.model.snapshots.PrometheusNaming;
import io.sentry.Sentry;

public class Metrics {

    private static Logger log = null;
    private static Metrics singleton = null;

    private String appName = null;
    private String deviceName = null;

    private Map<String, Counter> counters = null;
    private Map<String, Gauge> gauges = null;

    private Metrics() {
        log = LoggerFactory.getLogger(Metrics.class);
        final var env = System.getenv();
        appName = env.get("APP_NAME");
        deviceName = env.get("DEVICE_NAME");
        counters = new ConcurrentHashMap<>();
        gauges = new ConcurrentHashMap<>();
    }

    public static synchronized Metrics getInstance() {
        if (singleton == null) {
            singleton = new Metrics();
        }
        return singleton;
    }

    public Map<String, String> getNormalizedMetricTags(Map<String, String> tags) {
        final Map<String, String> metricTags = new LinkedHashMap<>();
        metricTags.put("application", PrometheusNaming.sanitizeLabelName(appName));
        metricTags.put("device", PrometheusNaming.sanitizeLabelName(deviceName));
        if (tags != null) {
            tags.forEach((k,v) -> {
                metricTags.put(k, PrometheusNaming.sanitizeLabelName(v));
            });
        }
        return metricTags;
    }

    public Map<String, String> postMetric(String name) {
        return postMetric(name, null, null);
    }

    public Map<String, String> postMetric(String name, Map<String,String> tags) {
        return postMetric(name, null, tags);
    }

    public Map<String, String> postMetric(String name, double value) {
        return postMetric(name, value, null);
    }

    public Map<String, String> postMetric(String name, Double value, Map<String,String> tags) {
        if (name == null) {
            throw new IllegalArgumentException("No metric name specified.");
        }
        final String metricName = PrometheusNaming.sanitizeMetricName(name);
        final var metricTags = getNormalizedMetricTags(tags);
        final String[] metricTagKeys = metricTags.keySet().toArray(new String[metricTags.size()]);
        final String[] metricTagValues = metricTags.values().toArray(new String[metricTags.size()]);
        try {
            if (value == null) {
                Counter counter = counters.computeIfAbsent(metricName, c -> Counter.builder()
                    .name(metricName)
                    .labelNames(metricTagKeys)
                    .register());
                counter.labelValues(metricTagValues).inc();
            } else {
                Gauge gauge = gauges.computeIfAbsent(metricName, g -> Gauge.builder()
                    .name(metricName)
                    .labelNames(metricTagKeys)
                    .register());
                gauge.labelValues(metricTagValues).set(value);
            }
        } catch (RuntimeException e) {
            log.error("Cannot create metric object for metric {}: {}", metricName, e.getMessage(), e);
            Sentry.captureException(e);
        }
        return metricTags;
    }
}
