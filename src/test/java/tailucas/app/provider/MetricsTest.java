package tailucas.app.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MetricsTest {

    private Metrics metrics;

    @BeforeEach
    void setUp() {
        // fresh instance per test; metric names below are kept unique per
        // test since the Prometheus default registry is shared JVM-wide
        metrics = new Metrics("Test App", "Test Device");
    }

    @Test
    void nullMetricNameRejected() {
        assertThrows(IllegalArgumentException.class, () -> metrics.postMetric(null));
    }

    @Test
    void counterPostsWithNormalizedTags() {
        final Map<String, String> tags = metrics.postMetric("counter_posts", Map.of("input_label", "Kitchen Smoke"));
        assertEquals(Map.of(
            "application", "Test App",
            "device", "Test Device",
            "input_label", "Kitchen Smoke"), tags);
    }

    @Test
    void tagsAreOptional() {
        final Map<String, String> tags = metrics.postMetric("counter_no_tags");
        assertEquals(Map.of(
            "application", "Test App",
            "device", "Test Device"), tags);
    }

    @Test
    void gaugePostedForValue() {
        final Map<String, String> tags = metrics.postMetric("gauge_value", 42.5);
        assertEquals(Map.of(
            "application", "Test App",
            "device", "Test Device"), tags);
    }

    @Test
    void gaugeWithTags() {
        final Map<String, String> tags = metrics.postMetric("gauge_tagged", Double.valueOf(1.0), Map.of("queue", "events"));
        assertEquals(Map.of(
            "application", "Test App",
            "device", "Test Device",
            "queue", "events"), tags);
    }

    @Test
    void repeatedPostsReuseRegisteredMetric() {
        metrics.postMetric("counter_reused");
        final Map<String, String> tags = metrics.postMetric("counter_reused");
        assertEquals("Test App", tags.get("application"));
    }

    @Test
    void tagKeysAndValuesPassThroughUnchanged() {
        // codifies the current sanitizer behavior (prometheus-metrics-model 1.8.0
        // leaves these label values untouched)
        final Map<String, String> tags = metrics.postMetric("pass_through_metric", Map.of("weird/tag", "a.b"));
        assertEquals("a.b", tags.get("weird/tag"));
    }

    @Test
    void normalizedTagsHandleNullInput() {
        final Map<String, String> tags = metrics.getNormalizedMetricTags(null);
        assertEquals(Map.of(
            "application", "Test App",
            "device", "Test Device"), tags);
    }

    @Test
    void singletonIsShared() {
        assertSame(Metrics.getInstance(), Metrics.getInstance());
    }
}
