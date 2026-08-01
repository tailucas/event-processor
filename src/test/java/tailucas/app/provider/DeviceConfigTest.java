package tailucas.app.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import com.fasterxml.jackson.databind.ObjectMapper;

import tailucas.app.TestStatics;
import tailucas.app.device.Ring;
import tailucas.app.device.config.HAConfig;
import tailucas.app.device.config.InputConfig;
import tailucas.app.device.config.MeterConfig;
import tailucas.app.device.config.OutputConfig;

class DeviceConfigTest {

    private static final String INPUT_JSON = """
        [{"device_key": "Kitchen Smoke", "device_type": "detector", "device_enabled": true}]
        """;
    private static final String OUTPUT_LINK_JSON = """
        [{"device_key": "out-1", "device_type": "siren", "device_label": "Siren", "device_enabled": true}]
        """;

    private HttpClient httpClient;
    private DeviceConfig deviceConfig;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        deviceConfig = new DeviceConfig(httpClient, "config-host", "8080");
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> httpResponse(int status, String body) {
        final HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private void stubSend(HttpResponse<String> first, HttpResponse<String>... rest) throws Exception {
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(first, rest);
    }

    @Test
    void fetchInputDeviceConfigReturnsConfig() throws Exception {
        stubSend(httpResponse(200, INPUT_JSON));
        final InputConfig config = deviceConfig.fetchInputDeviceConfig("Kitchen Smoke");
        assertEquals("Kitchen Smoke", config.getDeviceKey());
        assertEquals("detector", config.getDeviceType());
        assertTrue(config.isDeviceEnabled());
    }

    @Test
    void fetchRequestTargetsConfigService() throws Exception {
        stubSend(httpResponse(200, INPUT_JSON));
        deviceConfig.fetchInputDeviceConfig("Kitchen Smoke");
        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        final HttpRequest request = captor.getValue();
        assertEquals("GET", request.method());
        assertEquals("http://config-host:8080/api/input_config?device_key=Kitchen%20Smoke",
            request.uri().toString());
    }

    @Test
    void fetchInputDeviceConfigRejectsMultipleItems() throws Exception {
        stubSend(httpResponse(200, """
            [{"device_key": "K1"}, {"device_key": "K1"}]
            """));
        final IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> deviceConfig.fetchInputDeviceConfig("K1"));
        assertTrue(ex.getMessage().contains("Expected exactly 1 configuration item for K1"));
    }

    @Test
    void fetchInputDeviceConfigRejectsKeyMismatch() throws Exception {
        stubSend(httpResponse(200, """
            [{"device_key": "Other"}]
            """));
        final IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> deviceConfig.fetchInputDeviceConfig("K1"));
        assertTrue(ex.getMessage().contains("Device key mismatch between device (K1) and config (Other)"));
    }

    @Test
    void fetchReturnsNullOnHttpError() throws Exception {
        stubSend(httpResponse(404, "{\"detail\": \"not found\"}"));
        assertNull(deviceConfig.fetchInputDeviceConfig("K1"));
    }

    @Test
    void fetchReturnsNullOnHttpErrorWithNonJsonBody() throws Exception {
        stubSend(httpResponse(500, "boom"));
        assertNull(deviceConfig.fetchInputDeviceConfig("K1"));
    }

    @Test
    void anySuccessfulHttpStatusIsAccepted() throws Exception {
        // guards the responseCode / 100 == 2 semantics (the old % 200 check accepted HTTP 400)
        stubSend(httpResponse(201, INPUT_JSON));
        assertEquals("Kitchen Smoke", deviceConfig.fetchInputDeviceConfig("Kitchen Smoke").getDeviceKey());
    }

    @Test
    void emptySuccessBodyRaisesIOException() {
        // codifies the current behavior: a success status with an empty body
        // fails Jackson parsing (MismatchedInputException is an IOException)
        assertThrows(java.io.IOException.class, () -> {
            stubSend(httpResponse(204, ""));
            deviceConfig.fetchInputDeviceConfig("K1");
        });
    }

    @Test
    void configurationIsCachedWithinTtl() throws Exception {
        stubSend(httpResponse(200, INPUT_JSON));
        deviceConfig.fetchInputDeviceConfig("Kitchen Smoke");
        deviceConfig.fetchInputDeviceConfig("Kitchen Smoke");
        verify(httpClient, times(1))
            .send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @Test
    void invalidateConfigurationClearsCachedEntries() throws Exception {
        stubSend(httpResponse(200, INPUT_JSON), httpResponse(200, INPUT_JSON));
        deviceConfig.fetchInputDeviceConfig("Kitchen Smoke");
        deviceConfig.invalidateConfiguration("Kitchen Smoke");
        deviceConfig.fetchInputDeviceConfig("Kitchen Smoke");
        verify(httpClient, times(2))
            .send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @Test
    void invalidateConfigurationClearsNestedOutputEntries() throws Exception {
        final InputConfig inputConfig = new ObjectMapper()
            .readValue("{\"device_key\": \"K1\"}", InputConfig.class);
        stubSend(httpResponse(200, OUTPUT_LINK_JSON), httpResponse(200, OUTPUT_LINK_JSON));
        List<OutputConfig> outputs = deviceConfig.getLinkedOutputs(inputConfig);
        assertEquals(1, outputs.size());
        assertEquals("out-1", outputs.getFirst().getDeviceKey());
        // cached under K1/output_link on the second call
        deviceConfig.getLinkedOutputs(inputConfig);
        verify(httpClient, times(1))
            .send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        // invalidating the *output* device must also drop the input's cached output links
        deviceConfig.invalidateConfiguration("out-1");
        outputs = deviceConfig.getLinkedOutputs(inputConfig);
        assertEquals(1, outputs.size());
        verify(httpClient, times(2))
            .send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @Test
    void linkedOutputsHandleMissingConfiguration() throws Exception {
        assertNull(deviceConfig.getLinkedOutputs(null));
        final InputConfig inputConfig = new ObjectMapper()
            .readValue("{\"device_key\": \"K1\"}", InputConfig.class);
        stubSend(httpResponse(200, "[]"));
        assertNull(deviceConfig.getLinkedOutputs(inputConfig));
    }

    @Test
    void fetchMeterConfigReturnsConfig() throws Exception {
        stubSend(httpResponse(200, """
            [{"meter_low_limit": 5, "meter_high_limit": 65000}]
            """));
        final MeterConfig config = deviceConfig.fetchMeterConfig("Water Meter");
        assertEquals(Integer.valueOf(5), config.getMeterLowLimit());
        assertEquals(Integer.valueOf(65000), config.getMeterHighLimit());
    }

    @Test
    void haConfigCacheRoundTrip() {
        final HAConfig haConfig = TestStatics.haConfig("Front Door", List.of("device-123", "device-456"));
        deviceConfig.putHaConfig(haConfig);
        final Ring ring = mock(Ring.class);
        when(ring.getDeviceId()).thenReturn("device-456");
        assertSame(haConfig, deviceConfig.getHaConfig(ring));
        final Ring unknown = mock(Ring.class);
        when(unknown.getDeviceId()).thenReturn("device-999");
        assertNull(deviceConfig.getHaConfig(unknown));
    }

    @Test
    void postDeviceInfoPostsJson() throws Exception {
        stubSend(httpResponse(200, "ok"));
        final tailucas.app.device.Device device = new tailucas.app.device.Device();
        deviceConfig.postDeviceInfo(device);
        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        final HttpRequest request = captor.getValue();
        assertEquals("POST", request.method());
        assertTrue(request.uri().toString().startsWith("http://config-host:8080/api/device_info"));
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElse(null));
    }

    @Test
    void postDeviceInfoToleratesHttpErrors() throws Exception {
        stubSend(httpResponse(500, "boom"));
        // logged as a warning, but does not propagate
        deviceConfig.postDeviceInfo(new tailucas.app.device.Device());
        verify(httpClient, times(1))
            .send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @Test
    void closeClosesHttpClient() {
        deviceConfig.close();
        verify(httpClient).close();
    }

    @Test
    void noHttpTrafficForNullLinkedOutputs() throws Exception {
        deviceConfig.getLinkedOutputs(null);
        verify(httpClient, never())
            .send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }
}
