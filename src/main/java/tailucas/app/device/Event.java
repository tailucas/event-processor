package tailucas.app.device;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.function.Failable;
import org.msgpack.jackson.dataformat.MessagePackMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dikhan.pagerduty.client.events.domain.EventResult;
import com.github.dikhan.pagerduty.client.events.domain.Payload;
import com.github.dikhan.pagerduty.client.events.domain.ResolveIncident;
import com.github.dikhan.pagerduty.client.events.domain.Severity;
import com.github.dikhan.pagerduty.client.events.domain.TriggerIncident;
import com.github.dikhan.pagerduty.client.events.exceptions.NotifyEventException;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.BasicProperties;

import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SpanStatus;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import tailucas.app.EventProcessor;
import tailucas.app.device.config.InputConfig;
import tailucas.app.device.config.OutputConfig;
import tailucas.app.provider.DeviceConfig;
import tailucas.app.provider.Metrics;

public class Event implements Runnable {

    private static volatile String exchangeName;
    private static volatile String expiration;

    private static final Logger log = LoggerFactory.getLogger(Event.class);
    private static final Pattern namePattern = Pattern.compile("\\W");
    private static final MessagePackMapper mapper = new MessagePackMapper();
    private static final TriggerHistory triggerLatchHistory = new TriggerHistory();
    private static final TriggerHistory triggerMultiHistory = new TriggerHistory();
    private static final TriggerHistory triggerOutputHistory = new TriggerHistory();
    private static final Metrics metrics = Metrics.getInstance();
    private static final Map<String, String> recentEscalations = new ConcurrentHashMap<>();

    protected Connection connection;
    protected String source;
    protected Generic device;
    protected String deviceUpdateString;
    protected long initTime;

    public static void configure(String exchangeName, String expiration) {
        Event.exchangeName = exchangeName;
        Event.expiration = expiration;
    }

    public Event(Connection connection, String source, Generic device, String deviceUpdateString) {
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
            log.atDebug().setMessage("Source posts no device details")
                .addKeyValue("source", source)
                .addKeyValue("device_update", deviceUpdateString)
                .log();
            return;
        }
        try {
            final DeviceConfig configProvider = DeviceConfig.getInstance();
            log.atDebug().setMessage("Device")
                .addKeyValue("source", source)
                .addKeyValue("device", String.valueOf(device))
                .log();
            final String deviceKey = device.getDeviceKey();
            if (deviceKey == null) {
                log.atError().setMessage("No identifier for device").addKeyValue("device", String.valueOf(device)).log();
                return;
            }
            final String deviceLabel = device.getDeviceLabel();
            if (deviceLabel == null) {
                log.atWarn().setMessage("No device label").addKeyValue("device_key", deviceKey).log();
            }
            final String deviceType = device.getDeviceType();
            if (deviceType == null) {
                log.atWarn().setMessage("No device type set").addKeyValue("device_key", deviceKey).log();
            }
            log.atDebug().setMessage("Device identity")
                .addKeyValue("device_type", deviceType)
                .addKeyValue("device_key", deviceKey)
                .addKeyValue("device_label", deviceLabel)
                .log();
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
            metricTags.put("input_label", deviceDescription);
            metrics.postMetric("event", metricTags);
            if (device.isHeartbeat() || source.contains(".heartbeat.")) {
                log.atDebug().setMessage("Heartbeat")
                    .addKeyValue("source", source)
                    .addKeyValue("device_description", deviceDescription)
                    .log();
                // post device info for side-car only upon heartbeats
                configProvider.postDeviceInfo(device);
                return;
            }
            log.atDebug().setMessage("Fetch configuration")
                .addKeyValue("source", source)
                .addKeyValue("device_key", deviceKey)
                .addKeyValue("device_description", deviceDescription)
                .log();
            InputConfig deviceConfig = configProvider.fetchInputDeviceConfig(deviceKey);
            log.atDebug().setMessage("Configuration")
                .addKeyValue("device_description", deviceDescription)
                .addKeyValue("config", String.valueOf(deviceConfig))
                .log();
            if (!device.wouldTriggerOutput(deviceConfig)) {
                // reset any trigger history
                triggerLatchHistory.unTriggered(deviceKey);
                // resolve any active escalations
                final String escalationKey = recentEscalations.remove(deviceKey);
                if (escalationKey != null) {
                    log.atInfo().setMessage("Device no longer requires escalation")
                        .addKeyValue("device_description", deviceDescription)
                        .log();
                    if (EventProcessor.isFeatureEnabled(EventProcessor.FEATURE_FLAG_PAGER_DUTY_TICKETS)) {
                        final ResolveIncident resolve = ResolveIncident.ResolveIncidentBuilder
                            .newBuilder(EventProcessor.getPagerDutyRoutingKey(), escalationKey)
                            .build();
                        try {
                            final EventResult result = EventProcessor.getPagerDuty().resolve(resolve);
                            log.atInfo().setMessage("Updated PagerDuty")
                                .addKeyValue("pagerduty_status", result.getStatus())
                                .addKeyValue("pagerduty_message", result.getMessage())
                                .addKeyValue("pagerduty_errors", result.getErrors())
                                .log();
                        } catch (NotifyEventException e) {
                            log.atError().setMessage("Cannot update PagerDuty").setCause(e).log();
                            Sentry.captureException(e);
                        }
                    }
                }
                log.atDebug().setMessage("Device does not trigger any outputs based on current configuration or state")
                    .addKeyValue("device_description", deviceDescription)
                    .log();
                return;
            }
            // record the trigger attempt
            triggerMultiHistory.triggered(deviceKey);
            // rate limit 1 - trigger rate latch
            final Long secondsSinceLastTrigger = triggerLatchHistory.secondsSinceLastTriggered(deviceKey);
            if (secondsSinceLastTrigger != null) {
                log.atDebug().setMessage("Device was last triggered recently")
                    .addKeyValue("device_description", deviceDescription)
                    .addKeyValue("seconds_since_last_trigger", secondsSinceLastTrigger)
                    .log();
                final Integer triggerLatchDuration = deviceConfig.getTriggerLatchDuration();
                if (triggerLatchDuration != null) {
                    if (triggerLatchHistory.triggeredWithin(deviceKey, triggerLatchDuration.intValue())) {
                        final LoggingEventBuilder latchLog = deviceConfig.isDeviceEnabled() ? log.atInfo() : log.atDebug();
                        latchLog.setMessage("Device has been triggered already within the latch duration")
                            .addKeyValue("device_description", deviceDescription)
                            .addKeyValue("trigger_latch_duration", triggerLatchDuration)
                            .log();
                        return;
                    }
                }
            }
            // rate limit 2 - trigger filter
            final Integer multiTriggerRate = deviceConfig.getMultiTriggerRate();
            final Integer multiTriggerInterval = deviceConfig.getMultiTriggerInterval();
            if (multiTriggerRate != null && multiTriggerInterval != null) {
                if (!triggerMultiHistory.isMultiTriggered(deviceKey, multiTriggerRate, multiTriggerInterval)) {
                    final LoggingEventBuilder multiLog = deviceConfig.isDeviceEnabled() ? log.atInfo() : log.atDebug();
                    multiLog.setMessage("Device has not yet triggered the required times within the interval")
                        .addKeyValue("device_description", deviceDescription)
                        .addKeyValue("multi_trigger_rate", multiTriggerRate)
                        .addKeyValue("multi_trigger_interval", multiTriggerInterval)
                        .log();
                    return;
                }
            }
            // record trigger event
            triggerLatchHistory.triggered(deviceKey);
            if (!deviceConfig.isDeviceEnabled()) {
                log.atWarn().setMessage("Device is disabled but would otherwise trigger outputs")
                    .addKeyValue("device_description", deviceDescription)
                    .addKeyValue("trigger_state", device.getTriggerStateDescription())
                    .log();
                return;
            }
            final Long triggeredDuration = triggerLatchHistory.getTriggeredDuration(deviceKey);
            metrics.postMetric("triggered_duration", triggeredDuration.doubleValue(), metricTags);
            String escalationDetail = "";
            final Integer activationEscalation = deviceConfig.getActivationEscalation();
            if (activationEscalation != null) {
                escalationDetail = String.format(" (triggered for %ss, escalates at %s)", triggeredDuration, activationEscalation);
            } else {
                escalationDetail = String.format(" (triggered for %ss)", triggeredDuration);
            }
            log.atDebug().setMessage("Device will trigger outputs")
                .addKeyValue("device_description", deviceDescription)
                .addKeyValue("trigger_state", device.getTriggerStateDescription())
                .addKeyValue("escalation_detail", escalationDetail)
                .log();
            List<OutputConfig> linkedOutputs = configProvider.getLinkedOutputs(deviceConfig);
            log.atDebug().setMessage("Linked outputs")
                .addKeyValue("device_description", deviceDescription)
                .addKeyValue("outputs", String.valueOf(linkedOutputs))
                .log();
            if (linkedOutputs == null) {
                log.atWarn().setMessage("No output links found for active device")
                    .addKeyValue("device_description", deviceDescription)
                    .log();
                return;
            }
            final List<String> outputNames = new ArrayList<>();
            linkedOutputs.forEach(output -> {
                outputNames.add(output.getDeviceLabel());
            });
            log.atInfo().setMessage("Device is linked to outputs")
                .addKeyValue("device_description", deviceDescription)
                .addKeyValue("output_count", linkedOutputs.size())
                .addKeyValue("output_names", outputNames)
                .log();
            final Channel rabbitMqChannel = connection.createChannel();
            rabbitMqChannel.exchangeDeclare(exchangeName, BuiltinExchangeType.DIRECT);
            final BasicProperties rabbitMqProperties = new AMQP.BasicProperties.Builder()
                .expiration(expiration)
                .build();
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
                        log.atWarn().setMessage("Device does not trigger output because output is not enabled")
                            .addKeyValue("device_description", deviceDescription)
                            .addKeyValue("output_device", outputDeviceDescription)
                            .log();
                        return;
                    }
                    final Integer outputDeviceTriggerInterval = outputConfig.getTriggerInterval();
                    // trigger not at the rate of incoming messages
                    if (outputDeviceTriggerInterval != null && triggerOutputHistory.triggeredWithin(outputDeviceKey, outputDeviceTriggerInterval)) {
                        log.atWarn().setMessage("Output device has been triggered already within the trigger interval")
                            .addKeyValue("output_device", outputDeviceDescription)
                            .addKeyValue("trigger_interval", outputDeviceTriggerInterval)
                            .log();
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
                        final Matcher nameMatcher = namePattern.matcher(outputDeviceType.toLowerCase(Locale.ROOT));
                        String responseTopic = outputConfig.getTriggerTopic();
                        if (responseTopic == null) {
                            final String responseTopicSuffix = nameMatcher.replaceAll("_");
                            if (responseTopicSuffix.length() == 0) {
                                throw new IllegalStateException(String.format(
                                    "%s maps to invalid command topic suffix %s.",
                                    device.getDeviceLabel(),
                                    outputDeviceType));
                            }
                            responseTopic = String.format("event.trigger.%s", responseTopicSuffix);
                            log.atWarn().setMessage("Device has no configured message topic; using derived topic")
                                .addKeyValue("device_description", deviceDescription)
                                .addKeyValue("topic", responseTopic)
                                .log();
                        }
                        responseTopic = responseTopic.toLowerCase(Locale.ROOT);
                        log.atInfo().setMessage("Input triggers output")
                            .addKeyValue("input_device", deviceDescription)
                            .addKeyValue("source", source)
                            .addKeyValue("output_device", outputDeviceLabel)
                            .addKeyValue("output_type", outputDeviceType)
                            .addKeyValue("exchange", exchangeName)
                            .addKeyValue("routing_key", responseTopic)
                            .addKeyValue("payload_bytes", wireCommand.length)
                            .log();
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
                        log.atWarn().setMessage("Output trigger failure")
                            .addKeyValue("source", source)
                            .addKeyValue("error", e.getMessage())
                            .log();
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
            // now escalate long-running triggers as configured
            if (activationEscalation != null) {
                if (triggerLatchHistory.isTriggeredFor(deviceKey, activationEscalation)) {
                    if (!recentEscalations.containsKey(deviceKey)) {
                        log.atWarn().setMessage("Device has been triggered beyond escalation threshold, requires escalation")
                            .addKeyValue("device_description", deviceDescription)
                            .addKeyValue("activation_escalation", activationEscalation)
                            .log();
                        final String appName = EventProcessor.getAppName();
                        final String dupeKey = appName+"-"+deviceKey;
                        if (EventProcessor.isFeatureEnabled(EventProcessor.FEATURE_FLAG_PAGER_DUTY_TICKETS)) {
                            final Payload payload = Payload.Builder.newBuilder()
                                .setSummary(String.format("%s escalation", deviceDescription))
                                .setSource(EventProcessor.getDeviceName())
                                .setSeverity(Severity.CRITICAL)
                                .setTimestamp(OffsetDateTime.now())
                                .build();
                            final TriggerIncident incident = TriggerIncident.TriggerIncidentBuilder
                                .newBuilder(EventProcessor.getPagerDutyRoutingKey(), payload)
                                .setDedupKey(dupeKey)
                                .build();
                            final EventResult result = EventProcessor.getPagerDuty().trigger(incident);
                            log.atInfo().setMessage("Updated PagerDuty")
                                .addKeyValue("pagerduty_dedup_key", result.getDedupKey())
                                .addKeyValue("pagerduty_status", result.getStatus())
                                .addKeyValue("pagerduty_message", result.getMessage())
                                .addKeyValue("pagerduty_errors", result.getErrors())
                                .log();
                        }
                        recentEscalations.put(deviceKey, dupeKey);
                    }
                }
            }
        } catch (IllegalStateException | UnsupportedOperationException | IOException e) {
            // logged only with message
            log.atWarn().setMessage("Event processing issue")
                .addKeyValue("source", source)
                .addKeyValue("error", e.getMessage())
                .log();
            metrics.postMetric("error", Map.of(
                "class", this.getClass().getSimpleName(),
                "exception", e.getClass().getSimpleName()));
        } catch (Throwable e) {
            log.atError().setMessage("Event issue").addKeyValue("source", source).setCause(e).log();
            metrics.postMetric("error", Map.of(
                "class", this.getClass().getSimpleName(),
                "exception", e.getClass().getSimpleName()));
            Sentry.captureException(e);
        }
    }
}
