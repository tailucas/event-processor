package tailucas.app;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class Mqtt extends Thread {

    private static final Logger log = LoggerFactory.getLogger(Mqtt.class);

    public Mqtt() {
        super("mqtt");
        setDaemon(true);
    }

    public void run() {

        try {
            String clientId = UUID.randomUUID().toString();
            IMqttClient mqttClient = new MqttClient("tcp://192.168.0.5:1883", clientId, new MemoryPersistence());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            mqttClient.connect(options);

            mqttClient.subscribe("#", (topic, msg) -> {
                byte[] payload = msg.getPayload();
                log.info("{}}: {}", topic, new String(payload));
            });
        } catch (MqttException e) {
            log.error(e.getMessage(), e);
        }
    }
}
