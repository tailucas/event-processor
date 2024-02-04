package tailucas.app.device;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.function.Failable;
import org.msgpack.jackson.dataformat.MessagePackMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import tailucas.app.device.config.InputConfig;
import tailucas.app.device.config.OutputConfig;
import tailucas.app.provider.DeviceConfig;

public class Event implements Runnable {

    public enum DeviceType {
        SENSOR,
        METER
    }

    private static Logger log = null;

    protected Connection connection;
    protected String source;
    protected Device device;
    protected State deviceUpdate;
    protected String deviceUpdateString;
    protected DeviceConfig configProvider;
    protected MessagePackMapper mapper;

    public Event(Connection connection, String source, Device device, State deviceUpdate, String deviceUpdateString) {
        log = LoggerFactory.getLogger(Event.class);
        this.connection = connection;
        this.source = source;
        this.device = device;
        this.deviceUpdate = deviceUpdate;
        this.deviceUpdateString = deviceUpdateString;
        configProvider = DeviceConfig.getInstance();
        mapper = new MessagePackMapper();
    }

    public Event(Connection connection, String source, Device device, State deviceUpdate) {
        this(connection, source, device, deviceUpdate, null);
    }

    public Event(Connection connection, String source, State deviceUpdate) {
        this(connection, source, null, deviceUpdate, null);
    }

    public Event(Connection connection, String source, String deviceUpdate) {
        this(connection, source, null, null, deviceUpdate);
    }

    protected void sendResponse(String topic, byte[] payload) throws IOException {
        log.info("Responding to {} with {} bytes.", topic, payload.length);
        Channel rabbitMqChannel = connection.createChannel();
        final String EXCHANGE_NAME = "home_automation_control";
        rabbitMqChannel.exchangeDeclare(EXCHANGE_NAME, BuiltinExchangeType.DIRECT);
        rabbitMqChannel.queueDeclare(topic, false, false, false, null);
        rabbitMqChannel.basicPublish("", topic, null, payload);
    }

    @Override
    public void run() {
        final long unixTime = System.currentTimeMillis() / 1000L;
        log.debug("{}", source);
        try {
            final Map<String, OutputConfig> processedOutputs = new HashMap<>(10);
            if (device != null) {
                final String deviceLabel = device.getDeviceLabel();
                log.info("{} references {}", source, deviceLabel);
                final String deviceKey = device.getDeviceKey();
                InputConfig deviceConfig = configProvider.fetchInputDeviceConfig(deviceKey);
                if (deviceConfig == null) {
                    log.warn("No input device configuration found for active {}.", deviceLabel);
                    return;
                }
                List<OutputConfig> linkedOutputs = configProvider.getLinkedOutputs(deviceConfig);
                if (linkedOutputs == null) {
                    log.warn("No output device configuration found for active {}.", deviceLabel);
                    return;
                }
                log.info("{} is linked to {} outputs.", deviceLabel, linkedOutputs.size());
                if (device.mustTriggerOutput(deviceConfig)) {
                    linkedOutputs.forEach(Failable.asConsumer(outputConfig -> {
                        final String outputDeviceLabel = outputConfig.getDeviceLabel();
                        final String outputDeviceType = outputConfig.getDeviceType();
                        ObjectNode root = mapper.createObjectNode();
                        try {
                            root.put("timestamp", unixTime);
                            root.putPOJO("active_input", device);
                            root.putPOJO("output_triggered", outputConfig);
                            final byte[] wireCommand = mapper.writeValueAsBytes(root);
                            final String responseTopic = String.format("event.trigger.%s", outputDeviceType.toLowerCase());
                            String deviceDetail = "";
                            if (!outputDeviceType.equals(outputDeviceLabel)) {
                                deviceDetail = String.format(" (%s)", outputDeviceType);
                            }
                            log.info("{} triggers {}{} on topic {} ({} bytes on the wire).", deviceLabel, outputDeviceLabel, deviceDetail, responseTopic, wireCommand.length);
                            //sendResponse(responseTopic, wireCommand);
                        } catch (Exception e) {
                            log.error(e.getMessage(), e);
                        }
                    }));
                }
                linkedOutputs.forEach(Failable.asConsumer(linkedOutput -> {
                    processedOutputs.put(linkedOutput.getDeviceKey(), linkedOutput);
                }));
            }
            if (deviceUpdate != null) {
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
        } catch (IOException | InterruptedException e) {
            log.error(String.format("Issue during processing from %s", source), e);
        }
        if (deviceUpdateString != null) {
            // mutually exclusive with device and device updates
            log.debug("{} {}", source, deviceUpdateString);
        }
    }
}
