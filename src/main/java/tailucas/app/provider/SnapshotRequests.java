package tailucas.app.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.opentelemetry.context.Context;

/**
 * Remembers which trace asked a camera for a snapshot, so the picture that arrives
 * later can be <em>linked</em> to the request that caused it.
 *
 * <p>The image cannot inherit the request's trace: it comes back through the Ring
 * bridge, which carries no W3C context, so it legitimately starts a trace of its
 * own. A span link is the correct OpenTelemetry idiom for "caused by" — it records
 * the causal relationship without pretending the two spans are one trace.
 *
 * <p>Entries are one-shot and expire, so a snapshot the bridge never produced
 * cannot attach itself as the link on some later, unrelated image.
 */
public final class SnapshotRequests {

    /** How long a request stays eligible to link an image. */
    private static final Duration LINK_TTL = Duration.ofMinutes(2);

    private record Pending(Context parent, Instant requestedAt) { }

    private static final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private SnapshotRequests() { }

    /** Records that {@code parent} asked this device for a snapshot. */
    public static void remember(String deviceKey, Context parent) {
        if (deviceKey == null || deviceKey.isBlank() || parent == null) {
            return;
        }
        pending.put(deviceKey, new Pending(parent, Instant.now()));
    }

    /**
     * Takes the request context recorded for one device.
     *
     * @return the context to link to, or {@code null} when none was recorded or it has
     *     expired. Reading removes it, so a camera that publishes several images links
     *     only the first to the request.
     */
    public static Context poll(String deviceKey) {
        if (deviceKey == null || deviceKey.isBlank()) {
            return null;
        }
        final Pending entry = pending.remove(deviceKey);
        if (entry == null) {
            return null;
        }
        if (Duration.between(entry.requestedAt(), Instant.now()).compareTo(LINK_TTL) > 0) {
            return null;
        }
        return entry.parent();
    }

    /** Forgets every recorded request; tests only. */
    static void clear() {
        pending.clear();
    }
}
