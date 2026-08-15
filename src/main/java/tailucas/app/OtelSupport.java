package tailucas.app;

import java.util.List;

import org.apache.logging.log4j.core.config.plugins.util.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.instrumentation.log4j.appender.v2_17.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

public class OtelSupport {

    static {
        // Register log4j2 plugin packages that a Spring Boot nested (fat) jar
        // would otherwise fail to discover: the OTEL appender and the JSON
        // template layout. Duplicate categories load first-wins, so registered
        // packages override any stale cached plugin index.
        PluginManager.addPackages(List.of(
            "io.opentelemetry.instrumentation.log4j.appender.v2_17",
            "org.apache.logging.log4j.layout.template.json"));
    }

    private static final Logger log = LoggerFactory.getLogger(OtelSupport.class);

    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initialized = false;
    private static volatile String scopeName = "event-processor";
    private static volatile OpenTelemetrySdk sdk = null;

    private OtelSupport() { }

    /**
     * Bootstraps the auto-configured OpenTelemetry SDK. Endpoint/protocol come
     * from the standard OTEL_* env vars. {@code service.instance.id} is set from
     * DEVICE_NAME; {@code service.name} is left to the SDK's env detector (never
     * hardcoded). Safe to call more than once: subsequent calls return the
     * already-initialized SDK.
     */
    public static OpenTelemetrySdk init() {
        if (initialized) {
            return sdk;
        }
        synchronized (INIT_LOCK) {
            if (initialized) {
                return sdk;
            }
            final String appName = System.getenv("APP_NAME");
            if (appName != null && !appName.isBlank()) {
                scopeName = appName;
            }
            final String deviceName = System.getenv("DEVICE_NAME");
            final OpenTelemetrySdk built = AutoConfiguredOpenTelemetrySdk.builder()
                .setResultAsGlobal()
                .addResourceCustomizer((resource, config) -> {
                    if (deviceName == null || deviceName.isBlank()) {
                        return resource;
                    }
                    return resource.toBuilder()
                        .put(AttributeKey.stringKey("service.instance.id"), deviceName)
                        .build();
                })
                .build()
                .getOpenTelemetrySdk();
            // Bridge log4j2: log records emitted inside an active span carry
            // trace_id/span_id, giving log<->trace correlation.
            OpenTelemetryAppender.install(built);
            sdk = built;
            initialized = true;
            log.atInfo().setMessage("OpenTelemetry initialized")
                .addKeyValue("scope_name", scopeName)
                .addKeyValue("sdk_disabled", Boolean.getBoolean("otel.sdk.disabled"))
                .log();
            return built;
        }
    }

    public static OpenTelemetry getOpenTelemetry() {
        final OpenTelemetrySdk active = sdk;
        return active != null ? active : OpenTelemetry.noop();
    }

    public static Tracer getTracer() {
        return getOpenTelemetry().getTracer(scopeName);
    }

    public static Meter getMeter() {
        return getOpenTelemetry().getMeter(scopeName);
    }

    /**
     * Flushes then closes the SDK. Guarded so exporter errors never break app
     * teardown. Silently no-ops if never initialized.
     */
    public static void shutdown() {
        final OpenTelemetrySdk active = sdk;
        if (active == null) {
            return;
        }
        try {
            active.getSdkTracerProvider().forceFlush();
        } catch (Throwable t) {
            log.atWarn().setMessage("Error flushing OTEL trace provider").setCause(t).log();
        }
        try {
            active.close();
        } catch (Throwable t) {
            log.atWarn().setMessage("Error closing OTEL SDK").setCause(t).log();
        }
        initialized = false;
        sdk = null;
    }
}