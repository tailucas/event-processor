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
import tailucas.app.device.Meter;
import tailucas.app.device.Ring;
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
        if (payload.length == 0) {
            log.warn("{} ignored with no payload.", topic);
            return;
        } else if (payload.length == 2 && new String(payload).equals("OK")) {
            // catch heartbeat messages for topic matching
            srv.submit(new Event(rabbitMqConnection, topic, new String(payload)));
        } else if (topic.startsWith("inverter/")) {
            log.debug("{} not yet supported.");
        } else if (topic.startsWith("homeassistant/")) {
            if (payload[0] == '{') {
                try {
                    HAConfig haConfig = mapper.readerFor(new TypeReference<HAConfig>() { }).readValue(payload);
                    log.debug("HA config is: {}", haConfig);
                    DeviceConfig.getInstance().putHaConfig(haConfig);
                } catch (Throwable e) {
                    log.warn("JSON issue with {}", new String(payload), e);
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
                    log.warn("JSON issue with {}", new String(payload), e);
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
            try {
                JsonNode root = mapper.readTree(payload);
                final List<Device> inputs = new ArrayList<>();
                final List<Device> active_devices = new ArrayList<>();
                final String[] topicParts = topic.split("/", 2);
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
                    if (deviceType.equals(Type.SENSOR)) {
                        final Device common = mapper.treeToValue(root, Device.class);
                        root.fields().forEachRemaining(field -> {
                            final String fieldName = field.getKey();
                            final JsonNode node = field.getValue();
                            if (fieldName.startsWith("input_") && !fieldName.equals("input_location")) {
                                try {
                                    final Sensor sensor = mapper.treeToValue(node, Sensor.class);
                                    sensor.updateFrom(common);
                                    log.debug("Sensor state is: {}", sensor);
                                    inputs.add(sensor);
                                    if (sensor.isActive()) {
                                        active_devices.add(sensor);
                                    }
                                } catch (JsonProcessingException e) {
                                    log.error("{} deserialization failure of field {}: ", topic, fieldName, e);
                                    return;
                                }
                            }
                        });
                    } else if (deviceType.equals(Type.METER)) {
                        final Meter meter = mapper.treeToValue(root, Meter.class);
                        final String deviceLocation = topicParts[1];
                        meter.setDeviceKey(StringUtils.capitalize(String.format("%s %s", deviceLocation, deviceTypeString)));
                        log.debug("Meter state is: {}", meter);
                        inputs.add(meter);
                        // meters are always "active", thresholds are computed against configuration.
                        active_devices.add(meter);
                    } else {
                        log.warn("{} unknown inferred device type.", topic);
                        return;
                    }
                } catch (JsonProcessingException e) {
                    log.error("During deserialization of {}: " + e.getMessage(), deviceType);
                    return;
                }
                if (inputs.size() > 0) {
                    State deviceUpdate = new State(inputs, active_devices);
                    log.debug("Derived MQTT device state update: {}", deviceUpdate);
                    if (active_devices.size() > 0) {
                        active_devices.forEach(device -> {
                            srv.submit(new Event(rabbitMqConnection, topic, device, deviceUpdate));
                        });
                    } else {
                        srv.submit(new Event(rabbitMqConnection, topic, deviceUpdate));
                    }
                } else {
                    log.warn("{} insufficient device information from topic.", topic);
                    return;
                }
            } catch (JsonParseException e) {
                log.warn("{} during processing of payload, unsupported JSON: {}", topic, new String(payload), e);
                return;
            }
        } else {
            log.warn("{} ignored.", topic);
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
