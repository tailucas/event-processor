package tailucas.app.device;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.function.Failable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import tailucas.app.device.config.InputConfig;
import tailucas.app.device.config.OutputConfig;
import tailucas.app.provider.DeviceConfig;

public class Event implements Runnable {

    public enum DeviceType {
        SENSOR,
        METER
    }

    private static Logger log = LoggerFactory.getLogger(Event.class);

    protected String source;
    protected Device device;
    protected State deviceUpdate;
    protected String deviceUpdateString;
    protected DeviceConfig configProvider;
    protected ObjectMapper mapper;

    public Event(String source, Device device, State deviceUpdate, String deviceUpdateString) {
        this.source = source;
        this.device = device;
        this.deviceUpdate = deviceUpdate;
        this.deviceUpdateString = deviceUpdateString;
        configProvider = DeviceConfig.getInstance();
        mapper = new ObjectMapper();
    }

    public Event(String source, Device device, State deviceUpdate) {
        this(source, device, deviceUpdate, null);
    }

    public Event(String source, State deviceUpdate) {
        this(source, null, deviceUpdate, null);
    }

    public Event(String source, String deviceUpdate) {
        this(source, null, null, deviceUpdate);
    }

    @Override
    public void run() {
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
                        log.info("{} triggers {}.", deviceLabel, outputDeviceLabel);
                        ObjectNode root = mapper.createObjectNode();
                        try {
                            root.putPOJO("active_input", device);
                            root.putPOJO("input_config", deviceConfig);
                            root.putPOJO("output_triggered", outputConfig);
                            final String jsonCommand = mapper.writeValueAsString(root);
                            log.info("{} triggers {} using {}", deviceLabel, outputDeviceLabel, jsonCommand);
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
