"""Pure helpers for the Telegram ``/snapshot`` command.

Resolving which camera an operator named, and describing what happened, are pure
functions so they are unit tested without booting the application (see
``tests/test_snapshot_command.py``). The transport — the Java event processor's
``POST /snapshot`` — and the Telegram plumbing live in ``app/__main__.py``.

Only Ring devices are selectable today: ``GET /devices`` reports them with
``component == "camera"``. Widening this to every camera type is a change to
:func:`is_camera` alone; the command, the delivery window
(:mod:`app.snapshot_window`) and the image filter are already camera-agnostic.
"""

import emoji

from app.ring_report import markdown_safe

#: The Ring domain a camera reports on ``<device>/status`` and the discovery
#: topics. Chimes and alarm devices share the endpoint and are not snapshottable.
CAMERA_COMPONENT = "camera"

WARNING_ICON = emoji.emojize(":warning:")
CAMERA_ICON = emoji.emojize(":camera:")


def _text(value: object) -> str:
    return value.strip() if isinstance(value, str) else ""


def is_camera(device: dict) -> bool:
    """True when ``GET /devices`` describes a snapshottable camera."""
    return _text(device.get("component")).lower() == CAMERA_COMPONENT


def is_offline(device: dict) -> bool:
    """Only an explicit ``offline`` is treated as unreachable; unknown is not."""
    return _text(device.get("availability")).lower() == "offline"


def camera_label(device: dict) -> str:
    """The label a queued bot message will carry, so it can key a delivery window."""
    return (
        _text(device.get("label"))
        or _text(device.get("deviceKey"))
        or "unknown camera"
    )


def select_cameras(devices: list[dict], argument: str | None) -> tuple[list[dict], str | None]:
    """Resolve the command's argument to the cameras it addresses.

    A blank argument means "every camera that is not offline" — a bare
    ``/snapshot`` is a request for the whole house. An explicit argument is
    matched against the label first (exact, then substring, case-insensitive) and
    also against the Ring device id, so an operator can paste an id from the logs.

    Returns ``(matches, error)``: an error is a ready-to-send Markdown message
    explaining why nothing was requested (unknown or ambiguous name), and in that
    case ``matches`` is empty.
    """
    cameras = [device for device in devices if is_camera(device)]
    wanted = _text(argument).lower()
    if not wanted:
        reachable = [camera for camera in cameras if not is_offline(camera)]
        if reachable:
            return reachable, None
        if cameras:
            return [], f"{WARNING_ICON} No camera is online right now."
        return [], f"{WARNING_ICON} No cameras are known."
    exact = [
        camera for camera in cameras
        if camera_label(camera).lower() == wanted or _text(camera.get("deviceKey")).lower() == wanted
    ]
    matches = exact or [camera for camera in cameras if wanted in camera_label(camera).lower()]
    if len(matches) == 1:
        return matches, None
    if not matches:
        known = ", ".join(f"`{markdown_safe(camera_label(camera))}`" for camera in cameras) or "none"
        return [], f"{WARNING_ICON} No camera matches `{markdown_safe(argument)}`. Known cameras: {known}."
    listing = ", ".join(f"`{markdown_safe(camera_label(camera))}`" for camera in matches)
    return [], f"{WARNING_ICON} `{markdown_safe(argument)}` matches several cameras: {listing}. Name one."


def _skip_reason(skip: dict) -> str:
    """Describe one skipped camera in the operator's terms, not the wire's."""
    reason = _text(skip.get("reason"))
    if reason == "offline":
        return "offline"
    if reason == "recently_requested":
        retry_after = skip.get("retry_after_seconds")
        return f"retry in {retry_after}s" if isinstance(retry_after, int) else "already requested"
    if reason == "not_a_camera":
        return "not a camera"
    if reason == "disabled":
        return "disabled"
    if reason == "unknown_camera":
        return "unknown camera"
    if reason == "config_unavailable":
        return "configuration unavailable"
    if reason == "unknown_location":
        return "location unknown"
    if reason == "publish_failed":
        return "bridge unavailable"
    return reason or "skipped"


def format_snapshot_result(requested: list[dict], skipped: list[dict]) -> str:
    """Compose the reply for a ``POST /snapshot`` result.

    ``requested`` and ``skipped`` are the lists the event processor returns. The
    reply deliberately states the count so a bare ``/snapshot`` on a growing
    house is unambiguous, and names every skip so an operator is never left
    wondering which camera stayed silent.
    """
    requested_labels = ", ".join(f"`{markdown_safe(camera_label(camera))}`" for camera in requested)
    skipped_detail = ", ".join(
        f"`{markdown_safe(camera_label(camera))}` ({_skip_reason(camera)})" for camera in skipped
    )
    total = len(requested) + len(skipped)
    if not total:
        return f"{WARNING_ICON} *No cameras are configured*."
    if not requested:
        return "\n".join([
            f"{WARNING_ICON} *No camera was snapshotted*.",
            f"Skipped: {skipped_detail}.",
        ])
    lines = [f"{CAMERA_ICON} *Snapshot requested* — {requested_labels}"]
    if skipped:
        lines.append(f"Requested {len(requested)} of {total}: skipped {skipped_detail}.")
    return "\n".join(lines)
