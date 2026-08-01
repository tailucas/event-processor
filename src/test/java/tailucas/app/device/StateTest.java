package tailucas.app.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class StateTest {

    @Test
    void timestampParsesIsoFormat() {
        final State state = new State();
        state.timestamp = "2024-01-15T10:30:45.123456+0000";
        assertEquals(Instant.parse("2024-01-15T10:30:45.123456Z"), state.getTimestamp());
    }

    @Test
    void invalidTimestampFallsBackToNow() {
        final State state = new State();
        state.timestamp = "not-a-timestamp";
        final Instant before = Instant.now().minusSeconds(5);
        assertTrue(state.getTimestamp().isAfter(before));
    }

    @Test
    void missingTimestampFallsBackToNow() {
        assertNotNull(new State().getTimestamp());
    }

    @Test
    void explicitCreatedTimeWins() {
        final State state = new State();
        final Instant created = Instant.ofEpochSecond(1_600_000_000L);
        state.createdTime = created;
        state.timestamp = "2024-01-15T10:30:45.123456+0000";
        assertSame(created, state.getTimestamp());
    }

    @Test
    void inputsAreCopiedOnConstruction() {
        final List<Device> inputs = new ArrayList<>();
        inputs.add(new Device());
        final State state = new State(inputs);
        inputs.add(new Device());
        assertEquals(1, state.getInputs().size());
        assertThrows(UnsupportedOperationException.class, () -> state.getInputs().add(new Device()));
    }

    @Test
    void nullInputsAndOutputs() {
        final State state = new State();
        assertNull(state.getInputs());
        assertNull(state.getOutputs());
    }

    @Test
    void testToString() {
        final State state = new State(List.of(new Device()));
        assertTrue(state.toString().contains("inputs="));
    }
}
