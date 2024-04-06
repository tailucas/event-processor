package tailucas.app.device;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.function.Failable;
import org.msgpack.jackson.dataformat.MessagePackMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.BasicProperties;

import tailucas.app.AppProperties;
import tailucas.app.device.config.InputConfig;
import tailucas.app.device.config.OutputConfig;
import tailucas.app.provider.DeviceConfig;

public class Event implements Runnable {

    private static Logger log = null;
    protected static Pattern namePattern;
    protected static BasicProperties rabbitMqProperties;
    protected static MessagePackMapper mapper;
    protected static DeviceConfig configProvider;
    protected static String exchangeName;

    protected Connection connection;
    protected String source;
    protected Generic device;
    protected State deviceUpdate;
    protected String deviceUpdateString;

    public Event(Connection connection, String source, Generic device, State deviceUpdate, String deviceUpdateString) {
        if (log == null) {
            log = LoggerFactory.getLogger(Event.class);
            mapper = new MessagePackMapper();
            configProvider = DeviceConfig.getInstance();
            namePattern = Pattern.compile("\\W");
            rabbitMqProperties = new AMQP.BasicProperties.Builder()
                .expiration(AppProperties.getProperty("app.message-control-expiry-ms"))
                .build();
            exchangeName = AppProperties.getProperty("app.message-control-exchange-name");
        }
        this.connection = connection;
        this.source = source;
        this.device = device;
        this.deviceUpdate = deviceUpdate;
        this.deviceUpdateString = deviceUpdateString;
    }

    public Event(Connection connection, String source, Generic device, State deviceUpdate) {
        this(connection, source, device, deviceUpdate, null);
    }

    public Event(Connection connection, String source, Generic device) {
        this(connection, source, device, null, null);
    }

    public Event(Connection connection, String source, State deviceUpdate) {
        this(connection, source, null, deviceUpdate, null);
    }

    public Event(Connection connection, String source, String deviceUpdate) {
        this(connection, source, null, null, deviceUpdate);
    }

    protected void sendResponse(Channel rabbitMqChannel, String topic, byte[] payload) throws IOException {
        log.debug("Sending {} bytes to topic {}...", payload.length, topic);
        rabbitMqChannel.exchangeDeclare(exchangeName, BuiltinExchangeType.DIRECT);
        rabbitMqChannel.queueDeclare(topic, false, false, false, null);
        rabbitMqChannel.basicPublish("", topic, rabbitMqProperties, payload);
    }

    @Override
    public void run() {
        final long unixTime = System.currentTimeMillis() / 1000L;
        try {
            final Map<String, OutputConfig> processedOutputs = new HashMap<>(10);
            if (device != null) {
                log.debug("{} device: {}", source, device);
                final String deviceKey = device.getDeviceKey();
                final String deviceLabel = device.getDeviceLabel();
                final String deviceType = device.getDeviceType();
                log.debug("{} {} ({})", deviceType, deviceKey, deviceLabel);
                String deviceDescription;
                if (deviceLabel != null) {
                    deviceDescription = deviceLabel;
                } else {
                    deviceDescription = deviceKey;
                }
                configProvider.postDeviceInfo(device);
                if (device.isHeartbeat()) {
                    log.debug("Heartbeat for {}.", deviceDescription);
                    return;
                }
                log.debug("{} fetch configuration with key {}, description: {}", source, deviceKey, deviceDescription);
                InputConfig deviceConfig = configProvider.fetchInputDeviceConfig(deviceKey);
                log.debug("{} configuration: {}", deviceDescription, deviceConfig);
                if (!device.mustTriggerOutput(deviceConfig)) {
                    log.debug("{} does not trigger any outputs based on current configuration or state.", deviceDescription);
                    return;
                } else {
                    TriggerHistory.getInstance().triggered(deviceKey);
                }
                log.info("{} will trigger outputs because {}", deviceDescription, device.getTriggerStateDescription());
                log.debug("{} getting outputs", deviceDescription);
                List<OutputConfig> linkedOutputs = configProvider.getLinkedOutputs(deviceConfig);
                log.debug("{} outputs {}", deviceDescription, linkedOutputs);
                if (linkedOutputs == null) {
                    log.warn("No output links found for active {}.", deviceDescription);
                    return;
                }
                final List<String> outputNames = new ArrayList<>();
                linkedOutputs.forEach(output -> {
                    final String outputLabel = output.getDeviceLabel();
                    log.debug("Adding output {}...", outputLabel);
                    outputNames.add(outputLabel);
                });
                if (linkedOutputs.size() > 0) {
                    log.info("{} is linked to {} outputs: {}.", deviceDescription, linkedOutputs.size(), outputNames);
                    final Channel rabbitMqChannel = connection.createChannel();
                    try {
                        linkedOutputs.forEach(Failable.asConsumer(outputConfig -> {
                            final String outputDeviceLabel = outputConfig.getDeviceLabel();
                            final String outputDeviceType = outputConfig.getDeviceType();
                            ObjectNode root = mapper.createObjectNode();
                            try {
                                root.put("timestamp", unixTime);
                                root.putPOJO("active_input", device);
                                root.putPOJO("output_triggered", outputConfig);
                                final byte[] wireCommand = mapper.writeValueAsBytes(root);
                                final Matcher nameMatcher = namePattern.matcher(outputDeviceType.toLowerCase());
                                String deviceDetail = "";
                                if (!outputDeviceType.equals(outputDeviceLabel)) {
                                    deviceDetail = String.format(" (%s)", outputDeviceType);
                                }
                                final String responseTopicSuffix = nameMatcher.replaceAll("_");
                                if (responseTopicSuffix.length() == 0) {
                                    throw new RuntimeException(String.format(
                                        "%s maps to invalid command topic suffix %s.",
                                        device.getDeviceLabel(),
                                        outputDeviceType));
                                }
                                final String responseTopic = String.format("event.trigger.%s", responseTopicSuffix);
                                log.info("{} ({}) triggers {}{} on topic {} ({} bytes on the wire).", deviceDescription, source, outputDeviceLabel, deviceDetail, responseTopic, wireCommand.length);
                                sendResponse(rabbitMqChannel, responseTopic, wireCommand);
                            } catch (Exception e) {
                                log.error(e.getMessage(), e);
                            }
                        }));
                    } finally {
                        rabbitMqChannel.close();
                    }
                    linkedOutputs.forEach(Failable.asConsumer(linkedOutput -> {
                        processedOutputs.put(linkedOutput.getDeviceKey(), linkedOutput);
                    }));
                } else {
                    log.warn("{} is linked to no outputs: {}.", deviceDescription, outputNames);
                }
            }
            if (deviceUpdate != null) {
                log.debug("{} update: {}", source, deviceUpdate);
                if (deviceUpdate.inputs != null) {
                    deviceUpdate.inputs.forEach(Failable.asConsumer(device -> {
                        final String deviceKey = device.getDeviceKey();
                        if (this.device != null && deviceKey.equals(this.device.getDeviceKey())) {
                            return;
                        }
                        InputConfig deviceConfig = configProvider.fetchInputDeviceConfig(deviceKey);
                        if (deviceConfig != null) {
                            log.debug("{} input: {}", source, deviceConfig);
                        }
                    }));
                }
                if (deviceUpdate.outputs != null) {
                    deviceUpdate.outputs.forEach(Failable.asConsumer(device -> {
                        final String deviceKey = device.getDeviceKey();
                        if (processedOutputs.containsKey(deviceKey)) {
                            return;
                        }
                        OutputConfig deviceConfig = configProvider.fetchOutputDeviceConfig(deviceKey);
                        if (deviceConfig != null) {
                            log.debug("{} output: {}", source, deviceConfig);
                        }
                    }));
                }
            }
        } catch (IllegalStateException | UnsupportedOperationException | IOException e) {
            // logged only with message
            log.warn("{}: {}", source, e.getMessage());
        } catch (Throwable e) {
            // catch-all before thread death
            // TODO: catch in Sentry
            log.error("{} event issue.", source, e);
        }
        if (deviceUpdateString != null) {
            // mutually exclusive with device and device updates
            log.debug("{} {}", source, deviceUpdateString);
        }
    }
}
