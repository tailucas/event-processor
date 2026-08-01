package tailucas.app.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Stack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tailucas.app.TestStatics;

class TriggerHistoryTest {

    private static final String KEY = "Test Device";

    private TriggerHistory history;

    @BeforeEach
    void setUp() {
        history = new TriggerHistory();
    }

    @Test
    void unknownDeviceHasNoHistory() {
        assertNull(history.lastTriggered(KEY));
        assertNull(history.secondsSinceLastTriggered(KEY));
        assertNull(history.getTriggeredDuration(KEY));
        assertFalse(history.isTriggeredFor(KEY, 0));
        assertFalse(history.triggeredWithin(KEY, 60));
        assertFalse(history.isMultiTriggered(KEY, 1, 60));
    }

    @Test
    void triggeredRecordsEvent() {
        history.triggered(KEY);
        assertNotNull(history.lastTriggered(KEY));
        final Long seconds = history.secondsSinceLastTriggered(KEY);
        assertNotNull(seconds);
        assertTrue(seconds >= 0 && seconds <= 5);
        assertNotNull(history.getTriggeredDuration(KEY));
        assertTrue(history.isTriggeredFor(KEY, 0));
        assertTrue(history.triggeredWithin(KEY, 60));
    }

    @Test
    void unTriggeredResetsDurationButKeepsHistory() {
        history.triggered(KEY);
        history.unTriggered(KEY);
        assertNull(history.getTriggeredDuration(KEY));
        assertFalse(history.isTriggeredFor(KEY, 0));
        // the trigger event itself remains in the history
        assertNotNull(history.lastTriggered(KEY));
        assertTrue(history.triggeredWithin(KEY, 60));
    }

    @Test
    void secondsSinceLastTriggeredReflectsPastEvent() {
        TestStatics.injectTriggerAt(history, KEY, Instant.now().minusSeconds(3600));
        final Long seconds = history.secondsSinceLastTriggered(KEY);
        assertNotNull(seconds);
        assertTrue(seconds >= 3595 && seconds <= 3600);
    }

    @Test
    void isTriggeredForComparesAgainstWindow() {
        TestStatics.injectTriggerAt(history, KEY, Instant.now().minusSeconds(10));
        assertTrue(history.isTriggeredFor(KEY, 5));
        assertTrue(history.isTriggeredFor(KEY, 10));
        assertFalse(history.isTriggeredFor(KEY, 60));
    }

    @Test
    void triggeredWithinWindow() {
        TestStatics.injectTriggerAt(history, KEY, Instant.now().minusSeconds(10));
        assertTrue(history.triggeredWithin(KEY, 60));
        assertFalse(history.triggeredWithin(KEY, 5));
    }

    @Test
    void multiTriggerCountsEventsWithinWindow() {
        final Instant now = Instant.now();
        TestStatics.injectTriggerAt(history, KEY, now.minusSeconds(30));
        TestStatics.injectTriggerAt(history, KEY, now.minusSeconds(20));
        TestStatics.injectTriggerAt(history, KEY, now.minusSeconds(10));
        assertTrue(history.isMultiTriggered(KEY, 1, 15));
        assertFalse(history.isMultiTriggered(KEY, 2, 15));
        assertFalse(history.isMultiTriggered(KEY, 3, 15));
        assertTrue(history.isMultiTriggered(KEY, 2, 25));
        assertTrue(history.isMultiTriggered(KEY, 3, 60));
    }

    @Test
    void multiTriggerRequiresSufficientHistory() {
        history.triggered(KEY);
        assertFalse(history.isMultiTriggered(KEY, 2, 60));
    }

    @Test
    void multiTriggerRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> history.isMultiTriggered(KEY, 0, 60));
        assertThrows(IllegalArgumentException.class, () -> history.isMultiTriggered(KEY, -1, 60));
        assertThrows(IllegalArgumentException.class, () -> history.isMultiTriggered(KEY, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> history.isMultiTriggered(KEY, 1, -5));
        assertThrows(IllegalArgumentException.class, () -> history.isMultiTriggered(KEY, 121, 60));
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> history.isMultiTriggered(KEY, 0, 0));
        assertTrue(ex.getMessage().contains("0"));
    }

    @Test
    void historyIsTrimmedAtMaximumSize() {
        for (int i = 0; i < 125; i++) {
            history.triggered(KEY);
        }
        @SuppressWarnings("unchecked")
        final Map<String, Stack<Instant>> stored =
            (Map<String, Stack<Instant>>) TestStatics.getField(history, "triggerHistory");
        assertEquals(120, stored.get(KEY).size());
        // the most recent event is still recorded
        assertNotNull(history.lastTriggered(KEY));
        assertTrue(history.isMultiTriggered(KEY, 120, 60));
    }
}
