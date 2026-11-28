package tailucas.app.provider;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;

/**
 * The bridge between the request trace and the delivery trace.
 *
 * <p>The picture comes back through the Ring bridge, which carries no W3C context, so
 * the image's span links back to the request instead of inheriting it. That only works
 * if the registration is one-shot and does not outlive its usefulness.
 */
class SnapshotRequestsTest {

    @BeforeEach
    void reset() {
        SnapshotRequests.clear();
    }

    @Test
    void aRememberedRequestIsLinkedOnce() {
        final Context requested = Context.root().with(Span.getInvalid());
        SnapshotRequests.remember("cam-1", requested);
        final Context linked = SnapshotRequests.poll("cam-1");
        assertNotNull(linked, "the first image after a request must link to it");
        // reading consumes it, so a second frame cannot claim the same request
        assertNull(SnapshotRequests.poll("cam-1"));
    }

    @Test
    void anotherDevicesRequestIsNeverLinked() {
        SnapshotRequests.remember("cam-1", Context.root());
        assertNull(SnapshotRequests.poll("cam-2"));
    }

    @Test
    void aBlankKeyIsIgnored() {
        SnapshotRequests.remember(null, Context.root());
        SnapshotRequests.remember("  ", Context.root());
        assertNull(SnapshotRequests.poll(null));
        assertNull(SnapshotRequests.poll("  "));
    }
}
