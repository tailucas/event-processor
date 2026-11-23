"""Tests for the FastAPI telemetry route exclusions (pure functions)."""

from app.telemetry_filter import DEVICE_CONFIG_ROUTES, EXCLUDED_ROUTES, excluded_route


def test_device_info_post_is_excluded():
    assert excluded_route({"type": "http", "method": "POST", "path": "/api/device_info"})
    # a trailing slash is the same route
    assert excluded_route({"type": "http", "method": "POST", "path": "/api/device_info/"})


def test_the_device_config_pull_path_is_excluded():
    """Every route DeviceConfig/console reads configuration from is excluded."""
    for path in (
        "/api/input_config",
        "/api/input_configs",
        "/api/meter_config",
        "/api/output_config",
        "/api/output_link",
    ):
        assert excluded_route({"type": "http", "method": "GET", "path": path}), path


def test_the_config_route_set_covers_only_gets_under_api():
    assert all(method == "GET" and path.startswith("/api/") for method, path in DEVICE_CONFIG_ROUTES)


def test_config_lookalikes_remain_traced():
    """Exact matching keeps prefixes, siblings and other methods traced."""
    assert not excluded_route({"type": "http", "method": "GET", "path": "/api/input_config/extra"})
    assert not excluded_route({"type": "http", "method": "GET", "path": "/api/input_configuration"})
    assert not excluded_route({"type": "http", "method": "POST", "path": "/api/input_config"})


def test_other_api_posts_remain_traced():
    """Filtering one route must not become filtering the /api prefix."""
    assert not excluded_route({"type": "http", "method": "POST", "path": "/api/device"})
    assert not excluded_route({"type": "http", "method": "POST", "path": "/api/devices"})
    assert not excluded_route({"type": "http", "method": "POST", "path": "/api/device_info_extra"})
    assert not excluded_route({"type": "http", "method": "POST", "path": "/api/device_info/sub"})


def test_health_and_state_routes_remain_traced():
    assert not excluded_route({"type": "http", "method": "GET", "path": "/api/ping"})
    assert not excluded_route({"type": "http", "method": "GET", "path": "/api/running"})


def test_the_exclusion_is_method_specific():
    assert not excluded_route({"type": "http", "method": "GET", "path": "/api/device_info"})


def test_non_http_scopes_are_never_excluded():
    assert not excluded_route({"type": "websocket", "path": "/api/device_info"})
    assert not excluded_route({})


def test_the_excluded_route_list_is_exact():
    assert set(EXCLUDED_ROUTES) == {
        ("POST", "/api/device_info"),
        ("GET", "/api/input_config"),
        ("GET", "/api/input_configs"),
        ("GET", "/api/meter_config"),
        ("GET", "/api/output_config"),
        ("GET", "/api/output_link"),
    }
