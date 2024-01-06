package tailucas.app.message;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

import org.msgpack.jackson.dataformat.MessagePackMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;

import tailucas.app.device.Event;
import tailucas.app.device.State;

public class RabbitMq implements DeliverCallback {

    private static Logger log = LoggerFactory.getLogger(RabbitMq.class);

    private ExecutorService srv = null;
    private ObjectMapper mapper = null;

    public RabbitMq(ExecutorService srv) {
        this.srv = srv;
        this.mapper = new MessagePackMapper();
    }

    @Override
    public void handle(String consumerTag, Delivery message) throws IOException {
        try {
            final String source = message.getEnvelope().getRoutingKey();
            final byte[] msgBody = message.getBody();
            State deviceUpdate = mapper.readerFor(new TypeReference<State>() { }).readValue(msgBody);
            if (deviceUpdate.outputs_triggered != null) {
                deviceUpdate.outputs_triggered.forEach(device -> {
                    srv.submit(new Event(source, device, deviceUpdate));
                });
            } else {
                srv.submit(new Event(source, deviceUpdate));
            }
        } catch (Exception e) {
            log.warn(e.getMessage());
        }
    }
}