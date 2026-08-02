package tailucas.app.message;

import java.util.Locale;
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

import io.sentry.Sentry;
import tailucas.app.EventProcessor;
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

    private static final Logger log = LoggerFactory.getLogger(Mqtt.class);

    private static final class HATypeRef extends TypeReference<HAConfig> { }
    private static final class RingTypeRef extends TypeReference<Ring> { }

    private final Metrics metrics;

    private final ApplicationContext springApp;
    private final ExecutorService srv;
    private final Connection rabbitMqConnection;
    private final ObjectMapper mapper;

    public Mqtt(ApplicationContext springApp, ExecutorService srv, Connection rabbitMqConnection) {
        this.springApp = springApp;
        this.srv = srv;
        this.rabbitMqConnection = rabbitMqConnection;
        this.mapper = new ObjectMapper();
        this.metrics = Metrics.getInstance();
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        metrics.postMetric("message", Map.of("type", "mqtt"));
        final byte[] payload = message.getPayload();
        try {
            if (payload.length == 0) {
                log.atWarn().setMessage("Message ignored, no payload").addKeyValue("topic", topic).log();
                return;
            } else if (payload.length == 2 && new String(payload).equals("OK")) {
                // catch heartbeat messages for topic matching
                srv.execute(new Event(rabbitMqConnection, topic, new String(payload)));
            } else if (topic.startsWith("inverter/")) {
                log.atDebug().setMessage("Topic not yet supported").addKeyValue("topic", topic).log();
            } else if (topic.equals("homeassistant/status")) {
                log.atDebug().setMessage("Topic not supported").addKeyValue("topic", topic).log();
            } else if (topic.startsWith("homeassistant/")) {
                if (payload[0] == '{') {
                    try {
                        HAConfig haConfig = mapper.readerFor(new HATypeRef()).readValue(payload);
                        log.atDebug().setMessage("HA config").addKeyValue("ha_config", String.valueOf(haConfig)).log();
                        DeviceConfig.getInstance().putHaConfig(haConfig);
                    } catch (Throwable e) {
                        log.atWarn().setMessage("JSON issue")
                            .addKeyValue("topic", topic)
                            .addKeyValue("payload", new String(payload))
                            .addKeyValue("error", e.getMessage())
                            .log();
                    }
                } else {
                    log.atWarn().setMessage("Unassigned payload")
                        .addKeyValue("topic", topic)
                        .addKeyValue("payload", new String(payload))
                        .log();
                }
            } else if (topic.startsWith("ring/")) {
                Ring ringDevice = null;
                log.atDebug().setMessage("Ring payload")
                    .addKeyValue("topic", topic)
                    .addKeyValue("payload", new String(payload))
                    .log();
                if (payload[0] == '{') {
                    try {
                        ringDevice = mapper.readerFor(new RingTypeRef()).readValue(payload);
                        ringDevice.setMqttTopic(topic);
                    } catch (Throwable e) {
                        log.atWarn().setMessage("JSON issue")
                            .addKeyValue("topic", topic)
                            .addKeyValue("payload", new String(payload))
                            .addKeyValue("error", e.getMessage())
                            .log();
                    }
                } else {
                    ringDevice = new Ring();
                    ringDevice.setMqttTopic(topic, new String(payload));
                }
                if (ringDevice != null) {
                    log.atDebug().setMessage("Ring state").addKeyValue("ring_state", String.valueOf(ringDevice)).log();
                    srv.execute(new Event(rabbitMqConnection, topic, ringDevice));
                }
            } else if (topic.startsWith("meter/") || topic.startsWith("sensor/")) {
                // attempt a JSON introspection
                JsonNode root = mapper.readTree(payload);
                final String[] topicParts = topic.split("/", 3);
                if (topicParts.length < 2) {
                    log.atError().setMessage("Topic not handled").addKeyValue("topic", topic).log();
                    return;
                }
                final String deviceTypeString = StringUtils.capitalize(topicParts[0]);
                Type deviceType = null;
                try {
                    deviceType = Type.valueOf(deviceTypeString.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    log.atWarn().setMessage("Unknown device type").addKeyValue("topic", topic).log();
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
                                    log.atDebug().setMessage("Sensor state").addKeyValue("sensor_state", String.valueOf(sensor)).log();
                                    srv.execute(new Event(rabbitMqConnection, topic, sensor));
                                } catch (JsonProcessingException e) {
                                    log.atError().setMessage("Deserialization failure")
                                        .addKeyValue("topic", topic)
                                        .addKeyValue("field_name", fieldName)
                                        .addKeyValue("error", e.getMessage())
                                        .log();
                                    return;
                                }
                            }
                        });
                    } else if (deviceType.equals(Type.METER)) {
                        final Meter meter = mapper.treeToValue(root, Meter.class);
                        meter.setLocation(location);
                        log.atDebug().setMessage("Meter state").addKeyValue("meter_state", String.valueOf(meter)).log();
                        srv.execute(new Event(rabbitMqConnection, topic, meter));
                    } else {
                        log.atWarn().setMessage("Unknown inferred device type").addKeyValue("topic", topic).log();
                        return;
                    }
                } catch (JsonParseException e) {
                    log.atWarn().setMessage("Unsupported JSON during payload processing")
                        .addKeyValue("topic", topic)
                        .addKeyValue("payload", new String(payload))
                        .addKeyValue("error", e.getMessage())
                        .log();
                    return;
                }
            } else {
                log.atWarn().setMessage("Topic ignored").addKeyValue("topic", topic).log();
            }
        } catch (Exception e) {
            metrics.postMetric("error", Map.of(
                "class", this.getClass().getSimpleName(),
                "exception", e.getClass().getSimpleName()));
            log.atError().setMessage("Event issue")
                .addKeyValue("topic", topic)
                .addKeyValue("payload_bytes", payload.length)
                .setCause(e)
                .log();
            Sentry.captureException(e);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.atError().setMessage("MQTT error").setCause(cause).log();
        EventProcessor.addExitCode(EventProcessor.EXIT_CODE_MQTT);
        System.exit(SpringApplication.exit(springApp));
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) { }
}
