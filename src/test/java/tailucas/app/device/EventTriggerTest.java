package tailucas.app.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.msgpack.jackson.dataformat.MessagePackMapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import tailucas.app.TestStatics;
import tailucas.app.device.config.OutputConfig;

/**
 * The wire format of an operator-requested camera trigger.
 *
 * <p>It must match what the link path emits, because the consumer is the same. The
 * timestamp unit is the sharp edge: the snapshot processor converts it with
 * {@code fromtimestamp}, and milliseconds put the year around 58878 — a ValueError
 * that took the service down when this was first tried.
 */
class EventTriggerTest {

    private static OutputConfig cameraOutput() {
        final OutputConfig config = new OutputConfig();
        TestStatics.setField(config, "deviceKey", "Pool Camera");
        TestStatics.setField(config, "deviceType", "Camera");
        TestStatics.setField(config, "deviceLabel", "Pool Camera");
        TestStatics.setField(config, "triggerTopic", "event.trigger.snapshot");
        TestStatics.setField(config, "device_enabled", Boolean.TRUE);
        return config;
    }

    @Test
    void aRequestedTriggerCarriesEpochSecondsAndTheOutputDevice() throws Exception {
        Event.configure("home_automation_control", "60000");
        final Connection connection = mock(Connection.class);
        final Channel channel = mock(Channel.class);
        when(connection.createChannel()).thenReturn(channel);

        assertTrue(Event.publishRequestedTrigger(connection, cameraOutput(), "Telegram /snapshot"));

        final ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(channel).basicPublish(
            eq("home_automation_control"), eq("event.trigger.snapshot"), any(), body.capture());

        final JsonNode root = new MessagePackMapper().readTree(body.getValue());
        final long timestamp = root.get("timestamp").asLong();
        assertTrue(timestamp < 10_000_000_000L, "timestamp must be epoch seconds, not milliseconds");
        assertTrue(timestamp > 1_600_000_000L, "timestamp must be a plausible epoch time");
        assertEquals("Pool Camera", root.get("output_triggered").get("device_key").asText());
        assertEquals("Telegram /snapshot", root.get("active_input").get("device_label").asText());
        // the W3C carrier is injected from the live SDK, which a unit test does not run;
        // it is verified against the deployed service instead
    }

    @Test
    void anUnconfiguredPipelineRefusesTheTrigger() {
        Event.configure(null, "60000");
        assertFalse(Event.publishRequestedTrigger(null, cameraOutput(), "Telegram /snapshot"));
    }
}
