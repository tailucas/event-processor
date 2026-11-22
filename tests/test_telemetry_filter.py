"""Tests for the FastAPI telemetry route exclusions (pure functions)."""

from app.telemetry_filter import EXCLUDED_ROUTES, excluded_route


def test_device_info_post_is_excluded():
    assert excluded_route({"type": "http", "method": "POST", "path": "/api/device_info"})
    # a trailing slash is the same route
    assert excluded_route({"type": "http", "method": "POST", "path": "/api/device_info/"})


def test_other_api_posts_remain_traced():
    """Filtering one route must not become filtering the /api prefix."""
    assert not excluded_route({"type": "http", "method": "POST", "path": "/api/device"})
    assert not excluded_route({"type": "http", "method": "POST", "path": "/api/devices"})
    assert not excluded_route({"type": "http", "method": "POST", "path": "/api/device_info_extra"})
    assert not excluded_route({"type": "http", "method": "POST", "path": "/api/device_info/sub"})


def test_the_exclusion_is_method_specific():
    assert not excluded_route({"type": "http", "method": "GET", "path": "/api/device_info"})


def test_non_http_scopes_are_never_excluded():
    assert not excluded_route({"type": "websocket", "path": "/api/device_info"})
    assert not excluded_route({})


def test_the_excluded_route_list_is_exact():
    assert set(EXCLUDED_ROUTES) == {("POST", "/api/device_info")}
