package tailucas.app.provider;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tailucas.app.device.Ring;
import tailucas.app.device.config.HAConfig;

/**
 * Holds the latest information reported for each Ring device, merged across the
 * topics that carry it: a device spreads availability over {@code status}, entity
 * states over {@code <subject>/state} and health over {@code info/state} plus the
 * {@code battery}/{@code wireless} attribute topics.
 *
 * <p>Merging here means an operator (or the Telegram {@code /ring} command) can ask
 * for a current view of every device instead of replaying the MQTT stream, and a
 * message that carries only one field never erases the rest. Recording happens on
 * the MQTT callback thread, so it is a single map merge with no I/O.
 */
public final class DeviceState {

    /**
     * The newest value reported for each field of one device.
     *
     * @param deviceKey        the Ring device id from the topic
     * @param component        the Ring domain: {@code alarm}, {@code camera} or {@code chime}
     * @param label            the Home Assistant discovery name, e.g. "Front Gate"
     * @param model            manufacturer and model from discovery
     * @param availability     {@code online} or {@code offline} from the availability topic
     * @param deviceReportedAt when the device itself last reported to Ring (ISO instant)
     * @param lastSeen         when the device last published to this application (ISO instant)
     * @param states           last reported state per entity subject (alarm, contact, motion, ...)
     */
    public record DeviceSnapshot(
            String deviceKey,
            String component,
            String label,
            String model,
            String availability,
            Integer batteryLevel,
            Integer batteryLife,
            String batteryStatus,
            Integer wirelessSignal,
            String wirelessNetwork,
            String tamperStatus,
            String commStatus,
            String linkQuality,
            String firmwareStatus,
            String deviceReportedAt,
            String lastSeen,
            Map<String, String> states) { }

    private static final Logger log = LoggerFactory.getLogger(DeviceState.class);

    private final Map<String, DeviceSnapshot> devices = new ConcurrentHashMap<>();

    DeviceState() { }

    private static final class InstanceHolder {
        private static final DeviceState INSTANCE = new DeviceState();
    }

    public static DeviceState getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Merges the information carried by one Ring message into that device's
     * snapshot. Fields the message does not carry keep their previous value.
     */
    public void record(Ring device) {
        final String deviceKey = device == null ? null : device.getDeviceKey();
        if (StringUtils.isBlank(deviceKey)) {
            log.atDebug().setMessage("Cannot record device state, no device key")
                .addKeyValue("topic", device == null ? null : device.getMqttTopic())
                .log();
            return;
        }
        final Instant now = Instant.now();
        devices.compute(deviceKey, (key, previous) -> merge(key, previous, device, now));
    }

    /** Every known device, ordered by label so output is stable between calls. */
    public List<DeviceSnapshot> getDevices() {
        return devices.values().stream()
            .sorted(Comparator.comparing(DeviceSnapshot::label, Comparator.nullsLast(String::compareTo)))
            .toList();
    }

    public int size() {
        return devices.size();
    }

    /** Forgets everything recorded so far. */
    void reset() {
        devices.clear();
    }

    private static DeviceSnapshot merge(String deviceKey, DeviceSnapshot previous, Ring device, Instant now) {
        final DeviceSnapshot base = previous == null ? empty(deviceKey) : previous;
        final Map<String, String> states = new LinkedHashMap<>(base.states());
        // attribute dumps and binary payloads carry no entity state
        if (device.getUpdateSubject() != null
                && device.getState() != null
                && !Ring.BINARY_PAYLOAD_STATE.equals(device.getState())) {
            states.put(device.getUpdateSubject(), device.getState());
        }
        final HAConfig discovered = device.getDiscoveredConfig();
        final String discoveredLabel = discovered == null ? null : discovered.getDevice().getName();
        final String discoveredModel = discovered == null ? null
            : StringUtils.trimToNull(String.format("%s %s",
                discovered.getDevice().getMf(), discovered.getDevice().getMdl()));
        return new DeviceSnapshot(
            deviceKey,
            pick(device.getComponentName(), base.component()),
            pick(discoveredLabel, base.label() != null ? base.label() : deviceKey),
            pick(discoveredModel, base.model()),
            pick("status".equals(device.getUpdateType()) ? device.getState() : null, base.availability()),
            pick(device.getBatteryLevel(), base.batteryLevel()),
            pick(device.getBatteryLife(), base.batteryLife()),
            pick(device.getBatteryStatus(), base.batteryStatus()),
            pick(device.getWirelessSignal(), base.wirelessSignal()),
            pick(device.getWirelessNetwork(), base.wirelessNetwork()),
            pick(device.getTamperStatus(), base.tamperStatus()),
            pick(device.getCommStatus(), base.commStatus()),
            pick(device.getLinkQuality(), base.linkQuality()),
            pick(device.getFirmwareStatus(), base.firmwareStatus()),
            pick(device.getLastUpdate(), base.deviceReportedAt()),
            now.toString(),
            Map.copyOf(states));
    }

    private static DeviceSnapshot empty(String deviceKey) {
        return new DeviceSnapshot(deviceKey, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, Map.of());
    }

    private static <T> T pick(T reported, T previous) {
        return reported != null ? reported : previous;
    }
}
