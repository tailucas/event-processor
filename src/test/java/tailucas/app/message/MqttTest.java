package tailucas.app.message;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.context.ApplicationContext;

import com.rabbitmq.client.Connection;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import tailucas.app.TestStatics;
import tailucas.app.device.Event;
import tailucas.app.device.Ring;
import tailucas.app.device.config.HAConfig;
import tailucas.app.provider.DeviceConfig;

class MqttTest {

    private MockedStatic<DeviceConfig> deviceConfigStatic;
    private DeviceConfig deviceConfig;
    private ApplicationContext springApp;
    private ExecutorService srv;
    private Connection connection;
    private Mqtt mqtt;

    @BeforeAll
    static void initStatics() {
        TestStatics.configureTestMetrics();
    }

    @BeforeEach
    void setUp() {
        deviceConfig = mock(DeviceConfig.class);
        deviceConfigStatic = mockStatic(DeviceConfig.class);
        deviceConfigStatic.when(DeviceConfig::getInstance).thenReturn(deviceConfig);
        springApp = mock(ApplicationContext.class);
        srv = mock(ExecutorService.class);
        connection = mock(Connection.class);
        mqtt = new Mqtt(springApp, srv, connection);
    }

    @AfterEach
    void tearDown() {
        deviceConfigStatic.close();
    }

    private static MqttMessage message(String payload) {
        return new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
    }

    private ArgumentCaptor<Event> eventCaptor() {
        return ArgumentCaptor.forClass(Event.class);
    }

    @Test
    void emptyPayloadIsIgnored() throws Exception {
        mqtt.messageArrived("some/topic", new MqttMessage(new byte[0]));
        verifyNoInteractions(srv, deviceConfig);
    }

    @Test
    void okPayloadSubmitsHeartbeatEvent() throws Exception {
        mqtt.messageArrived("device/topic", message("OK"));
        final ArgumentCaptor<Event> captor = eventCaptor();
        verify(srv).execute(captor.capture());
        assertNull(TestStatics.getField(captor.getValue(), "device"));
        assertEquals("OK", TestStatics.getField(captor.getValue(), "deviceUpdateString"));
        assertEquals("device/topic", TestStatics.getField(captor.getValue(), "source"));
    }

    @Test
    void inverterTopicsAreIgnored() throws Exception {
        mqtt.messageArrived("inverter/grid/voltage", message("232.5"));
        verifyNoInteractions(srv);
    }

    @Test
    void homeAssistantEmptyPayloadIsEntityRemoval() throws Exception {
        // an empty discovery payload deletes the entity; ring-mqtt publishes one for
        // every entity a device does not have
        assertDoesNotThrow(() ->
            mqtt.messageArrived("homeassistant/light/loc/device_audio/config", new MqttMessage(new byte[0])));
        verifyNoInteractions(srv, deviceConfig);
    }

    @Test
    void homeAssistantStatusIsIgnored() throws Exception {
        mqtt.messageArrived("homeassistant/status", message("online"));
        verifyNoInteractions(srv, deviceConfig);
    }

    @Test
    void homeAssistantDiscoveryIsStored() throws Exception {
        mqtt.messageArrived("homeassistant/alarm_control_panel/ring/config", message("{\"name\": \"Ring Alarm\"}"));
        final ArgumentCaptor<HAConfig> captor = ArgumentCaptor.forClass(HAConfig.class);
        verify(deviceConfig).putHaConfig(captor.capture());
        assertEquals("Ring Alarm", captor.getValue().getName());
        verifyNoInteractions(srv);
    }

    @Test
    void homeAssistantNonJsonPayloadIsIgnored() throws Exception {
        mqtt.messageArrived("homeassistant/sensor/foo/attributes", message("plain-text"));
        verify(deviceConfig, never()).putHaConfig(any());
        verifyNoInteractions(srv);
    }

    @Test
    void homeAssistantBadJsonIsContained() throws Exception {
        assertDoesNotThrow(() -> mqtt.messageArrived("homeassistant/sensor/foo/config", message("{bad json")));
        verify(deviceConfig, never()).putHaConfig(any());
        verifyNoInteractions(srv);
    }

    @Test
    void ringJsonPayloadPropagatesTraceContext() throws Exception {
        final String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        final String baggage = "device.name=porch-camera";
        mqtt.messageArrived(
            "ring/component/alarm/device-123/motion/state",
            message(String.format("{\"batteryLevel\": 80, \"traceparent\": \"%s\", \"baggage\": \"%s\"}", traceparent, baggage)));
        final ArgumentCaptor<Event> captor = eventCaptor();
        verify(srv).execute(captor.capture());
        assertEquals(traceparent, TestStatics.getField(captor.getValue(), "traceparent"));
        assertEquals(baggage, TestStatics.getField(captor.getValue(), "baggage"));
    }

    @Test
    void ringJsonPayloadWithoutTraceContextHasNone() throws Exception {
        mqtt.messageArrived("ring/component/alarm/device-123/motion/state", message("{\"batteryLevel\": 80}"));
        final ArgumentCaptor<Event> captor = eventCaptor();
        verify(srv).execute(captor.capture());
        assertNull(TestStatics.getField(captor.getValue(), "traceparent"));
        assertNull(TestStatics.getField(captor.getValue(), "baggage"));
    }

    @Test
    void sensorTopicPropagatesTraceContextPerInput() throws Exception {
        final String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        final String baggage = "device.name=kitchen-sensor";
        final String payload = """
            {"traceparent": "%s", "baggage": "%s",
             "device_label": "multi", "timestamp": 1700000000, "uptime": 60,
             "input_1": {"input_label": "temperature", "sample_value": 25.5, "normal_value": 20, "active": true},
             "input_2": {"input_label": "humidity", "sample_value": 40.0, "normal_value": 50, "active": false}}
            """.formatted(traceparent, baggage);
        mqtt.messageArrived("sensor/kitchen/env", message(payload));
        final ArgumentCaptor<Event> captor = eventCaptor();
        verify(srv, times(2)).execute(captor.capture());
        assertEquals(traceparent, TestStatics.getField(captor.getValue(), "traceparent"));
        assertEquals(baggage, TestStatics.getField(captor.getValue(), "baggage"));
    }

    @Test
    void meterTopicPropagatesTraceContext() throws Exception {
        final String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        mqtt.messageArrived(
            "meter/utility/water",
            message(String.format("{\"register_reading\": 12345, \"traceparent\": \"%s\"}", traceparent)));
        final ArgumentCaptor<Event> captor = eventCaptor();
        verify(srv).execute(captor.capture());
        assertEquals(traceparent, TestStatics.getField(captor.getValue(), "traceparent"));
    }

    @Test
    void meterHeartbeatMessageTypeIsInterpreted() throws Exception {
        mqtt.messageArrived(
            "meter/utility/water",
            message("{\"register_reading\": 12345, \"message_type\": \"heartbeat\"}"));
        final ArgumentCaptor<Event> captor = eventCaptor();
        verify(srv).execute(captor.capture());
        final Object device = TestStatics.getField(captor.getValue(), "device");
        assertEquals("heartbeat", TestStatics.getField(device, "messageType"));
    }

    @Test
    void plainPayloadHasNoTraceContext() throws Exception {
        mqtt.messageArrived("ring/component/alarm/device-123/motion/state", message("ON"));
        final ArgumentCaptor<Event> captor = eventCaptor();
        verify(srv).execute(captor.capture());
        assertNull(TestStatics.getField(captor.getValue(), "traceparent"));
        assertNull(TestStatics.getField(captor.getValue(), "baggage"));
    }

    @Test
    void ringPlainPayloadSubmitsEvent() throws Exception {
        mqtt.messageArrived("ring/component/alarm/device-123/motion/state", message("ON"));
        final ArgumentCaptor<Event> captor = eventCaptor();
        verify(srv).execute(captor.capture());
        final Object device = TestStatics.getField(captor.getValue(), "device");
        assertInstanceOf(Ring.class, device);
        assertEquals("ON", ((Ring) device).getState());
        assertEquals("device-123", ((Ring) device).getDeviceKey());
    }

    @Test
    void ringJsonPayloadSubmitsEvent() throws Exception {
        mqtt.messageArrived("ring/component/alarm/device-123/motion/state", message("{\"batteryLevel\": 80}"));
        final ArgumentCaptor<Event> captor = eventCaptor();
        verify(srv).execute(captor.capture());
        final Object device = TestStatics.getField(captor.getValue(), "device");
        assertInstanceOf(Ring.class, device);
        assertEquals(Integer.valueOf(80), ((Ring) device).getBatteryLevel());
    }

    @Test
    void ringMessagePostsRingEventMetric() throws Exception {
        mqtt.messageArrived("ring/component/alarm/device-123/motion/state", message("ON"));
        final List<Map<String, String>> labelSets = TestStatics.metricLabelSets(Ring.RING_EVENT_METRIC);
        assertTrue(labelSets.stream().anyMatch(labels ->
            "device-123".equals(labels.get("ring_device_id"))
                && "motion".equals(labels.get("ring_update_subject"))
                && "ON".equals(labels.get("ring_state"))),
            () -> String.format("No ring event metric for the MQTT message in %s", labelSets));
    }

    @Test
    void cameraWirelessAttributesPostSignalGauge() throws Exception {
        // exact topic and payload from the bridge
        mqtt.messageArrived("ring/cfee52e0-fb85-46a7-868e-22436bef6123/camera/54e0191a1d8d/wireless/attributes",
            message("{\"wirelessNetwork\":\"NotNo\",\"wirelessSignal\":-67}"));
        final ArgumentCaptor<Event> captor = eventCaptor();
        verify(srv).execute(captor.capture());
        final Ring camera = (Ring) TestStatics.getField(captor.getValue(), "device");
        assertEquals("NotNo", camera.getWirelessNetwork());
        assertEquals(Integer.valueOf(-67), camera.getWirelessSignal());
        final Double signal = TestStatics.metricValue(
            Ring.RING_WIRELESS_SIGNAL_METRIC, "ring_device_id", "54e0191a1d8d");
        assertNotNull(signal);
        assertEquals(-67.0, signal.doubleValue());
    }

    @Test
    void cameraBatteryAttributesPostBatteryGauges() throws Exception {
        mqtt.messageArrived("ring/cfee52e0-fb85-46a7-868e-22436bef6123/camera/54e0191a1d8d/battery/attributes",
            message("{\"batteryLevel\":100,\"batteryLife\":100}"));
        final ArgumentCaptor<Event> captor = eventCaptor();
        verify(srv).execute(captor.capture());
        final Ring camera = (Ring) TestStatics.getField(captor.getValue(), "device");
        assertEquals(Integer.valueOf(100), camera.getBatteryLevel());
        assertEquals(Integer.valueOf(100), camera.getBatteryLife());
        assertNotNull(TestStatics.metricValue(
            Ring.RING_BATTERY_LEVEL_METRIC, "ring_device_id", "54e0191a1d8d"));
        assertNotNull(TestStatics.metricValue(
            Ring.RING_BATTERY_LIFE_METRIC, "ring_device_id", "54e0191a1d8d"));
    }

    @Test
    void chimeInfoStateIsSubmitted() throws Exception {
        // the chime domain routes through the same Ring device model
        mqtt.messageArrived("ring/cfee52e0-fb85-46a7-868e-22436bef6123/chime/90486cbb3b2c/info/state",
            message("{\"firmwareStatus\":\"Up To Date\",\"lastUpdate\":\"2026-11-04T06:11:01Z\","
                + "\"wirelessNetwork\":\"No\",\"wirelessSignal\":-52}"));
        final ArgumentCaptor<Event> captor = eventCaptor();
        verify(srv).execute(captor.capture());
        final Object device = TestStatics.getField(captor.getValue(), "device");
        assertInstanceOf(Ring.class, device);
        final Ring chime = (Ring) device;
        assertEquals("chime", chime.getComponentName());
        assertEquals("90486cbb3b2c", chime.getDeviceKey());
        assertEquals("No", chime.getWirelessNetwork());
        assertEquals(Integer.valueOf(-52), chime.getWirelessSignal());
        final Double signal = TestStatics.metricValue(
            Ring.RING_WIRELESS_SIGNAL_METRIC, "ring_device_id", "90486cbb3b2c");
        assertNotNull(signal);
        assertEquals(-52.0, signal.doubleValue());
    }

    @Test
    void cameraInfoPostsHealthGauges() throws Exception {
        // real payload shape from the bridge: cameras report battery level and
        // wireless signal on info/state
        mqtt.messageArrived("ring/cfee52e0-fb85-46a7-868e-22436bef6123/camera/54e0191a1d8d/info/state",
            message("{\"batteryLevel\":100,\"batteryLife\":100,\"firmwareStatus\":\"Up to Date\","
                + "\"lastUpdate\":\"2026-11-04T09:12:58Z\",\"wirelessNetwork\":\"NotNo\",\"wirelessSignal\":-67}"));
        final Double batteryLevel = TestStatics.metricValue(
            Ring.RING_BATTERY_LEVEL_METRIC, "ring_device_id", "54e0191a1d8d");
        final Double wirelessSignal = TestStatics.metricValue(
            Ring.RING_WIRELESS_SIGNAL_METRIC, "ring_device_id", "54e0191a1d8d");
        assertNotNull(batteryLevel);
        assertNotNull(wirelessSignal);
        assertEquals(100.0, batteryLevel.doubleValue());
        assertEquals(-67.0, wirelessSignal.doubleValue());
    }

    @Test
    void ringCameraSnapshotImageIsSummarised() throws Exception {
        // the bridge publishes a camera snapshot as raw image bytes on this topic
        final byte[] payload = "\ufffd\ufffd\ufffd\ufffd\u0000\u0010Lavc61.19.101".getBytes(StandardCharsets.UTF_8);
        mqtt.messageArrived("ring/cfee52e0-fb85-46a7-868e-22436bef6123/camera/54e0191a1d8d/snapshot/image",
            new MqttMessage(payload));
        final ArgumentCaptor<Event> captor = eventCaptor();
        verify(srv).execute(captor.capture());
        final Object device = TestStatics.getField(captor.getValue(), "device");
        assertInstanceOf(Ring.class, device);
        assertEquals(Ring.BINARY_PAYLOAD_STATE, ((Ring) device).getState());
        // the picture itself is carried for outputs, not for logs
        assertArrayEquals(payload, ((Ring) device).getImage());
    }

    @Test
    void ringTextPayloadsCarryNoImage() throws Exception {
        mqtt.messageArrived("ring/component/alarm/device-123/contact/state", message("OFF"));
        final ArgumentCaptor<Event> captor = eventCaptor();
        verify(srv).execute(captor.capture());
        final Ring ring = (Ring) TestStatics.getField(captor.getValue(), "device");
        assertEquals("OFF", ring.getState());
        assertNull(ring.getImage());
    }

    @Test
    void ringBadJsonIsContained() throws Exception {
        assertDoesNotThrow(() ->
            mqtt.messageArrived("ring/component/alarm/device-123/motion/state", message("{bad json")));
        verifyNoInteractions(srv);
    }

    @Test
    void sensorTopicSubmitsEventPerInput() throws Exception {
        final String payload = """
            {"device_label": "multi", "timestamp": 1700000000, "uptime": 60,
             "input_1": {"input_label": "temperature", "sample_value": 25.5, "normal_value": 20, "active": true},
             "input_2": {"input_label": "humidity", "sample_value": 40.0, "normal_value": 50, "active": false}}
            """;
        mqtt.messageArrived("sensor/kitchen/env", message(payload));
        verify(srv, times(2)).execute(any(Event.class));
    }

    @Test
    void meterTopicSubmitsEvent() throws Exception {
        mqtt.messageArrived("meter/utility/water", message("{\"register_reading\": 12345}"));
        final ArgumentCaptor<Event> captor = eventCaptor();
        verify(srv).execute(captor.capture());
        assertEquals("meter/utility/water", TestStatics.getField(captor.getValue(), "source"));
    }

    @Test
    void unknownTopicsAreIgnored() throws Exception {
        mqtt.messageArrived("some/other/topic", message("payload"));
        verifyNoInteractions(srv);
    }

    @Test
    void invalidMeterPayloadIsContained() throws Exception {
        assertDoesNotThrow(() -> mqtt.messageArrived("meter/utility/water", message("not json")));
        verifyNoInteractions(srv);
    }

    @Test
    void deliveryCompleteIsNoOp() {
        assertDoesNotThrow(() -> mqtt.deliveryComplete(null));
    }
}
