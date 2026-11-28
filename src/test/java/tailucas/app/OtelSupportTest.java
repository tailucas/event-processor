package tailucas.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;

/**
 * The inbound half of trace propagation: an HTTP request from the web application has
 * to continue that command's trace, not start a new one.
 *
 * <p>There is no servlet instrumentation on the classpath, so this extraction is the
 * only thing that makes the hop work; the endpoint uses it as the parent of its
 * {@code snapshot.request} span.
 */
class OtelSupportTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + SPAN_ID + "-01";

    /** Propagators only: the extraction under test needs no exporter or tracer. */
    private static OpenTelemetry propagatorOnly() {
        return OpenTelemetrySdk.builder()
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
    }

    @Test
    void anInboundCarrierContinuesTheCallersTrace() {
        final Context parent = OtelSupport.extractParent(propagatorOnly(), Map.of("traceparent", TRACEPARENT));
        final SpanContext spanContext = Span.fromContext(parent).getSpanContext();
        assertTrue(spanContext.isValid(), "the caller's context must be usable as a parent");
        assertEquals(TRACE_ID, spanContext.getTraceId());
        assertEquals(SPAN_ID, spanContext.getSpanId());
    }

    @Test
    void anUntracedRequestHasNoParentSoTheServerSpanStartsATrace() {
        assertEquals(Context.root(), OtelSupport.extractParent(propagatorOnly(), Map.of()));
        assertEquals(Context.root(), OtelSupport.extractParent(propagatorOnly(), null));
    }
}
