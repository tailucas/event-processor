"""Which HTTP routes are excluded from FastAPI's built-in telemetry.

Pure functions only, so the policy is unit tested without booting the application
(see ``tests/test_telemetry_filter.py``). FastAPI emits its own server span plus
``endpoint``/``dependencies``/``serialization`` operation spans for every request,
so a high-frequency internal route multiplies the trace volume.
"""

from collections.abc import MutableMapping
from typing import Any

#: The device-config pull path (``tailucas.app.provider.DeviceConfig``). The Java
#: event processor fetches these whenever its config cache is cold or expired, and
#: the console reads ``input_configs`` for the device list. These are configuration
#: reads, not state transitions worth tracing. ``ConfigType`` values lower-case to
#: exactly these names; ``ha_config`` is declared but has no route.
DEVICE_CONFIG_ROUTES = frozenset({
    ("GET", "/api/input_config"),
    ("GET", "/api/input_configs"),
    ("GET", "/api/meter_config"),
    ("GET", "/api/output_config"),
    ("GET", "/api/output_link"),
})

#: Routes whose telemetry is pure noise, keyed as ``(METHOD, path)``.
#:
#: ``POST /api/device_info`` is the heartbeat/config handshake: the event processor
#: posts it for every device heartbeat, and nothing an operator debugs lives in its
#: trace. Matching is exact on method and path, never the ``/api`` prefix, so
#: siblings such as ``/api/device_info_extra`` or ``GET /api/device_info`` keep
#: their spans.
EXCLUDED_ROUTES = frozenset({
    ("POST", "/api/device_info"),
}) | DEVICE_CONFIG_ROUTES


def excluded_route(scope: MutableMapping[str, Any]) -> bool:
    """Return ``True`` when FastAPI telemetry should skip this ASGI scope.

    Passed to ``FastAPI(telemetry={"exclude": ...})``. Method and path must both
    match (a trailing slash is tolerated), so ``POST /api/device_info_extra``,
    ``GET /api/input_config/extra`` and ``POST /api/input_config`` stay traced.
    """
    if scope.get("type") != "http":
        return False
    method = str(scope.get("method") or "")
    # ASGI paths are absolute; normalise only so "/x" and "/x/" agree.
    path = str(scope.get("path") or "/").rstrip("/") or "/"
    return (method, path) in EXCLUDED_ROUTES
