package tailucas.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;

@SpringBootTest
class AppTest {

    @Autowired
    private AppProperties appProperties;

    @Value("${spring.threads.virtual.enabled}")
    private Boolean virtualThreadsEnabled;

    @Value("${management.endpoints.web.exposure.include}")
    private String managementSettings;

    @Test
    void addition() {
        assertEquals(2, 1+1);
    }

    @Test
    void testPropertyValue() {
        assertTrue(virtualThreadsEnabled);
    }

    @Test
    void testManagementSettings() {
        assertEquals("health,info,loggers", managementSettings);
    }

    @Test
    void testAppProperties() {
        assertEquals("test_control_exchange", appProperties.getMessageControlExchangeName());
        assertEquals(30000, appProperties.getMessageControlExpiryMs());
        assertEquals("test_project", appProperties.getProjectName());
    }

    @Test
    void otelSdkBuildsAndRecordsByDefault() {
        // keep the SDK recording but prevent it from reaching for a collector
        System.setProperty("otel.traces.exporter", "none");
        System.setProperty("otel.metrics.exporter", "none");
        System.setProperty("otel.logs.exporter", "none");
        try {
            final OpenTelemetrySdk sdk = OtelSupport.init();
            try {
                final Span span = sdk.getTracer("test").spanBuilder("probe").startSpan();
                try {
                    assertTrue(span.getSpanContext().isValid(), "span context must be valid");
                    assertTrue(span.isRecording(), "span must be recording when SDK is enabled");
                } finally {
                    span.end();
                }
            } finally {
                OtelSupport.shutdown();
                GlobalOpenTelemetry.resetForTest();
            }
        } finally {
            System.clearProperty("otel.traces.exporter");
            System.clearProperty("otel.metrics.exporter");
            System.clearProperty("otel.logs.exporter");
        }
    }
}