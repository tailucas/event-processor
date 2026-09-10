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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import tailucas.app.EventProcessor;
import tailucas.app.OtelSupport;
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

    private static final TextMapGetter<Map<String, String>> PROPAGATION_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }
        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };
    private static final TextMapSetter<Map<String, String>> PROPAGATION_SETTER = (carrier, key, value) -> {
        if (carrier != null) {
            carrier.put(key, value);
        }
    };

    /** Lazily-bound OTEL instruments; no-op before OtelSupport.init(). */
    private static final class OtelMetrics {
        static final DoubleHistogram QUEUE_TIME = OtelSupport.getMeter()
            .histogramBuilder("event.queue_time")
            .setUnit("s")
            .setDescription("Time an event waited in the queue before processing")
            .build();
        static final LongCounter TRIGGERED = OtelSupport.getMeter()
            .counterBuilder("event.triggered")
            .setDescription("Device events that triggered an output")
            .build();
    }

    protected Connection connection;
    protected String source;
    protected Generic device;
    protected String deviceUpdateString;
    protected long initTime;
    protected String traceparent;
    protected String baggage;

    public void setTraceContext(String traceparent, String baggage) {
        this.traceparent = traceparent;
        this.baggage = baggage;
    }

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

    private boolean isHeartbeatEvent() {
        return device != null
            && (device.isHeartbeat() || source.contains(".heartbeat."));
    }

    private Span buildRunSpan() {
        final var builder = OtelSupport.getTracer().spanBuilder("event.run")
            .setSpanKind(SpanKind.CONSUMER);
        builder.setAttribute("source", source);
        if (traceparent != null) {
            final Map<String, String> carrier = new HashMap<>();
            carrier.put("traceparent", traceparent);
            if (baggage != null) {
                carrier.put("baggage", baggage);
            }
            final Context parentContext = OtelSupport.getOpenTelemetry().getPropagators()
                .getTextMapPropagator().extract(Context.current(), carrier, PROPAGATION_GETTER);
            builder.setParent(parentContext);
        }
        return builder.startSpan();
    }

    @Override
    public void run() {
        final long now = System.currentTimeMillis();
        metrics.postMetric("event_queue_time", now - initTime);
        final double queueTimeSeconds = (now - initTime) / 1000.0;
        OtelMetrics.QUEUE_TIME.record(queueTimeSeconds, Attributes.empty());
        if (device == null) {
            log.atDebug().setMessage("Source posts no device details")
                .addKeyValue("source", source)
                .addKeyValue("device_update", deviceUpdateString)
                .log();
            return;
        }
        // Phase 1 – identity resolution (no span needed)
        final DeviceConfig configProvider;
        try {
            configProvider = DeviceConfig.getInstance();
        } catch (RuntimeException e) {
            metrics.postMetric("error", Map.of(
                "class", this.getClass().getSimpleName(),
                "exception", e.getClass().getSimpleName()));
            log.atError().setMessage("Cannot obtain device config provider")
                .addKeyValue("source", source)
                .setCause(e)
                .log();
            return;
        }
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
        final String deviceDescription;
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
        // Phase 2 – heartbeat check (no span needed)
        if (isHeartbeatEvent()) {
            log.atDebug().setMessage("Heartbeat")
                .addKeyValue("source", source)
                .addKeyValue("device_description", deviceDescription)
                .log();
            // post device info for side-car only upon heartbeats
            configProvider.postDeviceInfo(device);
            // Heartbeats carry the current device state. If a Sensor-type
            // device reports active=false and was previously in a triggered
            // state, reset the trigger state and resolve any escalation.
            if (device instanceof Sensor sensor) {
                final boolean wasActivelyTriggering = triggerLatchHistory.getTriggeredDuration(deviceKey) != null;
                if (wasActivelyTriggering && !sensor.isActive()) {
                    log.atInfo().setMessage("Heartbeat indicates device is no longer active, resetting trigger state")
                        .addKeyValue("device_description", deviceDescription)
                        .log();
                    triggerLatchHistory.unTriggered(deviceKey);
                    final String escalationKey = recentEscalations.remove(deviceKey);
                    if (escalationKey != null) {
                        log.atInfo().setMessage("Device no longer requires escalation (inferred from heartbeat)")
                            .addKeyValue("device_description", deviceDescription)
                            .log();
                        final boolean pagerDutyEnabled = EventProcessor.isFeatureEnabled(EventProcessor.FEATURE_FLAG_PAGER_DUTY_TICKETS);
                        if (pagerDutyEnabled) {
                            final ResolveIncident resolve = ResolveIncident.ResolveIncidentBuilder
                                .newBuilder(EventProcessor.getPagerDutyRoutingKey(), escalationKey)
                                .build();
                            final Span pdSpan = OtelSupport.getTracer().spanBuilder("pagerduty.resolve")
                                .setSpanKind(SpanKind.CLIENT)
                                .setAttribute("pagerduty.service", "PagerDuty Events API v2")
                                .setAttribute("pagerduty.action", "resolve")
                                .setAttribute("pagerduty.dedup_key", escalationKey)
                                .startSpan();
                            try (Scope pdScope = pdSpan.makeCurrent()) {
                                final EventResult result = EventProcessor.getPagerDuty().resolve(resolve);
                                pdSpan.setAttribute("pagerduty.status", result.getStatus());
                                pdSpan.setAttribute("pagerduty.message", result.getMessage());
                                pdSpan.setAttribute("pagerduty.errors", String.valueOf(result.getErrors()));
                                pdSpan.setStatus(StatusCode.OK);
                                log.atInfo().setMessage("Updated PagerDuty")
                                    .addKeyValue("pagerduty_status", result.getStatus())
                                    .addKeyValue("pagerduty_message", result.getMessage())
                                    .addKeyValue("pagerduty_errors", result.getErrors())
                                    .log();
                            } catch (NotifyEventException e) {
                                pdSpan.recordException(e);
                                pdSpan.setStatus(StatusCode.ERROR);
                                log.atError().setMessage("Cannot resolve PagerDuty incident from heartbeat").setCause(e).log();
                                Sentry.captureException(e);
                            } finally {
                                pdSpan.end();
                            }
                        }
                    }
                }
            }
            return;
        }
        // Phase 3 – only non-heartbeat events create a span and do real work
        final Span runSpan = buildRunSpan();
        try (Scope runScope = runSpan.makeCurrent()) {
            final long unixTime = now / 1000L;
            runSpan.setAttribute("device_key", deviceKey);
            runSpan.setAttribute("device_type", String.valueOf(deviceType));
            runSpan.setAttribute("device_label", deviceDescription);
            try {
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
                        runSpan.setAttribute("escalation_resolved", true);
                        final boolean pagerDutyEnabled = EventProcessor.isFeatureEnabled(EventProcessor.FEATURE_FLAG_PAGER_DUTY_TICKETS);
                        runSpan.setAttribute("pagerduty_enabled", pagerDutyEnabled);
                        if (pagerDutyEnabled) {
                            final ResolveIncident resolve = ResolveIncident.ResolveIncidentBuilder
                                .newBuilder(EventProcessor.getPagerDutyRoutingKey(), escalationKey)
                                .build();
                            final Span pdSpan = OtelSupport.getTracer().spanBuilder("pagerduty.resolve")
                                .setSpanKind(SpanKind.CLIENT)
                                .setAttribute("pagerduty.service", "PagerDuty Events API v2")
                                .setAttribute("pagerduty.action", "resolve")
                                .setAttribute("pagerduty.dedup_key", escalationKey)
                                .startSpan();
                            try (Scope pdScope = pdSpan.makeCurrent()) {
                                final EventResult result = EventProcessor.getPagerDuty().resolve(resolve);
                                pdSpan.setAttribute("pagerduty.status", result.getStatus());
                                pdSpan.setAttribute("pagerduty.message", result.getMessage());
                                pdSpan.setAttribute("pagerduty.errors", String.valueOf(result.getErrors()));
                                pdSpan.setStatus(StatusCode.OK);
                                log.atInfo().setMessage("Updated PagerDuty")
                                    .addKeyValue("pagerduty_status", result.getStatus())
                                    .addKeyValue("pagerduty_message", result.getMessage())
                                    .addKeyValue("pagerduty_errors", result.getErrors())
                                    .log();
                            } catch (NotifyEventException e) {
                                pdSpan.recordException(e);
                                pdSpan.setStatus(StatusCode.ERROR);
                                log.atError().setMessage("Cannot update PagerDuty").setCause(e).log();
                                Sentry.captureException(e);
                            } finally {
                                pdSpan.end();
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
                final Integer activationEscalation = deviceConfig.getActivationEscalation();
                final String escalationDetail;
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
                        final Span outputSpan = OtelSupport.getTracer().spanBuilder("event.trigger.output")
                            .setSpanKind(SpanKind.PRODUCER)
                            .startSpan();
                        final String outputDeviceType = outputConfig.getDeviceType();
                        ObjectNode root = mapper.createObjectNode();
                        try (Scope outputScope = outputSpan.makeCurrent()) {
                            try {
                                // inject W3C trace context into the payload body so
                                // downstream consumers can continue this trace.
                                final Map<String, String> propagationCarrier = new HashMap<>();
                                OtelSupport.getOpenTelemetry().getPropagators().getTextMapPropagator()
                                    .inject(Context.current(), propagationCarrier, PROPAGATION_SETTER);
                                root.put("timestamp", unixTime);
                                root.putPOJO("active_input", device);
                                root.putPOJO("output_triggered", outputConfig);
                                propagationCarrier.forEach(root::put);
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

                                outputSpan.setAttribute("input_device", deviceDescription);
                                outputSpan.setAttribute("source", source);
                                outputSpan.setAttribute("output_device", outputDeviceLabel);
                                outputSpan.setAttribute("output_type", outputDeviceType);
                                outputSpan.setAttribute("exchange", exchangeName);
                                outputSpan.setAttribute("routing_key", responseTopic);
                                outputSpan.setAttribute("payload_bytes", wireCommand.length);

                                final BasicProperties rabbitMqProperties = new AMQP.BasicProperties.Builder()
                                    .expiration(expiration)
                                    .build();

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
                                outputSpan.setStatus(StatusCode.OK);
                                OtelMetrics.TRIGGERED.add(1, Attributes.of(
                                    AttributeKey.stringKey("output_type"), outputConfig.getDeviceType(),
                                    AttributeKey.stringKey("output_label"), outputDeviceDescription,
                                    AttributeKey.stringKey("destination"), responseTopic));
                            } catch (Exception e) {
                                outputSpan.recordException(e);
                                outputSpan.setStatus(StatusCode.ERROR);
                                log.atWarn().setMessage("Output trigger failure")
                                    .addKeyValue("source", source)
                                    .setCause(e)
                                    .log();
                                sentrySpan.setThrowable(e);
                                sentrySpan.setStatus(SpanStatus.INTERNAL_ERROR);
                            } finally {
                                sentrySpan.finish();
                            }
                        } finally {
                            outputSpan.end();
                        }
                    }));
                } finally {
                    rabbitMqChannel.close();
                    sentry.finish();
                }
                // now escalate long-running triggers as configured
                if (activationEscalation != null) {
                    runSpan.setAttribute("activation_escalation", activationEscalation);
                    runSpan.setAttribute("triggered_duration", triggeredDuration);
                    runSpan.setAttribute("escalation_detail", escalationDetail);
                    if (triggerLatchHistory.isTriggeredFor(deviceKey, activationEscalation)) {
                        if (!recentEscalations.containsKey(deviceKey)) {
                            log.atWarn().setMessage("Device has been triggered beyond escalation threshold, requires escalation")
                                .addKeyValue("device_description", deviceDescription)
                                .addKeyValue("activation_escalation", activationEscalation)
                                .log();
                            runSpan.setAttribute("escalated", true);
                            runSpan.addEvent("escalation.threshold_exceeded");
                            final String appName = EventProcessor.getAppName();
                            final String dupeKey = appName+"-"+deviceKey;
                            final boolean pagerDutyEnabled = EventProcessor.isFeatureEnabled(EventProcessor.FEATURE_FLAG_PAGER_DUTY_TICKETS);
                            runSpan.setAttribute("pagerduty_enabled", pagerDutyEnabled);
                            if (pagerDutyEnabled) {
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
                                final Span pdSpan = OtelSupport.getTracer().spanBuilder("pagerduty.trigger")
                                    .setSpanKind(SpanKind.CLIENT)
                                    .setAttribute("pagerduty.service", "PagerDuty Events API v2")
                                    .setAttribute("pagerduty.action", "trigger")
                                    .setAttribute("pagerduty.dedup_key", dupeKey)
                                    .setAttribute("pagerduty.severity", "critical")
                                    .startSpan();
                                try (Scope pdScope = pdSpan.makeCurrent()) {
                                    final EventResult result = EventProcessor.getPagerDuty().trigger(incident);
                                    pdSpan.setAttribute("pagerduty.status", result.getStatus());
                                    pdSpan.setAttribute("pagerduty.message", result.getMessage());
                                    pdSpan.setAttribute("pagerduty.errors", String.valueOf(result.getErrors()));
                                    pdSpan.setStatus(StatusCode.OK);
                                    log.atInfo().setMessage("Updated PagerDuty")
                                        .addKeyValue("pagerduty_dedup_key", result.getDedupKey())
                                        .addKeyValue("pagerduty_status", result.getStatus())
                                        .addKeyValue("pagerduty_message", result.getMessage())
                                        .addKeyValue("pagerduty_errors", result.getErrors())
                                        .log();
                                } catch (NotifyEventException e) {
                                    pdSpan.recordException(e);
                                    pdSpan.setStatus(StatusCode.ERROR);
                                    log.atError().setMessage("Cannot update PagerDuty").setCause(e).log();
                                    Sentry.captureException(e);
                                } finally {
                                    pdSpan.end();
                                }
                            }
                            recentEscalations.put(deviceKey, dupeKey);
                        }
                    }
                }
            } catch (IllegalStateException | UnsupportedOperationException | IOException e) {
                runSpan.recordException(e);
                runSpan.setStatus(StatusCode.ERROR);
                log.atWarn().setMessage("Event processing issue")
                    .addKeyValue("source", source)
                    .setCause(e)
                    .log();
                metrics.postMetric("error", Map.of(
                    "class", this.getClass().getSimpleName(),
                    "exception", e.getClass().getSimpleName()));
            } catch (Throwable e) {
                runSpan.recordException(e);
                runSpan.setStatus(StatusCode.ERROR);
                log.atError().setMessage("Event issue").addKeyValue("source", source).setCause(e).log();
                metrics.postMetric("error", Map.of(
                    "class", this.getClass().getSimpleName(),
                    "exception", e.getClass().getSimpleName()));
                Sentry.captureException(e);
            }
        } finally {
            runSpan.end();
        }
    }
}