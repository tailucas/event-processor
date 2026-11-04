"""Tests for the Telegram /ring report composition (pure functions)."""

from datetime import UTC, datetime, timedelta

from app.ring_report import format_age, format_ring_devices, markdown_safe, split_message

NOW = datetime(2026, 11, 4, 12, 0, tzinfo=UTC)


def device(**overrides) -> dict:
    base = {
        "deviceKey": "device-1",
        "component": "alarm",
        "label": "Front Door",
        "model": "Ring Contact Sensor",
        "availability": "online",
        "batteryLevel": 100,
        "batteryLife": 100,
        "batteryStatus": "full",
        "wirelessSignal": None,
        "tamperStatus": "ok",
        "commStatus": "ok",
        "linkQuality": "ok",
        "deviceReportedAt": "2026-11-04T10:00:00+00:00",
        "lastSeen": "2026-11-04T11:59:30+00:00",
        "states": {"contact": "OFF"},
    }
    base.update(overrides)
    return base


def test_empty_device_list_reports_no_devices():
    report = format_ring_devices([])
    assert "No Ring devices have reported yet" in report


def test_header_counts_known_and_online_devices():
    report = format_ring_devices([device(), device(deviceKey="device-2", availability="offline")])
    assert "*Ring devices* — 2 known, 1 online" in report


def test_device_block_contains_battery_signal_and_state():
    report = format_ring_devices(
        [device(label="Front Gate", wirelessSignal=-67, states={"contact": "ON"})]
    )
    assert "*Front Gate* — Ring Contact Sensor" in report
    assert "`online`" in report
    assert "battery `100%` full" in report
    assert "signal `-67` dBm" in report
    assert "`contact` ON" in report


def test_battery_health_is_shown_only_when_it_differs_from_charge():
    # charge 58% against health 100% is the dual-battery case ring-mqtt reports
    assert "(life `100%`)" in format_ring_devices([device(batteryLevel=58, batteryLife=100)])
    assert "(life" not in format_ring_devices([device(batteryLevel=100, batteryLife=100)])


def test_mains_device_reports_status_without_percentage():
    report = format_ring_devices(
        [device(batteryLevel=None, batteryLife=None, batteryStatus="charged", states={})]
    )
    assert "battery charged" in report


def test_unhealthy_flags_are_surfaced_and_ok_flags_are_not():
    healthy = format_ring_devices([device()])
    assert "tamper" not in healthy
    assert "comm" not in healthy
    assert "link" not in healthy
    faulty = format_ring_devices([device(tamperStatus="tamper", commStatus="error", linkQuality="poor")])
    assert "tamper `tamper`" in faulty
    assert "comm `error`" in faulty
    assert "link `poor`" in faulty


def test_offline_device_is_flagged():
    report = format_ring_devices([device(availability="offline")])
    assert "⚠️" in report
    assert "`offline`" in report


def test_low_battery_device_is_flagged():
    report = format_ring_devices([device(batteryLevel=15)])
    assert "🪫" in report


def test_component_icons_distinguish_camera_and_chime():
    report = format_ring_devices(
        [device(component="camera", label="Front Gate"), device(component="chime", label="House")]
    )
    assert "📷 *Front Gate*" in report
    assert "🎵 *House*" in report


def test_devices_are_sorted_by_label_regardless_of_payload_order():
    report = format_ring_devices(
        [device(label="Zebra"), device(label="Alpha"), device(label="Middle")]
    )
    assert report.index("Alpha") < report.index("Middle") < report.index("Zebra")


def test_label_falls_back_to_the_device_key():
    report = format_ring_devices([device(label=None, deviceKey="abcd1234")])
    assert "*abcd1234*" in report


def test_markdown_metacharacters_in_labels_are_neutralised():
    report = format_ring_devices([device(label="Front_Door*[1]`")])
    assert "*Front Door 1*" in report
    assert "`[1]`" not in report


def test_states_are_ordered_and_unknown_subjects_are_skipped():
    report = format_ring_devices(
        [device(states={"siren": "OFF", "bypass_mode": "Never", "contact": "OFF", "alarm": "disarmed"})]
    )
    assert "`alarm` disarmed · `contact` OFF · `siren` OFF" in report
    assert "bypass_mode" not in report


def test_device_blocks_are_separated_by_a_blank_line():
    report = format_ring_devices([device(label="Alpha"), device(label="Beta")])
    assert "`contact` OFF\n\n🔔 *Beta*" in report


def test_report_fits_one_message_for_a_typical_installation():
    devices = [device(deviceKey=f"device-{index}", label=f"Device {index}") for index in range(20)]
    assert len(split_message(format_ring_devices(devices))) == 1


def test_split_message_breaks_on_line_boundaries():
    chunks = split_message("a\nb\nc\nd", limit=3)
    assert chunks == ["a\nb", "c\nd"]


def test_format_age_buckets():
    assert format_age(None) is None
    assert format_age("not-a-timestamp") is None
    assert format_age("2026-11-04T12:00:00+00:00", now=NOW) == "just now"
    assert format_age("2026-11-04T11:58:00+00:00", now=NOW) == "2m ago"
    assert format_age("2026-11-04T09:00:00+00:00", now=NOW) == "3h ago"
    assert format_age("2026-11-01T09:00:00+00:00", now=NOW) == "3d ago"


def test_format_age_accepts_zulu_suffix():
    stamp = (NOW - timedelta(minutes=5)).isoformat().replace("+00:00", "Z")
    assert format_age(stamp, now=NOW) == "5m ago"


def test_markdown_safe_collapses_whitespace():
    assert markdown_safe("  Front   Door  ") == "Front Door"
    assert markdown_safe(None) == ""
