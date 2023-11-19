package tailucas.app;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.MapValue;
import org.msgpack.value.Value;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

public class RabbitMq extends Thread {

    private static final Logger log = LoggerFactory.getLogger(RabbitMq.class);

    public RabbitMq() {
        super();
        setDaemon(true);
    }

    public void run() {
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost("192.168.0.5");
            Connection connection = factory.newConnection();
            Channel channel = connection.createChannel();

            final String EXCHANGE_NAME = "home_automation";

            channel.exchangeDeclare(EXCHANGE_NAME, "topic");
            String queueName = channel.queueDeclare().getQueue();

            channel.queueBind(queueName, EXCHANGE_NAME, "#");

            System.out.println(" [*] Waiting for messages. To exit press CTRL+C");

            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                final byte[] msgBody = delivery.getBody();
                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(msgBody);
                try {
                    Value v = unpacker.unpackValue();
                    System.out.println(v.getValueType());
                    MapValue dataMap = v.asMapValue();
                    System.out.println(dataMap.keySet());
                    System.out.println(dataMap.values());
                } catch (IOException e) {
                    System.err.println(e);
                }
                unpacker.close();

            };
            channel.basicConsume(queueName, true, deliverCallback, consumerTag -> { });
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
        catch (TimeoutException e) {
            log.error(e.getMessage(), e);
        }
    }
}
