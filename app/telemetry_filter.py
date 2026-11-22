"""Which HTTP routes are excluded from FastAPI's built-in telemetry.

Pure functions only, so the policy is unit tested without booting the application
(see ``tests/test_telemetry_filter.py``). FastAPI emits its own server span plus
``endpoint``/``dependencies``/``serialization`` operation spans for every request,
so a high-frequency internal route multiplies the trace volume.
"""

from collections.abc import MutableMapping
from typing import Any

#: Routes whose telemetry is pure noise, keyed as ``(METHOD, path)``.
#:
#: ``POST /api/device_info`` is the heartbeat/config handshake: the Java event
#: processor posts it for every device heartbeat, and nothing an operator debugs
#: lives in its trace. The match is the exact path, never the ``/api`` prefix, so
#: every other API route keeps its spans.
EXCLUDED_ROUTES = frozenset({
    ("POST", "/api/device_info"),
})


def excluded_route(scope: MutableMapping[str, Any]) -> bool:
    """Return ``True`` when FastAPI telemetry should skip this ASGI scope.

    Passed to ``FastAPI(telemetry={"exclude": ...})``. Method and path must both
    match (a trailing slash is tolerated), so ``POST /api/device_info_extra``,
    ``POST /api/device_info/sub`` and ``GET /api/device_info`` stay traced.
    """
    if scope.get("type") != "http":
        return False
    method = str(scope.get("method") or "")
    # ASGI paths are absolute; normalise only so "/x" and "/x/" agree.
    path = str(scope.get("path") or "/").rstrip("/") or "/"
    return (method, path) in EXCLUDED_ROUTES
