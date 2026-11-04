package tailucas.app.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks whether Ring device information has arrived over MQTT since the
 * processor asked for it (subscription plus the Home Assistant discovery
 * message).
 *
 * <p>A Ring bridge that cannot reach the Ring API publishes nothing at all, so
 * silence is the only symptom it produces: no Ring events, no Ring logs and no
 * Ring metrics. This class records that first message so startup can distinguish
 * "the bridge is quiet right now" from "the bridge is not there at all".
 *
 * <p>Only the first message is logged; the MQTT callback thread must stay cheap,
 * so the alerting and incident handling lives with the caller (see
 * {@code EventProcessor.awaitRingDeviceInformation}).
 */
public final class RingDiscovery {

    private static final Logger log = LoggerFactory.getLogger(RingDiscovery.class);

    private final CountDownLatch discoveryRequested = new CountDownLatch(1);
    private final CountDownLatch informationSeen = new CountDownLatch(1);
    private volatile boolean liveInformationSeen = false;
    private volatile Instant discoveryRequestedAt = null;
    private volatile Instant informationSeenAt = null;
    private volatile String informationTopic = null;

    RingDiscovery() { }

    private static final class InstanceHolder {
        private static final RingDiscovery INSTANCE = new RingDiscovery();
    }

    public static RingDiscovery getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Records that the MQTT subscription and (when the feature flag is enabled)
     * the Home Assistant discovery message have been sent, which is the point at
     * which the Ring bridge has been asked for its device information.
     */
    public void markDiscoveryRequested() {
        if (discoveryRequested.getCount() > 0) {
            discoveryRequestedAt = Instant.now();
            log.atInfo().setMessage("Awaiting Ring device information")
                .addKeyValue("information_already_seen", isInformationSeen())
                .log();
        }
        discoveryRequested.countDown();
    }

    /**
     * Records an inbound Ring MQTT message.
     *
     * @param topic    the Ring topic the message arrived on
     * @param retained true when the broker replayed a retained message instead of
     *                 a live publish, which means the bridge itself may be silent
     */
    public void markRingInformation(String topic, boolean retained) {
        if (!retained) {
            liveInformationSeen = true;
        }
        if (informationSeen.getCount() > 0) {
            informationSeenAt = Instant.now();
            informationTopic = topic;
            // -1 means information arrived before discovery was requested
            final long secondsSinceDiscoveryRequest = discoveryRequestedAt == null
                ? -1
                : Duration.between(discoveryRequestedAt, informationSeenAt).toSeconds();
            log.atInfo().setMessage("Ring device information received")
                .addKeyValue("topic", topic)
                .addKeyValue("retained", retained)
                .addKeyValue("seconds_since_discovery_request", secondsSinceDiscoveryRequest)
                .log();
        }
        informationSeen.countDown();
    }

    /**
     * Waits for the first Ring message, bounded by the timeout.
     *
     * @return true when Ring device information has arrived, including when it
     *         arrived before the wait started
     */
    public boolean awaitInformation(Duration timeout) throws InterruptedException {
        return informationSeen.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public boolean isInformationSeen() {
        return informationSeen.getCount() == 0;
    }

    public boolean isRetainedInformationOnly() {
        return isInformationSeen() && !liveInformationSeen;
    }

    public String getInformationTopic() {
        return informationTopic;
    }

    public Instant getDiscoveryRequestedAt() {
        return discoveryRequestedAt;
    }
}
