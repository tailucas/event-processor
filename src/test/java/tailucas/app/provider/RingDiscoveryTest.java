package tailucas.app.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class RingDiscoveryTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(250);
    private static final String STATUS_TOPIC = "ring/location/alarm/device-123/status";
    private static final String MOTION_TOPIC = "ring/location/alarm/device-123/motion/state";

    @Test
    void staysUnseenWhileTheBridgeIsSilent() throws InterruptedException {
        final RingDiscovery ringDiscovery = new RingDiscovery();
        assertFalse(ringDiscovery.isInformationSeen());
        final Instant start = Instant.now();
        assertFalse(ringDiscovery.awaitInformation(SHORT_TIMEOUT));
        assertTrue(Duration.between(start, Instant.now()).toMillis() >= SHORT_TIMEOUT.toMillis());
        assertNull(ringDiscovery.getInformationTopic());
    }

    @Test
    void seesInformationThatArrivedBeforeTheWait() throws InterruptedException {
        final RingDiscovery ringDiscovery = new RingDiscovery();
        ringDiscovery.markDiscoveryRequested();
        ringDiscovery.markRingInformation(STATUS_TOPIC, false);
        assertTrue(ringDiscovery.isInformationSeen());
        assertTrue(ringDiscovery.awaitInformation(SHORT_TIMEOUT));
        assertEquals(STATUS_TOPIC, ringDiscovery.getInformationTopic());
        assertNotNull(ringDiscovery.getDiscoveryRequestedAt());
    }

    @Test
    void wakesTheWaitingThread() throws InterruptedException {
        final RingDiscovery ringDiscovery = new RingDiscovery();
        ringDiscovery.markDiscoveryRequested();
        final Thread producer = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ringDiscovery.markRingInformation(STATUS_TOPIC, false);
        });
        assertTrue(ringDiscovery.awaitInformation(Duration.ofSeconds(5)));
        producer.join();
    }

    @Test
    void keepsTheFirstTopicAcrossRepeatedMessages() {
        final RingDiscovery ringDiscovery = new RingDiscovery();
        ringDiscovery.markRingInformation(STATUS_TOPIC, false);
        ringDiscovery.markRingInformation(MOTION_TOPIC, false);
        assertEquals(STATUS_TOPIC, ringDiscovery.getInformationTopic());
    }

    @Test
    void retainedReplayAloneIsReportedAsRetainedOnly() {
        // a retained message only proves the broker remembers the bridge published
        // at some point, not that it is publishing now
        final RingDiscovery ringDiscovery = new RingDiscovery();
        ringDiscovery.markRingInformation(STATUS_TOPIC, true);
        assertTrue(ringDiscovery.isInformationSeen());
        assertTrue(ringDiscovery.isRetainedInformationOnly());
    }

    @Test
    void liveMessageClearsRetainedOnly() {
        final RingDiscovery ringDiscovery = new RingDiscovery();
        ringDiscovery.markRingInformation(STATUS_TOPIC, true);
        ringDiscovery.markRingInformation(MOTION_TOPIC, false);
        assertFalse(ringDiscovery.isRetainedInformationOnly());
    }

    @Test
    void discoveryRequestIsRecordedOnce() {
        final RingDiscovery ringDiscovery = new RingDiscovery();
        assertNull(ringDiscovery.getDiscoveryRequestedAt());
        ringDiscovery.markDiscoveryRequested();
        final Instant requestedAt = ringDiscovery.getDiscoveryRequestedAt();
        ringDiscovery.markDiscoveryRequested();
        assertEquals(requestedAt, ringDiscovery.getDiscoveryRequestedAt());
    }

    @Test
    void singletonIsShared() {
        assertSame(RingDiscovery.getInstance(), RingDiscovery.getInstance());
    }
}
