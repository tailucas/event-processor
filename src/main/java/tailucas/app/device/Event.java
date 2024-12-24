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
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.BasicProperties;

import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SpanStatus;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import tailucas.app.AppProperties;
import tailucas.app.device.config.InputConfig;
import tailucas.app.device.config.OutputConfig;
import tailucas.app.provider.DeviceConfig;
import tailucas.app.provider.Metrics;

public class Event implements Runnable {

    private static Logger log = null;
    protected static Pattern namePattern;
    protected static BasicProperties rabbitMqProperties;
    protected static MessagePackMapper mapper;
    protected static String exchangeName;
    protected static TriggerHistory triggerLatchHistory;
    protected static TriggerHistory triggerMultiHistory;
    protected static TriggerHistory triggerOutputHistory;
    protected static Metrics metrics;

    protected Connection connection;
    protected String source;
    protected Generic device;
    protected String deviceUpdateString;
    protected long initTime;

    public static void init() {
        if (log == null) {
            log = LoggerFactory.getLogger(Event.class);
            mapper = new MessagePackMapper();
            namePattern = Pattern.compile("\\W");
            rabbitMqProperties = new AMQP.BasicProperties.Builder()
                .expiration(AppProperties.getProperty("app.message-control-expiry-ms"))
                .build();
            exchangeName = AppProperties.getProperty("app.message-control-exchange-name");
            triggerLatchHistory = new TriggerHistory();
            triggerMultiHistory = new TriggerHistory();
            triggerOutputHistory = new TriggerHistory();
            metrics = Metrics.getInstance();
        }
    }

    public Event(Connection connection, String source, Generic device, String deviceUpdateString) {
        init();
        this.initTime = System.currentTimeMillis();
        this.connection = connection;
        this.source = source;
        this.device = device;
        this.deviceUpdateString = deviceUpdateString;
    }

    public Event(Connection connection, String source, Generic device) {
        this(connection, source, device, null);
    }

    public Event(Connection connection, String source, Device device) {
        this(connection, source, device.getDeviceByType(), null);
    }

    public Event(Connection connection, String source, String deviceUpdate) {
        this(connection, source, null, deviceUpdate);
    }

    @Override
    public void run() {
        final long now = System.currentTimeMillis();
        metrics.postMetric("event_queue_time", now - initTime);
        final long unixTime = now / 1000L;
        if (device == null) {
            log.debug("{} posts no device details for {}", source, deviceUpdateString);
            return;
        }
        try {
            final DeviceConfig configProvider = DeviceConfig.getInstance();
            log.debug("{} device: {}", source, device);
            final String deviceKey = device.getDeviceKey();
            if (deviceKey == null) {
                log.error("No identifier for device {}", device);
                return;
            }
            final String deviceLabel = device.getDeviceLabel();
            if (deviceLabel == null && deviceKey != null) {
                log.warn("No device label for {}.", deviceKey);
            }
            final String deviceType = device.getDeviceType();
            if (deviceType == null && deviceKey != null) {
                log.warn("No device type for set for {}", deviceKey);
            }
            log.debug("{} {} ({})", deviceType, deviceKey, deviceLabel);
            String deviceDescription;
            if (deviceLabel != null) {
                deviceDescription = deviceLabel;
            } else {
                deviceDescription = deviceKey;
            }
            final var metricTags = new HashMap<String, String>();
            if (deviceType != null) {
                metricTags.put("input_type", deviceType);
            }
            if (deviceDescription != null) {
                metricTags.put("input_label", deviceDescription);
            }
            metrics.postMetric("event", metricTags);
            if (device.isHeartbeat() || source.contains(".heartbeat.")) {
                log.debug("{}: Heartbeat for {}.", source, deviceDescription);
                // post device info for side-car only upon heartbeats
                configProvider.postDeviceInfo(device);
                return;
            }
            log.debug("{} fetch configuration with key {}, description: {}", source, deviceKey, deviceDescription);
            InputConfig deviceConfig = configProvider.fetchInputDeviceConfig(deviceKey);
            log.debug("{} configuration: {}", deviceDescription, deviceConfig);
            if (!device.wouldTriggerOutput(deviceConfig)) {
                log.debug("{} does not trigger any outputs based on current configuration or state.", deviceDescription);
                return;
            }
            // record the trigger attempt
            triggerMultiHistory.triggered(deviceKey);
            // rate limit 1 - trigger rate latch
            final Long secondsSinceLastTrigger = triggerLatchHistory.secondsSinceLastTriggered(deviceKey);
            if (secondsSinceLastTrigger != null) {
                log.debug("{} was last triggered {}s ago.", deviceDescription, secondsSinceLastTrigger.toString());
                final Integer triggerLatchDuration = deviceConfig.getTriggerLatchDuration();
                if (triggerLatchDuration != null) {
                    if (triggerLatchHistory.triggeredWithin(deviceKey, triggerLatchDuration.intValue())) {
                        final String logMessage = String.format("%s has been triggered already in the last %ss.", deviceDescription, triggerLatchDuration);
                        if (deviceConfig.isDeviceEnabled()) {
                            log.info(logMessage);
                        } else {
                            log.debug(logMessage);
                        }
                        return;
                    }
                }
            }
            // rate limit 2 - trigger filter
            final Integer multiTriggerRate = deviceConfig.getMultiTriggerRate();
            final Integer multiTriggerInterval = deviceConfig.getMultiTriggerInterval();
            if (multiTriggerRate != null && multiTriggerInterval != null) {
                if (!triggerMultiHistory.isMultiTriggered(deviceKey, multiTriggerRate, multiTriggerInterval)) {
                    final String logMessage = String.format("%s has not yet triggered %s times within %ss.", deviceDescription, multiTriggerRate, multiTriggerInterval);
                    if (deviceConfig.isDeviceEnabled()) {
                        log.info(logMessage);
                    } else {
                        log.debug(logMessage);
                    }
                    return;
                }
            }
            // record trigger event
            triggerLatchHistory.triggered(deviceKey);
            if (!deviceConfig.isDeviceEnabled()) {
                log.warn("{} is disabled but would otherwise trigger outputs because {}.", deviceDescription, device.getTriggerStateDescription());
                return;
            }
            log.info("{} will trigger outputs because {}.", deviceDescription, device.getTriggerStateDescription());
            List<OutputConfig> linkedOutputs = configProvider.getLinkedOutputs(deviceConfig);
            log.debug("{} outputs {}", deviceDescription, linkedOutputs);
            if (linkedOutputs == null) {
                log.warn("No output links found for active {}.", deviceDescription);
                return;
            }
            final List<String> outputNames = new ArrayList<>();
            linkedOutputs.forEach(output -> {
                outputNames.add(output.getDeviceLabel());
            });
            log.info("{} is linked to {} outputs: {}.", deviceDescription, linkedOutputs.size(), outputNames);
            final Channel rabbitMqChannel = connection.createChannel();
            rabbitMqChannel.exchangeDeclare(exchangeName, BuiltinExchangeType.DIRECT);
            final ITransaction sentry = Sentry.startTransaction("event", "device event");
            try {
                linkedOutputs.forEach(Failable.asConsumer(outputConfig -> {
                    final String outputDeviceKey = outputConfig.getDeviceKey();
                    final String outputDeviceLabel = outputConfig.getDeviceLabel();
                    String outputDeviceDescription;
                    if (outputDeviceLabel != null) {
                        outputDeviceDescription = outputDeviceLabel;
                    } else {
                        outputDeviceDescription = outputDeviceKey;
                    }
                    if (!outputConfig.isDeviceEnabled()) {
                        log.warn("{} does not trigger output {} because output is not enabled.", deviceDescription, outputDeviceDescription);
                        return;
                    }
                    final Integer outputDeviceTriggerInterval = outputConfig.getTriggerInterval();
                    // trigger not at the rate of incoming messages
                    if (outputDeviceTriggerInterval != null && triggerOutputHistory.triggeredWithin(outputDeviceKey, outputDeviceTriggerInterval)) {
                        log.warn(String.format("Output device %s has been triggered already in the last %ss.", outputDeviceDescription, outputDeviceTriggerInterval));
                        return;
                    }
                    final ISpan sentrySpan = sentry.startChild("trigger", "output");
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
                        String responseTopic = outputConfig.getTriggerTopic();
                        if (responseTopic == null) {
                            final String responseTopicSuffix = nameMatcher.replaceAll("_");
                            if (responseTopicSuffix.length() == 0) {
                                throw new RuntimeException(String.format(
                                    "%s maps to invalid command topic suffix %s.",
                                    device.getDeviceLabel(),
                                    outputDeviceType));
                            }
                            responseTopic = String.format("event.trigger.%s", responseTopicSuffix);
                            log.warn("{} has no configured message topic. Using {}", deviceDescription, responseTopic);
                        }
                        responseTopic = responseTopic.toLowerCase();
                        log.info("{} ({}) triggers {}{} on exchange {} with routing {} ({} bytes on the wire).", deviceDescription, source, outputDeviceLabel, deviceDetail, exchangeName, responseTopic, wireCommand.length);
                        rabbitMqChannel.basicPublish(exchangeName, responseTopic, rabbitMqProperties, wireCommand);
                        // record the trigger event
                        triggerOutputHistory.triggered(outputDeviceKey);
                        final var outputMetricTags = new HashMap<String, String>();
                        outputMetricTags.put("output_type", outputConfig.getDeviceType());
                        outputMetricTags.put("output_label", outputDeviceDescription);
                        outputMetricTags.putAll(metricTags);
                        metrics.postMetric(responseTopic, outputMetricTags);
                        outputMetricTags.put("destination", responseTopic);
                        metrics.postMetric("triggered", outputMetricTags).forEach((k, v) -> {
                            sentrySpan.setTag(k, v);
                        });
                        sentrySpan.setStatus(SpanStatus.OK);
                    } catch (Exception e) {
                        log.warn("{}: {}", source, e.getMessage());
                        sentrySpan.setThrowable(e);
                        sentrySpan.setStatus(SpanStatus.INTERNAL_ERROR);
                    } finally {
                        sentrySpan.finish();
                    }
                }));
            } finally {
                rabbitMqChannel.close();
                sentry.finish();
            }
        } catch (IllegalStateException | UnsupportedOperationException | IOException e) {
            // logged only with message
            log.warn("{}: {}", source, e.getMessage());
            metrics.postMetric("error", Map.of(
                "class", this.getClass().getSimpleName(),
                "exception", e.getClass().getSimpleName()));
        } catch (Throwable e) {
            log.error("{} event issue.", source, e);
            metrics.postMetric("error", Map.of(
                "class", this.getClass().getSimpleName(),
                "exception", e.getClass().getSimpleName()));
            Sentry.captureException(e);
        }
    }
}
