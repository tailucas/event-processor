package tailucas.app.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tailucas.app.EventProcessor;
import tailucas.app.TestStatics;
import tailucas.app.device.Event;
import tailucas.app.device.config.OutputConfig;
import tailucas.app.provider.DeviceConfig;
import tailucas.app.provider.DeviceState;

/**
 * The snapshot endpoint's contract: which devices it addresses, what it publishes,
 * and how it reports a camera it did not ask.
 *
 * <p>Each test uses its own device id because the endpoint remembers the last
 * request per device for the bridge's ten-second window, and that state is static.
 */
@WebMvcTest(SnapshotCommand.class)
class SnapshotCommandTest {

    @Autowired
    private MockMvc mvc;

    // deep stubs allow the SLF4J fluent API chain (atInfo().setMessage()...) on the mock
    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS)
    private Logger log;

    private static DeviceState.DeviceSnapshot device(
            String deviceKey, String component, String label, String availability, String locationId) {
        return new DeviceState.DeviceSnapshot(
            deviceKey, component, locationId, label, "Ring Doorbell 3", availability,
            Integer.valueOf(87), Integer.valueOf(87), "full", Integer.valueOf(-68), "wifi",
            "ok", "ok", "ok", "up-to-date",
            "2026-11-28T05:00:00Z", "2026-11-28T05:00:00Z", Map.of());
    }

    /** An output configuration as the panel stores one: enabled, with a trigger topic. */
    private static OutputConfig outputConfig(String deviceKey, String deviceType, boolean enabled) {
        final OutputConfig config = new OutputConfig();
        TestStatics.setField(config, "deviceKey", deviceKey);
        TestStatics.setField(config, "deviceType", deviceType);
        TestStatics.setField(config, "deviceLabel", deviceKey);
        TestStatics.setField(config, "triggerTopic", "event.trigger.snapshot");
        TestStatics.setField(config, "device_enabled", Boolean.valueOf(enabled));
        return config;
    }

    @Test
    void requestsTheBridgeTriggerTopicForOneCamera() throws Exception {
        final DeviceState state = mock(DeviceState.class);
        when(state.getDevices()).thenReturn(List.of(
            device("cam-a", "camera", "Front Gate", "online", "loc-1")));
        try (MockedStatic<DeviceState> mockedState = mockStatic(DeviceState.class);
             MockedStatic<EventProcessor> mockedMqtt = mockStatic(EventProcessor.class)) {
            mockedState.when(DeviceState::getInstance).thenReturn(state);
            mockedMqtt.when(() -> EventProcessor.publishMqtt(anyString(), anyString())).thenReturn(true);

            mvc.perform(post("/snapshot").param("device_key", "cam-a"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requested[0].device_key").value("cam-a"))
                .andExpect(jsonPath("$.requested[0].label").value("Front Gate"))
                .andExpect(jsonPath("$.skipped.length()").value(0));

            mockedMqtt.verify(() -> EventProcessor.publishMqtt(
                "ring/loc-1/camera/cam-a/take_snapshot/command", "PRESS"));
        }
    }

    @Test
    void aBareCommandRequestsEveryOnlineCamera() throws Exception {
        final DeviceState state = mock(DeviceState.class);
        when(state.getDevices()).thenReturn(List.of(
            device("cam-b1", "camera", "Front Gate", "online", "loc-1"),
            device("cam-b2", "camera", "Patio Camera", "online", "loc-1")));
        try (MockedStatic<DeviceState> mockedState = mockStatic(DeviceState.class);
             MockedStatic<EventProcessor> mockedMqtt = mockStatic(EventProcessor.class)) {
            mockedState.when(DeviceState::getInstance).thenReturn(state);
            mockedMqtt.when(() -> EventProcessor.publishMqtt(anyString(), anyString())).thenReturn(true);

            mvc.perform(post("/snapshot"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requested.length()").value(2));
        }
    }

    @Test
    void anOfflineCameraIsReportedRatherThanRequested() throws Exception {
        final DeviceState state = mock(DeviceState.class);
        when(state.getDevices()).thenReturn(List.of(
            device("cam-c", "camera", "Pool Camera", "offline", "loc-1")));
        try (MockedStatic<DeviceState> mockedState = mockStatic(DeviceState.class);
             MockedStatic<EventProcessor> mockedMqtt = mockStatic(EventProcessor.class)) {
            mockedState.when(DeviceState::getInstance).thenReturn(state);

            mvc.perform(post("/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped[0].reason").value("offline"));

            mockedMqtt.verifyNoInteractions();
        }
    }

    @Test
    void aBareCommandDoesNotReportNonCamerasAsSkips() throws Exception {
        // the house is mostly sensors: reporting them as "not a camera" buries the
        // outcome behind one line per device and reads like a failure
        final DeviceState state = mock(DeviceState.class);
        when(state.getDevices()).thenReturn(List.of(
            device("alarm-g", "alarm", "Back Door", "online", "loc-1"),
            device("cam-g", "camera", "Front Gate", "online", "loc-1")));
        try (MockedStatic<DeviceState> mockedState = mockStatic(DeviceState.class);
             MockedStatic<EventProcessor> mockedMqtt = mockStatic(EventProcessor.class)) {
            mockedState.when(DeviceState::getInstance).thenReturn(state);
            mockedMqtt.when(() -> EventProcessor.publishMqtt(anyString(), anyString())).thenReturn(true);

            mvc.perform(post("/snapshot"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requested.length()").value(1))
                .andExpect(jsonPath("$.skipped.length()").value(0));
        }
    }

    @Test
    void anExplicitlyNamedNonCameraIsReported() throws Exception {
        // naming a sensor is a mistake worth explaining, unlike walking every device
        final DeviceState state = mock(DeviceState.class);
        when(state.getDevices()).thenReturn(List.of(
            device("alarm-h", "alarm", "Back Door", "online", "loc-1")));
        try (MockedStatic<DeviceState> mockedState = mockStatic(DeviceState.class);
             MockedStatic<EventProcessor> mockedMqtt = mockStatic(EventProcessor.class)) {
            mockedState.when(DeviceState::getInstance).thenReturn(state);

            mvc.perform(post("/snapshot").param("device_key", "alarm-h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped[0].reason").value("not_a_camera"));

            mockedMqtt.verifyNoInteractions();
        }
    }

    @Test
    void anUnknownDeviceKeyIsNotFound() throws Exception {
        final DeviceState state = mock(DeviceState.class);
        when(state.getDevices()).thenReturn(List.of());
        try (MockedStatic<DeviceState> mockedState = mockStatic(DeviceState.class)) {
            mockedState.when(DeviceState::getInstance).thenReturn(state);

            mvc.perform(post("/snapshot").param("device_key", "nope"))
                .andExpect(status().isNotFound());
        }
    }

    @Test
    void aCameraIsAskedOnlyOnceInsideTheBridgesWindow() throws Exception {
        // ring-mqtt ignores a second on-demand snapshot within ten seconds; reporting
        // that is better than a second "requested" reply that produces nothing
        final DeviceState state = mock(DeviceState.class);
        when(state.getDevices()).thenReturn(List.of(
            device("cam-e", "camera", "Front Gate", "online", "loc-1")));
        try (MockedStatic<DeviceState> mockedState = mockStatic(DeviceState.class);
             MockedStatic<EventProcessor> mockedMqtt = mockStatic(EventProcessor.class)) {
            mockedState.when(DeviceState::getInstance).thenReturn(state);
            mockedMqtt.when(() -> EventProcessor.publishMqtt(anyString(), anyString())).thenReturn(true);

            mvc.perform(post("/snapshot").param("device_key", "cam-e"))
                .andExpect(status().isAccepted());
            mvc.perform(post("/snapshot").param("device_key", "cam-e"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped[0].reason").value("recently_requested"))
                .andExpect(jsonPath("$.skipped[0].retry_after_seconds").isNumber());
        }
    }

    @Test
    void aPipelineThatRefusesThePublishIsReported() throws Exception {
        final DeviceState state = mock(DeviceState.class);
        when(state.getDevices()).thenReturn(List.of(
            device("cam-f", "camera", "Front Gate", "online", "loc-1")));
        try (MockedStatic<DeviceState> mockedState = mockStatic(DeviceState.class);
             MockedStatic<EventProcessor> mockedMqtt = mockStatic(EventProcessor.class)) {
            mockedState.when(DeviceState::getInstance).thenReturn(state);
            mockedMqtt.when(() -> EventProcessor.publishMqtt(anyString(), anyString())).thenReturn(false);

            mvc.perform(post("/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped[0].reason").value("publish_failed"));
        }
    }

    @Test
    void aNamedCameraOutputIsTriggeredOverTheControlExchange() throws Exception {
        // snapshot-processor cameras are output configurations, not MQTT devices
        final DeviceState state = mock(DeviceState.class);
        when(state.getDevices()).thenReturn(List.of());
        final DeviceConfig configs = mock(DeviceConfig.class);
        when(configs.fetchOutputDeviceConfig("Pool Camera"))
            .thenReturn(outputConfig("Pool Camera", "Camera", true));
        try (MockedStatic<DeviceState> mockedState = mockStatic(DeviceState.class);
             MockedStatic<DeviceConfig> mockedConfig = mockStatic(DeviceConfig.class);
             MockedStatic<Event> mockedEvent = mockStatic(Event.class)) {
            mockedState.when(DeviceState::getInstance).thenReturn(state);
            mockedConfig.when(DeviceConfig::getInstance).thenReturn(configs);
            mockedEvent.when(() -> Event.publishRequestedTrigger(any(), any(), anyString())).thenReturn(true);

            mvc.perform(post("/snapshot").param("camera_key", "Pool Camera"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requested[0].device_key").value("Pool Camera"))
                .andExpect(jsonPath("$.skipped.length()").value(0));
        }
    }

    @Test
    void aDisabledCameraIsReportedRatherThanTriggered() throws Exception {
        final DeviceState state = mock(DeviceState.class);
        when(state.getDevices()).thenReturn(List.of());
        final DeviceConfig configs = mock(DeviceConfig.class);
        when(configs.fetchOutputDeviceConfig("Test Camera"))
            .thenReturn(outputConfig("Test Camera", "camera", false));
        try (MockedStatic<DeviceState> mockedState = mockStatic(DeviceState.class);
             MockedStatic<DeviceConfig> mockedConfig = mockStatic(DeviceConfig.class);
             MockedStatic<Event> mockedEvent = mockStatic(Event.class)) {
            mockedState.when(DeviceState::getInstance).thenReturn(state);
            mockedConfig.when(DeviceConfig::getInstance).thenReturn(configs);

            mvc.perform(post("/snapshot").param("camera_key", "Test Camera"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped[0].reason").value("disabled"));

            mockedEvent.verifyNoInteractions();
        }
    }

    @Test
    void anUnknownCameraKeyIsReported() throws Exception {
        final DeviceState state = mock(DeviceState.class);
        when(state.getDevices()).thenReturn(List.of());
        final DeviceConfig configs = mock(DeviceConfig.class);
        when(configs.fetchOutputDeviceConfig("Nowhere Camera")).thenReturn(null);
        try (MockedStatic<DeviceState> mockedState = mockStatic(DeviceState.class);
             MockedStatic<DeviceConfig> mockedConfig = mockStatic(DeviceConfig.class);
             MockedStatic<Event> mockedEvent = mockStatic(Event.class)) {
            mockedState.when(DeviceState::getInstance).thenReturn(state);
            mockedConfig.when(DeviceConfig::getInstance).thenReturn(configs);

            mvc.perform(post("/snapshot").param("camera_key", "Nowhere Camera"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped[0].reason").value("unknown_camera"));

            mockedEvent.verifyNoInteractions();
        }
    }
}
