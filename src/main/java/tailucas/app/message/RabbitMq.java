package tailucas.app.message;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

import org.msgpack.jackson.dataformat.MessagePackMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;

import tailucas.app.device.Event;
import tailucas.app.device.State;

public class RabbitMq implements DeliverCallback {

    private static Logger log = null;

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
    }

    @Override
    public void handle(String consumerTag, Delivery message) throws IOException {
        final String source = message.getEnvelope().getRoutingKey();
        final byte[] msgBody = message.getBody();
        try {
            final State deviceUpdate = mapper.readerFor(new TypeReference<State>() { }).readValue(msgBody);
            log.debug("RabbitMQ device state update: {}", deviceUpdate);
            var outputsTriggers = deviceUpdate.getOutputsTriggered();
            if (outputsTriggers != null) {
                outputsTriggers.forEach(device -> {
                    srv.submit(new Event(connection, source, device.getDeviceByType(), deviceUpdate));
                });
            } else {
                srv.submit(new Event(connection, source, deviceUpdate));
            }
        } catch (Exception e) {
            log.error("{} event issue ({} bytes).", source, msgBody.length, e);
        }
    }
}
