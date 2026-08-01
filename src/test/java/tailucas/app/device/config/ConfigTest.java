package tailucas.app.device.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import tailucas.app.device.config.Config.ConfigType;

class ConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void configClassMappings() {
        assertEquals(InputConfig.class, Config.getConfigClass(ConfigType.INPUT_CONFIG));
        assertEquals(OutputConfig.class, Config.getConfigClass(ConfigType.OUTPUT_CONFIG));
        assertEquals(MeterConfig.class, Config.getConfigClass(ConfigType.METER_CONFIG));
        assertEquals(HAConfig.class, Config.getConfigClass(ConfigType.HA_CONFIG));
        assertEquals(OutputConfig.class, Config.getConfigClass(ConfigType.OUTPUT_LINK));
    }

    @Test
    void inputConfigDeserialization() throws Exception {
        final String json = """
            {
              "device_key": "Kitchen Smoke",
              "device_type": "detector",
              "device_label": "Kitchen Smoke",
              "customized": true,
              "device_enabled": true,
              "trigger_latch_duration": 300,
              "multi_trigger_rate": 3,
              "multi_trigger_interval": 60,
              "activation_escalation": 900,
              "group_name": "detectors",
              "info_notify": true
            }
            """;
        final InputConfig config = mapper.readValue(json, InputConfig.class);
        assertEquals("Kitchen Smoke", config.getDeviceKey());
        assertEquals("detector", config.getDeviceType());
        assertEquals("Kitchen Smoke", config.getDeviceLabel());
        assertTrue(config.isCustomized());
        assertTrue(config.isDeviceEnabled());
        assertEquals(Integer.valueOf(300), config.getTriggerLatchDuration());
        assertEquals(Integer.valueOf(3), config.getMultiTriggerRate());
        assertEquals(Integer.valueOf(60), config.getMultiTriggerInterval());
        assertEquals(Integer.valueOf(900), config.getActivationEscalation());
        assertEquals("detectors", config.getGroupName());
        assertTrue(config.isInfoNotify());
        assertTrue(config.toString().contains("Kitchen Smoke"));
    }

    @Test
    void inputConfigNullSafety() {
        final InputConfig config = new InputConfig();
        assertThrows(IllegalStateException.class, config::getDeviceKey);
        assertFalse(config.isCustomized());
        assertFalse(config.isDeviceEnabled());
        assertFalse(config.isInfoNotify());
        assertNull(config.getTriggerLatchDuration());
        assertNull(config.getMultiTriggerRate());
        assertNull(config.getMultiTriggerInterval());
        assertNull(config.getActivationEscalation());
    }

    @Test
    void outputConfigDeserialization() throws Exception {
        final String json = """
            {
              "device_key": "siren-1",
              "device_type": "siren",
              "device_label": "Siren",
              "device_params": "duration=10",
              "trigger_topic": "event.trigger.siren",
              "trigger_interval": 60,
              "device_enabled": true
            }
            """;
        final OutputConfig config = mapper.readValue(json, OutputConfig.class);
        assertEquals("siren-1", config.getDeviceKey());
        assertEquals("siren", config.getDeviceType());
        assertEquals("Siren", config.getDeviceLabel());
        assertEquals("duration=10", config.getDeviceParams());
        assertEquals("event.trigger.siren", config.getTriggerTopic());
        assertEquals(Integer.valueOf(60), config.getTriggerInterval());
        assertTrue(config.isDeviceEnabled());
        assertTrue(config.toString().contains("siren-1"));
    }

    @Test
    void outputConfigLabelFallsBackToKey() throws Exception {
        assertNull(new OutputConfig().getDeviceLabel());
        final OutputConfig config = mapper.readValue("{\"device_key\": \"siren-1\"}", OutputConfig.class);
        assertEquals("siren-1", config.getDeviceLabel());
        assertFalse(config.isDeviceEnabled());
    }

    @Test
    void meterConfigDeserialization() throws Exception {
        final String json = """
            {
              "meter_value": 12,
              "register_value": 12345,
              "meter_reading": "00123.45",
              "meter_iot_topic": "meter/utility/reading",
              "meter_low_limit": 10,
              "meter_high_limit": 65000,
              "meter_reset_value": 0,
              "meter_reset_additive": true,
              "meter_reading_unit": "kL",
              "meter_reading_unit_factor": 1000,
              "meter_reading_unit_precision": 2
            }
            """;
        final MeterConfig config = mapper.readValue(json, MeterConfig.class);
        assertEquals(Integer.valueOf(12), config.getMeterValue());
        assertEquals(Integer.valueOf(12345), config.getRegisterValue());
        assertEquals("00123.45", config.getMeterReading());
        assertEquals("meter/utility/reading", config.getMeterIotTopic());
        assertEquals(Integer.valueOf(10), config.getMeterLowLimit());
        assertEquals(Integer.valueOf(65000), config.getMeterHighLimit());
        assertEquals(Integer.valueOf(0), config.getMeterResetValue());
        assertEquals(Boolean.TRUE, config.getMeterResetAdditive());
        assertEquals("kL", config.getMeterReadingUnit());
        assertEquals(Integer.valueOf(1000), config.getMeterReadingUnitFactor());
        assertEquals(Integer.valueOf(2), config.getMeterReadingUnitPrecision());
        assertTrue(config.toString().contains("00123.45"));
    }

    @Test
    void haConfigDeserialization() throws Exception {
        // NOTE: the nested "device" object is excluded here on purpose:
        // HADevice is a non-static inner class which Jackson cannot instantiate.
        final String json = """
            {
              "name": "Kitchen Motion",
              "unique_id": "abc-123",
              "state_topic": "ring/component/alarm/device-123/motion/state",
              "device_class": "motion",
              "command_topic": "ring/component/alarm/device-123/command",
              "options": ["a", "b"],
              "unit_of_measurement": "C"
            }
            """;
        final HAConfig config = mapper.readValue(json, HAConfig.class);
        assertEquals("Kitchen Motion", config.getName());
        assertEquals("abc-123", config.getUniqueId());
        assertEquals("ring/component/alarm/device-123/motion/state", config.getStateTopic());
        assertEquals("motion", config.getDeviceClass());
        assertEquals("ring/component/alarm/device-123/command", config.getCommandTopic());
        assertEquals(List.of("a", "b"), config.getOptions());
        assertEquals("C", config.getUnitOfMeasurement());
        assertNull(config.getDevice());
        assertThrows(UnsupportedOperationException.class, () -> config.getOptions().add("c"));
        assertTrue(config.toString().contains("Kitchen Motion"));
    }
}
