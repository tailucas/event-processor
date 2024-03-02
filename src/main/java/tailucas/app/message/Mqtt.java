package tailucas.app.message;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Connection;

import tailucas.app.device.Device;
import tailucas.app.device.Event;
import tailucas.app.device.Device.Type;
import tailucas.app.device.Meter;
import tailucas.app.device.Sensor;
import tailucas.app.device.State;

public class Mqtt implements MqttCallback {

    private static Logger log = null;

    private ApplicationContext springApp = null;
    private ExecutorService srv = null;
    private Connection rabbitMqConnection = null;
    private ObjectMapper mapper = null;

    public Mqtt(ApplicationContext springApp, ExecutorService srv, Connection rabbitMqConnection) {
        if (log == null) {
            log = LoggerFactory.getLogger(Mqtt.class);
        }
        this.springApp = springApp;
        this.srv = srv;
        this.rabbitMqConnection = rabbitMqConnection;
        this.mapper = new ObjectMapper();
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        final byte[] payload = message.getPayload();
        try {
            if (topic.startsWith("tasmota/discovery/") || topic.startsWith("tele/")) {
                log.warn("Ignoring event on topic {}", topic);
                return;
            } else if (payload.length == 2 && payload[0] == 'O' && payload[1] == 'K') {
                srv.submit(new Event(rabbitMqConnection, topic, new String(payload)));
            } else {
                JsonNode root = mapper.readTree(payload);
                final List<Device> inputs = new ArrayList<>();
                final List<Device> active_devices = new ArrayList<>();
                final String[] topicParts = topic.split("/", 2);
                if (topicParts.length < 2) {
                    throw new RuntimeException("Invalid topic path on topic [" + topic + "]");
                }
                final String deviceTypeString = StringUtils.capitalize(topicParts[0]);
                Type deviceType = null;
                try {
                    deviceType = Type.valueOf(deviceTypeString.toUpperCase());
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown inferred device type from topic [{}]", topic);
                    return;
                }
                try {
                    if (deviceType.equals(Type.SENSOR)) {
                        final Device common = mapper.treeToValue(root, Device.class);
                        root.fields().forEachRemaining(field -> {
                            final String fieldName = field.getKey();
                            final JsonNode node = field.getValue();
                            if (fieldName.startsWith("input_") && !fieldName.equals("input_location")) {
                                try {
                                    final Sensor sensor = mapper.treeToValue(node, Sensor.class);
                                    sensor.updateFrom(common);
                                    inputs.add(sensor);
                                    if (sensor.active) {
                                        active_devices.add(sensor);
                                    }
                                } catch (JsonProcessingException e) {
                                    log.error("During deserialization of field {}: " + e.getMessage(), fieldName);
                                    throw new RuntimeException(e);
                                }
                            }
                        });
                    } else if (deviceType.equals(Type.METER)) {
                        final Meter meter = mapper.treeToValue(root, Meter.class);
                        final String deviceLocation = topicParts[1];
                        meter.device_key = StringUtils.capitalize(String.format("%s %s", deviceLocation, deviceTypeString));
                        inputs.add(meter);
                        // meters are always "active", thresholds are computed against configuration.
                        active_devices.add(meter);
                    } else {
                        log.warn("Unknown inferred device type from topic {}", topic);
                    }
                } catch (JsonProcessingException e) {
                    log.error("During deserialization of {}: " + e.getMessage(), deviceType);
                    throw new RuntimeException(e);
                }
                State deviceUpdate = new State(inputs, active_devices);
                if (active_devices.size() > 0) {
                    active_devices.forEach(device -> {
                        srv.submit(new Event(rabbitMqConnection, topic, device, deviceUpdate));
                    });
                } else {
                    srv.submit(new Event(rabbitMqConnection, topic, deviceUpdate));
                }
            }
        } catch (RuntimeException e) {
            log.warn("{} payload {}", e.getMessage(), new String(payload));
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.error("MQTT error", cause);
        System.exit(SpringApplication.exit(springApp));
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) { }
}
