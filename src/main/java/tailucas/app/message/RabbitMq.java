package tailucas.app.message;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.msgpack.jackson.dataformat.MessagePackMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;

import io.sentry.Sentry;
import tailucas.app.device.Device;
import tailucas.app.device.Event;
import tailucas.app.device.State;
import tailucas.app.provider.Metrics;

public class RabbitMq implements DeliverCallback {

    private static final Logger log = LoggerFactory.getLogger(RabbitMq.class);

    private static final class StateTypeRef extends TypeReference<State> { }

    private final Metrics metrics;
    private final ExecutorService srv;
    private final Connection connection;

    private final ObjectMapper mapper;

    public RabbitMq(ExecutorService srv, Connection connection) {
        this.srv = srv;
        this.connection = connection;
        this.mapper = new MessagePackMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.metrics = Metrics.getInstance();
    }

    @Override
    public void handle(String consumerTag, Delivery message) throws IOException {
        metrics.postMetric("message", Map.of("type", "rabbitmq"));
        final String source = message.getEnvelope().getRoutingKey();
        final byte[] msgBody = message.getBody();
        // Optional W3C trace context. The body is the primary carrier; the AMQP
        // headers are a fallback for producers that still use the header convention.
        String traceparentValue = null;
        String baggageValue = null;
        final com.rabbitmq.client.AMQP.BasicProperties props = message.getProperties();
        if (props != null && props.getHeaders() != null) {
            final Object traceparentHeader = props.getHeaders().get("traceparent");
            if (traceparentHeader != null) {
                traceparentValue = String.valueOf(traceparentHeader);
            }
            final Object baggageHeader = props.getHeaders().get("baggage");
            if (baggageHeader != null) {
                baggageValue = String.valueOf(baggageHeader);
            }
        }
        try {
            final State deviceUpdate = mapper.readerFor(new StateTypeRef()).readValue(msgBody);
            // Body-level trace context takes precedence over the header fallback.
            if (deviceUpdate.getTraceparent() != null) {
                traceparentValue = deviceUpdate.getTraceparent();
            }
            if (deviceUpdate.getBaggage() != null) {
                baggageValue = deviceUpdate.getBaggage();
            }
            final String traceparent = traceparentValue;
            final String baggage = baggageValue;
            log.atDebug().setMessage("RabbitMQ device state update")
                .addKeyValue("source", source)
                .addKeyValue("device_update", String.valueOf(deviceUpdate))
                .addKeyValue("traceparent_present", traceparent != null)
                .log();
            final List<Device> inputs = deviceUpdate.getInputs();
            if (inputs == null) {
                log.atWarn().setMessage("No inputs provided in device update").addKeyValue("source", source).log();
                return;
            }
            inputs.forEach(device -> {
                final Event event = new Event(connection, source, device);
                if (traceparent != null || baggage != null) {
                    event.setTraceContext(traceparent, baggage);
                }
                srv.execute(event);
            });
        } catch (Exception e) {
            metrics.postMetric("error", Map.of(
                "class", this.getClass().getSimpleName(),
                "exception", e.getClass().getSimpleName()));
            log.atError().setMessage("Event issue")
                .addKeyValue("source", source)
                .addKeyValue("payload_bytes", msgBody.length)
                .setCause(e)
                .log();
            Sentry.captureException(e);
        }
    }
}
