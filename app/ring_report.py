"""Composition of the Telegram ``/ring`` device report.

Pure functions only: the payload comes from the Java event processor's
``GET /devices`` endpoint and is turned into legacy-Markdown chat text here, so the
formatting can be unit tested without booting the application (see
``tests/test_ring_report.py``). Parse-mode rules live in ``.clinerules/telegram.md``.
"""

from datetime import UTC, datetime

import emoji

#: Telegram rejects messages longer than 4096 characters.
TELEGRAM_MESSAGE_LIMIT = 4000

#: Entity states worth showing, in the order an operator scans them.
STATE_ORDER = (
    "alarm",
    "contact",
    "motion",
    "ding",
    "siren",
    "fire",
    "police",
    "stream",
    "snapshot",
    "volume",
    "snooze",
)

COMPONENT_ICONS = {
    "alarm": ":bell:",
    "camera": ":camera:",
    "chime": ":musical_note:",
}

#: Legacy Markdown reserves these; a device label never intends markup.
MARKDOWN_METACHARACTERS = ("*", "_", "`", "[", "]")


def markdown_safe(value: object) -> str:
    """Neutralise Markdown metacharacters in external data.

    Only data is sanitised, never a composed message: the markup this module adds
    is intentional and must survive.
    """
    if value is None:
        return ""
    text = " ".join(str(value).split())
    for character in MARKDOWN_METACHARACTERS:
        text = text.replace(character, " ")
    return " ".join(text.split())


def format_age(timestamp: str | None, now: datetime | None = None) -> str | None:
    """Render an ISO-8601 instant as a short age, or None when unparseable."""
    if not timestamp:
        return None
    try:
        moment = datetime.fromisoformat(str(timestamp).replace("Z", "+00:00"))
    except ValueError:
        return None
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=UTC)
    reference = now or datetime.now(UTC)
    seconds = int((reference - moment).total_seconds())
    if seconds < 0:
        seconds = 0
    if seconds < 60:
        return "just now"
    if seconds < 5400:
        return f"{seconds // 60}m ago"
    if seconds < 129600:
        return f"{seconds // 3600}h ago"
    return f"{seconds // 86400}d ago"


def split_message(text: str, limit: int = TELEGRAM_MESSAGE_LIMIT) -> list[str]:
    """Split text on line boundaries so each part fits a Telegram message."""
    chunks: list[str] = []
    current: list[str] = []
    size = 0
    for line in text.split("\n"):
        addition = len(line) if not current else len(line) + 1
        if current and size + addition > limit:
            chunks.append("\n".join(current))
            current = []
            size = 0
            addition = len(line)
        current.append(line)
        size += addition
    if current:
        chunks.append("\n".join(current))
    return chunks


def _battery_details(device: dict) -> list[str]:
    """Battery charge, charge state and (when it differs) battery health."""
    level = device.get("batteryLevel")
    status = markdown_safe(device.get("batteryStatus"))
    life = device.get("batteryLife")
    if isinstance(level, int):
        detail = f"battery `{level}%`"
        if status:
            detail = f"{detail} {status}"
        if isinstance(life, int) and life != level:
            detail = f"{detail} (life `{life}%`)"
        return [detail]
    # mains-powered devices report a state without a percentage
    return [f"battery {status}"] if status else []


def _device_icon(device: dict) -> str:
    if str(device.get("availability") or "").lower() == "offline":
        return ":warning:"
    level = device.get("batteryLevel")
    if isinstance(level, int) and level <= 20:
        return ":low_battery:"
    component = str(device.get("component") or "").lower()
    return COMPONENT_ICONS.get(component, ":electric_plug:")


def device_lines(device: dict) -> list[str]:
    """One device as a heading plus a detail line and its entity states."""
    label = markdown_safe(device.get("label") or device.get("deviceKey"))
    model = markdown_safe(device.get("model"))
    heading = f"{emoji.emojize(_device_icon(device))} *{label}*"
    if model:
        heading = f"{heading} — {model}"
    details: list[str] = []
    availability = str(device.get("availability") or "").lower()
    if availability:
        details.append(f"`{markdown_safe(availability)}`")
    details.extend(_battery_details(device))
    signal = device.get("wirelessSignal")
    if isinstance(signal, int):
        details.append(f"signal `{signal}` dBm")
    # only report the flags that are not healthy; 'ok' everywhere is noise
    for key, name in (("tamperStatus", "tamper"), ("commStatus", "comm"), ("linkQuality", "link")):
        value = markdown_safe(device.get(key))
        if value and value.lower() != "ok":
            details.append(f"{name} `{value}`")
    # the device's own last report to Ring is the staleness signal; fall back to
    # when the processor last saw it
    reported = format_age(device.get("deviceReportedAt"))
    if reported:
        details.append(f"reported {reported}")
    else:
        seen = format_age(device.get("lastSeen"))
        if seen:
            details.append(f"seen {seen}")
    states = device.get("states") or {}
    state_text = " · ".join(
        f"`{markdown_safe(subject)}` {markdown_safe(states[subject])}"
        for subject in STATE_ORDER
        if subject in states
    )
    lines = [heading]
    if details:
        lines.append(" · ".join(details))
    if state_text:
        lines.append(state_text)
    return lines


def format_ring_devices(devices: list[dict]) -> str:
    """Compose the ``/ring`` report (legacy Markdown) from the ``/devices`` payload."""
    if not devices:
        return f"{emoji.emojize(':warning:')} No Ring devices have reported yet."
    ordered = sorted(
        devices,
        key=lambda device: markdown_safe(device.get("label") or device.get("deviceKey")).lower(),
    )
    online = sum(1 for device in ordered if str(device.get("availability") or "").lower() == "online")
    lines = [
        f"{emoji.emojize(':bell:')} *Ring devices* — {len(ordered)} known, {online} online",
        "",
    ]
    for index, device in enumerate(ordered):
        if index:
            lines.append("")
        lines.extend(device_lines(device))
    return "\n".join(lines)
