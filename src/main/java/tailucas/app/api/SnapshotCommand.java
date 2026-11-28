package tailucas.app.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tailucas.app.EventProcessor;
import tailucas.app.provider.DeviceState;

/**
 * Requests an on-demand camera snapshot, on behalf of the Telegram
 * {@code /snapshot} command, which cannot see the MQTT stream itself.
 *
 * <p>The Ring bridge takes a {@code PRESS} on
 * {@code ring/<location>/camera/<device>/take_snapshot/command} and publishes the
 * picture on {@code .../snapshot/image}; that image then arrives back through the
 * normal event pipeline. This endpoint only asks for it — delivery to the chat is
 * the event path's job, which is why the web application opens a delivery window
 * for each accepted camera rather than waiting here.
 *
 * <p>With no {@code device_key} every camera is requested, which is what a bare
 * {@code /snapshot} means. The reply names what was asked for and what was
 * skipped, so the caller can report honestly instead of claiming success for a
 * camera that is offline or was asked a moment ago.
 */
@RestController
public class SnapshotCommand {

    /** The Ring domain of a snapshottable device; alarm and chime devices share {@code /devices}. */
    private static final String CAMERA_COMPONENT = "camera";

    /** ring-mqtt ignores an on-demand snapshot within ten seconds of the previous one. */
    private static final long ON_DEMAND_MIN_INTERVAL_SECONDS = 10;

    private static final String TAKE_SNAPSHOT_TOPIC = "ring/%s/camera/%s/take_snapshot/command";

    /** The bridge's on-demand snapshot trigger; it accepts nothing else. */
    private static final String PRESS = "PRESS";

    /**
     * Last accepted request per device. The bridge rate-limits silently, so without
     * this a second {@code /snapshot} inside ten seconds would look successful and
     * produce nothing.
     */
    private static final Map<String, Long> lastRequests = new ConcurrentHashMap<>();

    @Autowired
    private Logger log;

    @PostMapping("/snapshot")
    public ResponseEntity<Map<String, Object>> snapshot(
            @RequestParam(name = "device_key", required = false) String deviceKey) {
        final List<Map<String, Object>> requested = new ArrayList<>();
        final List<Map<String, Object>> skipped = new ArrayList<>();

        if (StringUtils.isBlank(deviceKey)) {
            for (final DeviceState.DeviceSnapshot device : DeviceState.getInstance().getDevices()) {
                requestSnapshot(device, requested, skipped);
            }
        } else {
            final DeviceState.DeviceSnapshot device = findDevice(deviceKey);
            if (device == null) {
                log.atWarn().setMessage("Snapshot requested for an unknown device")
                    .addKeyValue("device_key", deviceKey)
                    .log();
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", String.format("No such device %s", deviceKey)));
            }
            requestSnapshot(device, requested, skipped);
        }

        log.atInfo().setMessage("Requested camera snapshot")
            .addKeyValue("device_key", StringUtils.defaultString(deviceKey))
            .addKeyValue("requested_count", requested.size())
            .addKeyValue("skipped_count", skipped.size())
            .log();

        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("requested", requested);
        body.put("skipped", skipped);
        final HttpStatus status = requested.isEmpty() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Asks one camera for a snapshot, recording it as requested or explaining why not.
     * A device that is not a camera is skipped rather than rejected: a bare
     * {@code /snapshot} walks every device the processor knows about.
     */
    private void requestSnapshot(
            DeviceState.DeviceSnapshot device,
            List<Map<String, Object>> requested,
            List<Map<String, Object>> skipped) {
        if (!CAMERA_COMPONENT.equalsIgnoreCase(StringUtils.defaultString(device.component()))) {
            skipped.add(skip(device, "not_a_camera", null));
            return;
        }
        if ("offline".equalsIgnoreCase(StringUtils.defaultString(device.availability()))) {
            log.atInfo().setMessage("Not requesting snapshot, camera is offline")
                .addKeyValue("device_key", device.deviceKey())
                .addKeyValue("device_label", device.label())
                .log();
            skipped.add(skip(device, "offline", null));
            return;
        }
        if (StringUtils.isBlank(device.locationId())) {
            log.atWarn().setMessage("Not requesting snapshot, device has no location id")
                .addKeyValue("device_key", device.deviceKey())
                .log();
            skipped.add(skip(device, "unknown_location", null));
            return;
        }
        final long now = Instant.now().getEpochSecond();
        final Long previous = lastRequests.get(device.deviceKey());
        if (previous != null && now - previous < ON_DEMAND_MIN_INTERVAL_SECONDS) {
            final long retryAfter = ON_DEMAND_MIN_INTERVAL_SECONDS - (now - previous);
            log.atInfo().setMessage("Not requesting snapshot, one was requested moments ago")
                .addKeyValue("device_key", device.deviceKey())
                .addKeyValue("retry_after_seconds", retryAfter)
                .log();
            skipped.add(skip(device, "recently_requested", retryAfter));
            return;
        }
        final String topic = String.format(TAKE_SNAPSHOT_TOPIC, device.locationId(), device.deviceKey());
        if (!EventProcessor.publishMqtt(topic, PRESS)) {
            log.atError().setMessage("Cannot request snapshot, the MQTT pipeline did not accept it")
                .addKeyValue("device_key", device.deviceKey())
                .addKeyValue("topic", topic)
                .log();
            skipped.add(skip(device, "publish_failed", null));
            return;
        }
        lastRequests.put(device.deviceKey(), now);
        log.atInfo().setMessage("Requested on-demand snapshot")
            .addKeyValue("device_key", device.deviceKey())
            .addKeyValue("device_label", device.label())
            .addKeyValue("topic", topic)
            .log();
        requested.add(entry(device));
    }

    private static DeviceState.DeviceSnapshot findDevice(String deviceKey) {
        for (final DeviceState.DeviceSnapshot device : DeviceState.getInstance().getDevices()) {
            if (deviceKey.equals(device.deviceKey())) {
                return device;
            }
        }
        return null;
    }

    private static Map<String, Object> entry(DeviceState.DeviceSnapshot device) {
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("device_key", device.deviceKey());
        entry.put("label", device.label());
        return entry;
    }

    private static Map<String, Object> skip(
            DeviceState.DeviceSnapshot device, String reason, Long retryAfterSeconds) {
        final Map<String, Object> entry = entry(device);
        entry.put("reason", reason);
        if (retryAfterSeconds != null) {
            entry.put("retry_after_seconds", retryAfterSeconds);
        }
        return entry;
    }
}
