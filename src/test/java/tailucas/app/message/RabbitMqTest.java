package tailucas.app.message;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.msgpack.jackson.dataformat.MessagePackMapper;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;

import tailucas.app.TestStatics;
import tailucas.app.device.Event;

class RabbitMqTest {

    private ExecutorService srv;
    private Connection connection;
    private RabbitMq rabbitMq;
    private MessagePackMapper mapper;

    @BeforeAll
    static void initStatics() {
        TestStatics.configureTestMetrics();
    }

    @BeforeEach
    void setUp() {
        srv = mock(ExecutorService.class);
        connection = mock(Connection.class);
        rabbitMq = new RabbitMq(srv, connection);
        mapper = new MessagePackMapper();
    }

    private Delivery delivery(byte[] body) {
        final Delivery delivery = mock(Delivery.class);
        when(delivery.getEnvelope())
            .thenReturn(new Envelope(1L, false, "home_automation", "source.routing.key"));
        when(delivery.getBody()).thenReturn(body);
        return delivery;
    }

    /**
     * Builds the update payload as maps of wire fields, mirroring the producing
     * side: serializing actual Device instances would emit derived read-only
     * properties (is_input, is_output) that the consumer rightly rejects.
     */
    private static Map<String, Object> devicePayload(String deviceLabel, String deviceType) {
        final Map<String, Object> device = new HashMap<>();
        device.put("device_label", deviceLabel);
        device.put("device_type", deviceType);
        device.put("timestamp", 1_700_000_000L);
        device.put("uptime", 60);
        return device;
    }

    private byte[] statePayload(List<Map<String, Object>> inputs) throws Exception {
        if (inputs == null) {
            return mapper.writeValueAsBytes(Map.of());
        }
        return mapper.writeValueAsBytes(Map.of("inputs", inputs));
    }

    @Test
    void submitsOneEventPerInput() throws Exception {
        rabbitMq.handle("consumer", delivery(statePayload(List.of(
            devicePayload("Kitchen Smoke", "contact"),
            devicePayload("Porch Camera", "camera"),
            devicePayload("Hall Motion", "motion_detector")))));
        final ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(srv, times(3)).execute(captor.capture());
        assertEquals("source.routing.key", TestStatics.getField(captor.getValue(), "source"));
    }

    @Test
    void missingInputsAreSkipped() throws Exception {
        rabbitMq.handle("consumer", delivery(statePayload(null)));
        verifyNoInteractions(srv);
    }

    @Test
    void corruptPayloadIsContained() throws Exception {
        assertDoesNotThrow(() -> rabbitMq.handle("consumer", delivery(new byte[] {1, 2, 3})));
        verifyNoInteractions(srv);
    }

    @Test
    void singleInputSubmitsSingleEvent() throws Exception {
        rabbitMq.handle("consumer", delivery(statePayload(List.of(devicePayload("Kitchen Smoke", "contact")))));
        verify(srv, times(1)).execute(any(Event.class));
    }

    @Test
    void bodyTraceparentIsPropagatedToEvent() throws Exception {
        final String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        final Map<String, Object> payload = new HashMap<>();
        payload.put("traceparent", traceparent);
        payload.put("inputs", List.of(devicePayload("Kitchen Smoke", "contact")));
        final byte[] body = mapper.writeValueAsBytes(payload);

        rabbitMq.handle("consumer", delivery(body));

        final ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(srv, times(1)).execute(captor.capture());
        assertEquals(traceparent, TestStatics.getField(captor.getValue(), "traceparent"));
    }
}
