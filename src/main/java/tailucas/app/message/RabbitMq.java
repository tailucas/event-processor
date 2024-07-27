package tailucas.app.message;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.msgpack.jackson.dataformat.MessagePackMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
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

    private static Logger log = null;

    private Metrics metrics = null;

    private ExecutorService srv = null;
    private Connection connection = null;

    private ObjectMapper mapper = null;

    public RabbitMq(ExecutorService srv, Connection connection) {
        if (log == null) {
            log = LoggerFactory.getLogger(RabbitMq.class);
        }
        this.srv = srv;
        this.connection = connection;
        this.mapper = new MessagePackMapper();
        this.metrics = Metrics.getInstance();
    }

    @Override
    public void handle(String consumerTag, Delivery message) throws IOException {
        metrics.postMetric("message", 1f, Map.of("type", "rabbitmq"));
        final String source = message.getEnvelope().getRoutingKey();
        final byte[] msgBody = message.getBody();
        try {
            final State deviceUpdate = mapper.readerFor(new TypeReference<State>() { }).readValue(msgBody);
            log.debug("{}: RabbitMQ device state update: {}", source, deviceUpdate);
            final List<Device> inputs = deviceUpdate.getInputs();
            if (inputs == null) {
                log.warn("{}: no inputs provide in device update.", source);
                return;
            }
            inputs.forEach(device -> {
                srv.submit(new Event(connection, source, device));
            });
        } catch (Exception e) {
            metrics.postMetric("error", 1f, Map.of(
                "class", this.getClass().getSimpleName(),
                "exception", e.getClass().getSimpleName()));
            log.error("{} event issue ({} bytes) ({})", source, msgBody.length, e.getMessage());
            Sentry.captureException(e);
        } finally {
            metrics.postMetric("error", 0f, Map.of(
                "class", this.getClass().getSimpleName()));
        }
    }
}
