package tailucas.app.message;

import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Connection;

import tailucas.app.device.Device;
import tailucas.app.device.Event;
import tailucas.app.device.Device.Type;
import tailucas.app.device.config.HAConfig;
import tailucas.app.provider.DeviceConfig;
import tailucas.app.provider.Metrics;
import tailucas.app.device.Meter;
import tailucas.app.device.Ring;
import tailucas.app.device.Sensor;

public class Mqtt implements MqttCallback {

    private static Logger log = null;

    private Metrics metrics = null;

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
        this.metrics = Metrics.getInstance();
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        metrics.postMetric("message", 1f, Map.of("type", "mqtt"));
        final byte[] payload = message.getPayload();
        try {
            if (payload.length == 0) {
                log.warn("{} ignored with no payload.", topic);
                return;
            } else if (payload.length == 2 && new String(payload).equals("OK")) {
                // catch heartbeat messages for topic matching
                srv.submit(new Event(rabbitMqConnection, topic, new String(payload)));
            } else if (topic.startsWith("inverter/")) {
                log.debug("{} not yet supported.", topic);
            } else if (topic.equals("homeassistant/status")) {
                log.debug("{} not supported.", topic);
            } else if (topic.startsWith("homeassistant/")) {
                if (payload[0] == '{') {
                    try {
                        HAConfig haConfig = mapper.readerFor(new TypeReference<HAConfig>() { }).readValue(payload);
                        log.debug("HA config is: {}", haConfig);
                        DeviceConfig.getInstance().putHaConfig(haConfig);
                    } catch (Throwable e) {
                        log.warn("{} JSON issue with {}", topic, new String(payload), e);
                    }
                } else {
                    log.warn("{} unassigned payload: {}", topic, new String(payload));
                }
            } else if (topic.startsWith("ring/")) {
                Ring ringDevice = null;
                log.debug("{}: {}", topic, new String(payload));
                if (payload[0] == '{') {
                    try {
                        ringDevice = mapper.readerFor(new TypeReference<Ring>() { }).readValue(payload);
                        ringDevice.setMqttTopic(topic);
                    } catch (Throwable e) {
                        log.warn("{} JSON issue with {}", topic, new String(payload), e);
                    }
                } else {
                    ringDevice = new Ring();
                    ringDevice.setMqttTopic(topic, new String(payload));
                }
                if (ringDevice != null) {
                    log.debug("Ring state is: {}", ringDevice);
                    srv.submit(new Event(rabbitMqConnection, topic, ringDevice));
                }
            } else if (topic.startsWith("meter/") || topic.startsWith("sensor/")) {
                // attempt a JSON introspection
                JsonNode root = mapper.readTree(payload);
                final String[] topicParts = topic.split("/", 3);
                if (topicParts.length < 2) {
                    log.error("{} not handled.", topic);
                    return;
                }
                final String deviceTypeString = StringUtils.capitalize(topicParts[0]);
                Type deviceType = null;
                try {
                    deviceType = Type.valueOf(deviceTypeString.toUpperCase());
                } catch (IllegalArgumentException e) {
                    log.warn("{} unknown device type.", topic);
                    return;
                }
                try {
                    final String location = StringUtils.capitalize(topicParts[1]);
                    if (deviceType.equals(Type.SENSOR)) {
                        final Device common = mapper.treeToValue(root, Device.class);
                        common.setLocation(location);
                        root.fields().forEachRemaining(field -> {
                            final String fieldName = field.getKey();
                            final JsonNode node = field.getValue();
                            if (fieldName.startsWith("input_") && !fieldName.equals("input_location")) {
                                try {
                                    final Sensor sensor = mapper.treeToValue(node, Sensor.class);
                                    sensor.updateFrom(common);
                                    log.debug("Sensor state is: {}", sensor);
                                    srv.submit(new Event(rabbitMqConnection, topic, sensor));
                                } catch (JsonProcessingException e) {
                                    log.error("{} deserialization failure of field {}: ", topic, fieldName, e);
                                    return;
                                }
                            }
                        });
                    } else if (deviceType.equals(Type.METER)) {
                        final Meter meter = mapper.treeToValue(root, Meter.class);
                        meter.setLocation(location);
                        log.debug("Meter state is: {}", meter);
                        srv.submit(new Event(rabbitMqConnection, topic, meter));
                    } else {
                        log.warn("{} unknown inferred device type.", topic);
                        return;
                    }
                } catch (JsonParseException e) {
                    log.warn("{} during processing of payload, unsupported JSON: {}", topic, new String(payload), e);
                    return;
                }
            } else {
                log.warn("{} ignored.", topic);
            }
        } catch (Exception e) {
            metrics.postMetric("error", 1f, Map.of(
                "class", this.getClass().getSimpleName(),
                "exception", e.getClass().getSimpleName()));
            log.error("{} event issue ({} bytes).", topic, payload.length, e);
        } finally {
            metrics.postMetric("error", 0f, Map.of(
                "class", this.getClass().getSimpleName()));
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
