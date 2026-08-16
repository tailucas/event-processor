package tailucas.app.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.msgpack.jackson.dataformat.MessagePackMapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dikhan.pagerduty.client.events.PagerDutyEventsClient;
import com.github.dikhan.pagerduty.client.events.domain.EventResult;
import com.github.dikhan.pagerduty.client.events.domain.ResolveIncident;
import com.github.dikhan.pagerduty.client.events.domain.TriggerIncident;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import io.opentelemetry.api.GlobalOpenTelemetry;

import tailucas.app.EventProcessor;
import tailucas.app.OtelSupport;
import tailucas.app.TestStatics;
import tailucas.app.device.config.InputConfig;
import tailucas.app.device.config.OutputConfig;
import tailucas.app.provider.DeviceConfig;

class EventTest {

    private static final String DEVICE_KEY = "Kitchen Smoke";
    private static final String SOURCE = "device.state.update";

    private MockedStatic<DeviceConfig> deviceConfigStatic;
    private MockedStatic<EventProcessor> eventProcessorStatic;
    private DeviceConfig deviceConfig;
    private PagerDutyEventsClient pagerDuty;
    private Connection connection;
    private Channel channel;

    @BeforeAll
    static void initStatics() {
        TestStatics.configureTestMetrics();
        Event.configure("test_exchange", "30000");
    }

    @BeforeEach
    void setUp() throws Exception {
        TestStatics.clearEventState();
        connection = mock(Connection.class);
        channel = mock(Channel.class);
        when(connection.createChannel()).thenReturn(channel);
        deviceConfig = mock(DeviceConfig.class);
        deviceConfigStatic = mockStatic(DeviceConfig.class);
        deviceConfigStatic.when(DeviceConfig::getInstance).thenReturn(deviceConfig);
        pagerDuty = mock(PagerDutyEventsClient.class);
        eventProcessorStatic = mockStatic(EventProcessor.class);
        eventProcessorStatic.when(() -> EventProcessor.isFeatureEnabled(anyString())).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        deviceConfigStatic.close();
        eventProcessorStatic.close();
    }

    private static Detector detectorInState(String state) {
        final Device device = new Device();
        device.deviceLabel = DEVICE_KEY;
        device.state = state;
        return new Detector(device);
    }

    private static InputConfig inputConfig(String json) throws Exception {
        return new ObjectMapper().readValue(json, InputConfig.class);
    }

    private static OutputConfig outputConfig(String json) throws Exception {
        return new ObjectMapper().readValue(json, OutputConfig.class);
    }

    private static String inputConfigJson(String extra) throws Exception {
        return String.format("{\"device_key\": \"%s\", \"device_enabled\": true%s}", DEVICE_KEY, extra);
    }

    private void stubTriggeringSetup(InputConfig config, List<OutputConfig> outputs) throws Exception {
        when(deviceConfig.fetchInputDeviceConfig(DEVICE_KEY)).thenReturn(config);
        when(deviceConfig.getLinkedOutputs(config)).thenReturn(outputs);
    }

    @Test
    void missingDeviceIsNoOp() {
        new Event(connection, SOURCE, "OK").run();
        verifyNoInteractions(deviceConfig);
    }

    @Test
    void missingDeviceKeyIsDropped() {
        final Generic device = mock(Generic.class);
        when(device.getDeviceKey()).thenReturn(null);
        new Event(connection, SOURCE, device).run();
        verifyNoInteractions(deviceConfig);
    }

    @Test
    void heartbeatPostsDeviceInfoOnly() throws Exception {
        final Detector detector = detectorInState("on");
        new Event(connection, "device.heartbeat.update", detector).run();
        // the Device-based constructor re-wraps the device by type, so capture rather than compare identities
        final ArgumentCaptor<Generic> captor = ArgumentCaptor.forClass(Generic.class);
        verify(deviceConfig).postDeviceInfo(captor.capture());
        assertEquals(DEVICE_KEY, captor.getValue().getDeviceLabel());
        verify(deviceConfig, never()).fetchInputDeviceConfig(anyString());
        verifyNoInteractions(connection);
    }

    @Test
    void nonTriggeringDeviceDoesNotPublish() throws Exception {
        final InputConfig config = inputConfig(inputConfigJson(""));
        when(deviceConfig.fetchInputDeviceConfig(DEVICE_KEY)).thenReturn(config);
        new Event(connection, SOURCE, detectorInState("off")).run();
        verify(deviceConfig).fetchInputDeviceConfig(DEVICE_KEY);
        verify(deviceConfig, never()).getLinkedOutputs(any());
        verifyNoInteractions(connection);
    }

    @Test
    void clearedEscalationResolvesPagerDutyIncident() throws Exception {
        TestStatics.escalations().put(DEVICE_KEY, "app-" + DEVICE_KEY);
        eventProcessorStatic.when(() -> EventProcessor.isFeatureEnabled(EventProcessor.FEATURE_FLAG_PAGER_DUTY_TICKETS))
            .thenReturn(true);
        eventProcessorStatic.when(EventProcessor::getPagerDutyRoutingKey).thenReturn("routing-key");
        eventProcessorStatic.when(EventProcessor::getPagerDuty).thenReturn(pagerDuty);
        final EventResult result = mock(EventResult.class);
        when(result.getStatus()).thenReturn("success");
        when(pagerDuty.resolve(any(ResolveIncident.class))).thenReturn(result);

        final InputConfig config = inputConfig(inputConfigJson(""));
        when(deviceConfig.fetchInputDeviceConfig(DEVICE_KEY)).thenReturn(config);
        new Event(connection, SOURCE, detectorInState("off")).run();

        verify(pagerDuty).resolve(any(ResolveIncident.class));
        assertTrue(TestStatics.escalations().isEmpty());
    }

    @Test
    void triggeringDevicePublishesToLinkedOutputs() throws Exception {
        final InputConfig config = inputConfig(inputConfigJson(""));
        final List<OutputConfig> outputs = List.of(outputConfig(
            "{\"device_key\": \"out-1\", \"device_type\": \"siren\", \"device_enabled\": true}"));
        stubTriggeringSetup(config, outputs);

        new Event(connection, SOURCE, detectorInState("on")).run();

        verify(channel).exchangeDeclare("test_exchange", BuiltinExchangeType.DIRECT);
        final ArgumentCaptor<String> routingKey = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<AMQP.BasicProperties> props = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
        final ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(channel).basicPublish(eq("test_exchange"), routingKey.capture(), props.capture(), body.capture());
        // no configured trigger topic: derived from the sanitized output device type
        assertEquals("event.trigger.siren", routingKey.getValue());
        assertEquals("30000", props.getValue().getExpiration());
        final var tree = new MessagePackMapper().readTree(body.getValue());
        assertTrue(tree.has("active_input"));
        assertTrue(tree.has("output_triggered"));
        assertTrue(tree.has("timestamp"));
        verify(channel).close();
    }

    @Test
    void configuredTriggerTopicIsUsed() throws Exception {
        final InputConfig config = inputConfig(inputConfigJson(""));
        final List<OutputConfig> outputs = List.of(outputConfig(
            "{\"device_key\": \"out-1\", \"device_type\": \"siren\", \"device_enabled\": true, \"trigger_topic\": \"Custom.Topic\"}"));
        stubTriggeringSetup(config, outputs);

        new Event(connection, SOURCE, detectorInState("on")).run();

        final ArgumentCaptor<String> routingKey = ArgumentCaptor.forClass(String.class);
        verify(channel).basicPublish(eq("test_exchange"), routingKey.capture(), any(), any(byte[].class));
        // configured topics are lower-cased before publishing
        assertEquals("custom.topic", routingKey.getValue());
    }

    @Test
    void triggerLatchSuppressesRapidRetrigger() throws Exception {
        final InputConfig config = inputConfig(inputConfigJson(", \"trigger_latch_duration\": 300"));
        final List<OutputConfig> outputs = List.of(outputConfig(
            "{\"device_key\": \"out-1\", \"device_type\": \"siren\", \"device_enabled\": true}"));
        stubTriggeringSetup(config, outputs);

        new Event(connection, SOURCE, detectorInState("on")).run();
        new Event(connection, SOURCE, detectorInState("on")).run();

        verify(channel, times(1)).basicPublish(anyString(), anyString(), any(), any(byte[].class));
    }

    @Test
    void multiTriggerGateRequiresConfiguredRate() throws Exception {
        final InputConfig config = inputConfig(
            inputConfigJson(", \"multi_trigger_rate\": 3, \"multi_trigger_interval\": 60"));
        final List<OutputConfig> outputs = List.of(outputConfig(
            "{\"device_key\": \"out-1\", \"device_type\": \"siren\", \"device_enabled\": true}"));
        stubTriggeringSetup(config, outputs);

        new Event(connection, SOURCE, detectorInState("on")).run();
        new Event(connection, SOURCE, detectorInState("on")).run();
        verify(channel, never()).basicPublish(anyString(), anyString(), any(), any(byte[].class));
        new Event(connection, SOURCE, detectorInState("on")).run();
        verify(channel, times(1)).basicPublish(anyString(), anyString(), any(), any(byte[].class));
    }

    @Test
    void disabledDeviceDoesNotPublish() throws Exception {
        final InputConfig config = inputConfig(
            String.format("{\"device_key\": \"%s\", \"device_enabled\": false}", DEVICE_KEY));
        when(deviceConfig.fetchInputDeviceConfig(DEVICE_KEY)).thenReturn(config);
        new Event(connection, SOURCE, detectorInState("on")).run();
        verify(deviceConfig, never()).getLinkedOutputs(any());
        verifyNoInteractions(connection);
    }

    @Test
    void missingLinkedOutputsDoesNotPublish() throws Exception {
        final InputConfig config = inputConfig(inputConfigJson(""));
        stubTriggeringSetup(config, null);
        new Event(connection, SOURCE, detectorInState("on")).run();
        verifyNoInteractions(connection);
    }

    @Test
    void disabledOutputIsSkipped() throws Exception {
        final InputConfig config = inputConfig(inputConfigJson(""));
        final List<OutputConfig> outputs = List.of(outputConfig(
            "{\"device_key\": \"out-1\", \"device_type\": \"siren\", \"device_enabled\": false}"));
        stubTriggeringSetup(config, outputs);
        new Event(connection, SOURCE, detectorInState("on")).run();
        verify(channel, never()).basicPublish(anyString(), anyString(), any(), any(byte[].class));
    }

    @Test
    void outputTriggerIntervalRateLimits() throws Exception {
        final InputConfig config = inputConfig(inputConfigJson(""));
        final List<OutputConfig> outputs = List.of(outputConfig(
            "{\"device_key\": \"out-1\", \"device_type\": \"siren\", \"device_enabled\": true, \"trigger_interval\": 300}"));
        stubTriggeringSetup(config, outputs);

        new Event(connection, SOURCE, detectorInState("on")).run();
        new Event(connection, SOURCE, detectorInState("on")).run();

        verify(channel, times(1)).basicPublish(anyString(), anyString(), any(), any(byte[].class));
    }

    @Test
    void sustainedTriggerEscalatesToPagerDutyOnce() throws Exception {
        eventProcessorStatic.when(() -> EventProcessor.isFeatureEnabled(EventProcessor.FEATURE_FLAG_PAGER_DUTY_TICKETS))
            .thenReturn(true);
        eventProcessorStatic.when(EventProcessor::getPagerDutyRoutingKey).thenReturn("routing-key");
        eventProcessorStatic.when(EventProcessor::getPagerDuty).thenReturn(pagerDuty);
        eventProcessorStatic.when(EventProcessor::getAppName).thenReturn("app");
        eventProcessorStatic.when(EventProcessor::getDeviceName).thenReturn("dev");
        final EventResult result = mock(EventResult.class);
        when(result.getDedupKey()).thenReturn("app-" + DEVICE_KEY);
        when(result.getStatus()).thenReturn("success");
        when(pagerDuty.trigger(any(TriggerIncident.class))).thenReturn(result);

        // simulate a trigger condition that started 30s ago
        final TriggerHistory latchHistory =
            (TriggerHistory) TestStatics.getStaticField(Event.class, "triggerLatchHistory");
        TestStatics.injectTriggerAt(latchHistory, DEVICE_KEY, Instant.now().minusSeconds(30));

        final InputConfig config = inputConfig(inputConfigJson(", \"activation_escalation\": 10"));
        final List<OutputConfig> outputs = List.of(outputConfig(
            "{\"device_key\": \"out-1\", \"device_type\": \"siren\", \"device_enabled\": true}"));
        stubTriggeringSetup(config, outputs);

        new Event(connection, SOURCE, detectorInState("on")).run();
        verify(pagerDuty, times(1)).trigger(any(TriggerIncident.class));
        assertEquals("app-" + DEVICE_KEY, TestStatics.escalations().get(DEVICE_KEY));

        // a second event for the same device does not re-escalate
        new Event(connection, SOURCE, detectorInState("on")).run();
        verify(pagerDuty, times(1)).trigger(any(TriggerIncident.class));
        verify(channel, times(2)).basicPublish(anyString(), anyString(), any(), any(byte[].class));
    }

    @Test
    void publishFailureIsContainedPerOutput() throws Exception {
        final InputConfig config = inputConfig(inputConfigJson(""));
        final List<OutputConfig> outputs = List.of(outputConfig(
            "{\"device_key\": \"out-1\", \"device_type\": \"siren\", \"device_enabled\": true}"));
        stubTriggeringSetup(config, outputs);
        org.mockito.Mockito.doThrow(new IOException("broker down"))
            .when(channel).basicPublish(anyString(), anyString(), any(), any(byte[].class));

        // the failure is logged and spanned, not propagated
        new Event(connection, SOURCE, detectorInState("on")).run();
        verify(channel).basicPublish(anyString(), anyString(), any(), any(byte[].class));
        verify(channel).close();
    }

    @Test
    void channelFailureIsContained() throws Exception {
        final InputConfig config = inputConfig(inputConfigJson(""));
        final List<OutputConfig> outputs = List.of(outputConfig(
            "{\"device_key\": \"out-1\", \"device_type\": \"siren\", \"device_enabled\": true}"));
        stubTriggeringSetup(config, outputs);
        when(connection.createChannel()).thenThrow(new IOException("no channel"));

        new Event(connection, SOURCE, detectorInState("on")).run();
        verify(channel, never()).basicPublish(anyString(), anyString(), any(), any(byte[].class));
    }

    @Test
    void deviceUpdateStringWithoutDeviceIsLogged() {
        final Event event = new Event(connection, SOURCE, "raw update");
        assertNull(TestStatics.getField(event, "device"));
        assertEquals("raw update", TestStatics.getField(event, "deviceUpdateString"));
        event.run();
        verifyNoInteractions(deviceConfig);
    }

    @Test
    void inboundTraceparentIsPropagatedToPublishBody() throws Exception {
        // Hermetic OTEL: keep the SDK recording but stop it reaching for a collector.
        System.setProperty("otel.traces.exporter", "none");
        System.setProperty("otel.metrics.exporter", "none");
        System.setProperty("otel.logs.exporter", "none");
        try {
            OtelSupport.init();
            final String inbound = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
            final InputConfig config = inputConfig(inputConfigJson(""));
            final List<OutputConfig> outputs = List.of(outputConfig(
                "{\"device_key\": \"out-1\", \"device_type\": \"siren\", \"device_enabled\": true}"));
            stubTriggeringSetup(config, outputs);

            final Event event = new Event(connection, SOURCE, detectorInState("on"));
            event.setTraceContext(inbound, null);
            event.run();

            final ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
            verify(channel).basicPublish(eq("test_exchange"), anyString(), any(), body.capture());
            final var tree = new MessagePackMapper().readTree(body.getValue());
            assertTrue(tree.has("traceparent"), "publish body must carry a traceparent");
            final String outbound = tree.get("traceparent").asText();
            assertTrue(outbound.startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-"),
                "outbound traceparent must continue the inbound trace id");
        } finally {
            OtelSupport.shutdown();
            GlobalOpenTelemetry.resetForTest();
            System.clearProperty("otel.traces.exporter");
            System.clearProperty("otel.metrics.exporter");
            System.clearProperty("otel.logs.exporter");
        }
    }
}
