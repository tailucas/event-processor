package tailucas.app.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.fasterxml.jackson.databind.ObjectMapper;

import tailucas.app.device.config.MeterConfig;
import tailucas.app.provider.DeviceConfig;

class MeterTest {

    private static final String DEVICE_KEY = "Water Meter";

    private MockedStatic<DeviceConfig> deviceConfigStatic;
    private DeviceConfig deviceConfig;
    private Meter meter;

    @BeforeEach
    void setUp() {
        deviceConfig = mock(DeviceConfig.class);
        deviceConfigStatic = mockStatic(DeviceConfig.class);
        deviceConfigStatic.when(DeviceConfig::getInstance).thenReturn(deviceConfig);
        meter = new Meter();
        meter.deviceKey = DEVICE_KEY;
        meter.registerReading = Integer.valueOf(50);
    }

    @AfterEach
    void tearDown() {
        deviceConfigStatic.close();
    }

    private static MeterConfig meterConfig(String json) throws Exception {
        return new ObjectMapper().readValue(json, MeterConfig.class);
    }

    @Test
    void belowLowLimitTriggers() throws Exception {
        when(deviceConfig.fetchMeterConfig(DEVICE_KEY))
            .thenReturn(meterConfig("{\"meter_low_limit\": 100}"));
        assertTrue(meter.wouldTriggerOutput(null));
        assertEquals("register value 50 is below the configured limit of 100",
            meter.getTriggerStateDescription());
    }

    @Test
    void aboveHighLimitTriggers() throws Exception {
        when(deviceConfig.fetchMeterConfig(DEVICE_KEY))
            .thenReturn(meterConfig("{\"meter_high_limit\": 40}"));
        assertTrue(meter.wouldTriggerOutput(null));
        assertEquals("register value 50 is above the configured limit of 40",
            meter.getTriggerStateDescription());
    }

    @Test
    void withinLimitsDoesNotTrigger() throws Exception {
        when(deviceConfig.fetchMeterConfig(DEVICE_KEY))
            .thenReturn(meterConfig("{\"meter_low_limit\": 10, \"meter_high_limit\": 100}"));
        assertFalse(meter.wouldTriggerOutput(null));
    }

    @Test
    void noLimitsDoesNotTrigger() throws Exception {
        when(deviceConfig.fetchMeterConfig(DEVICE_KEY)).thenReturn(meterConfig("{}"));
        assertFalse(meter.wouldTriggerOutput(null));
    }

    @Test
    void configFetchFailureDoesNotTrigger() throws Exception {
        when(deviceConfig.fetchMeterConfig(DEVICE_KEY)).thenThrow(new IOException("boom"));
        assertFalse(meter.wouldTriggerOutput(null));
    }

    @Test
    void interruptionRestoresInterruptFlag() throws Exception {
        when(deviceConfig.fetchMeterConfig(DEVICE_KEY)).thenThrow(new InterruptedException("stop"));
        assertFalse(meter.wouldTriggerOutput(null));
        // also clears the thread flag so that other tests are unaffected
        assertTrue(Thread.interrupted());
    }

    @Test
    void deviceCharacteristics() {
        assertEquals("meter", meter.getDeviceType());
        assertTrue(meter.toString().contains("registerReading=50"));
    }
}
