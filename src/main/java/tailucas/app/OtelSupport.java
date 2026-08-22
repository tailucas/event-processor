package tailucas.app;

import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;

import org.apache.logging.log4j.core.config.plugins.util.PluginManager;
import org.apache.logging.log4j.jul.Log4jBridgeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.log4j.appender.v2_17.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

import tailucas.app.device.Generic;

public class OtelSupport {

    static {
        // Register log4j2 plugin packages that a Spring Boot nested (fat) jar
        // would otherwise fail to discover: the OTEL appender and the JSON
        // template layout. Duplicate categories load first-wins, so registered
        // packages override any stale cached plugin index.
        PluginManager.addPackages(List.of(
            "io.opentelemetry.instrumentation.log4j.appender.v2_17",
            "org.apache.logging.log4j.layout.template.json"));
        // Bridge java.util.logging (used by OTEL autoconfigure and other libs)
        // into Log4j2 so their messages render as structured JSON, not raw JUL.
        installJulBridge();
    }

    private static void installJulBridge() {
        try {
            final java.util.logging.Logger rootLogger = LogManager.getLogManager().getLogger("");
            final Handler[] handlers = rootLogger.getHandlers();
            for (Handler handler : handlers) {
                rootLogger.removeHandler(handler);
            }
            rootLogger.addHandler(new Log4jBridgeHandler());
            rootLogger.setLevel(Level.ALL);
        } catch (Throwable t) {
            // Never let logging setup break application startup.
            System.err.println("Cannot install JUL->Log4j2 bridge: " + t);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(OtelSupport.class);

    /**
     * Span attribute key that marks a span as originating from a heartbeat
     * message. The SDK sampler drops such spans so low-signal heartbeat traffic
     * does not pollute the trace backend.
     */
    public static final AttributeKey<String> MESSAGE_TYPE_ATTRIBUTE = AttributeKey.stringKey("event.message_type");

    private static final String HEARTBEAT_MESSAGE_TYPE = Generic.MESSAGE_TYPE_HEARTBEAT;

    /**
     * Drops spans whose attributes mark them as heartbeat messages. All other
     * spans are recorded and sampled. Applied directly on the tracer provider;
     * heartbeat lifetime is short and end-to-end visibility is not required.
     */
    private static final Sampler HEARTBEAT_SAMPLER = new Sampler() {
        @Override
        public SamplingResult shouldSample(Context parentContext, String traceId, String name, SpanKind spanKind,
                Attributes attributes, List<LinkData> parentLinks) {
            final String messageType = attributes.get(MESSAGE_TYPE_ATTRIBUTE);
            if (HEARTBEAT_MESSAGE_TYPE.equalsIgnoreCase(messageType)) {
                return SamplingResult.drop();
            }
            return SamplingResult.recordAndSample();
        }
        @Override
        public String getDescription() {
            return "ParentOrSelfSampler";
        }
    };

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
            // Log the OTLP env vars so it is clear the JVM loaded them.
            log.atInfo().setMessage("OTLP exporter configuration")
                .addKeyValue("otel_exporter_otlp_protocol", System.getenv("OTEL_EXPORTER_OTLP_PROTOCOL"))
                .addKeyValue("otel_exporter_otlp_endpoint", System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"))
                .log();
            // Force HTTP/protobuf: autoconfigure reads the system property with
            // highest precedence, so this deterministically selects the HTTP
            // exporter regardless of env-var propagation into the JVM.
            System.setProperty("otel.exporter.otlp.protocol", "http/protobuf");
            OpenTelemetrySdk built;
            try {
                built = AutoConfiguredOpenTelemetrySdk.builder()
                    .addResourceCustomizer((resource, config) -> {
                        if (deviceName == null || deviceName.isBlank()) {
                            return resource;
                        }
                        return resource.toBuilder()
                            .put(AttributeKey.stringKey("service.instance.id"), deviceName)
                            .build();
                    })
                    .addTracerProviderCustomizer((provider, config) -> provider
                        .setSampler(HEARTBEAT_SAMPLER))
                    .build()
                    .getOpenTelemetrySdk();
            } catch (RuntimeException e) {
                // Surface the real cause: autoconfigure logs a generic
                // "Error encountered during autoconfiguration" via JUL and
                // hides the underlying failure. Fall back to a no-op SDK so the
                // application keeps running without telemetry.
                log.atError().setMessage("OpenTelemetry autoconfiguration failed; continuing with no-op SDK")
                    .setCause(e)
                    .log();
                built = OpenTelemetrySdk.builder().build();
            }
            // Register as the global best-effort. Another component (e.g. Sentry)
            // may have already locked the global via GlobalOpenTelemetry.get();
            // the SDK remains fully usable through getOpenTelemetry() regardless.
            try {
                GlobalOpenTelemetry.set(built);
            } catch (IllegalStateException e) {
                log.atWarn().setMessage("OpenTelemetry global already set; using SDK via OtelSupport accessor")
                    .setCause(e)
                    .log();
            }
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