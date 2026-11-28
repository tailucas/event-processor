"""Tests for ``/snapshot`` camera selection and reply composition (pure)."""

from app.snapshot_command import format_snapshot_result, is_camera, select_cameras

FRONT_GATE = {
    "deviceKey": "54e0191a1d8d",
    "component": "camera",
    "label": "Front Gate",
    "availability": "online",
}
PATIO = {
    "deviceKey": "bb22",
    "component": "camera",
    "label": "Patio Camera",
    "availability": "online",
}
POOL = {
    "deviceKey": "aa11",
    "component": "camera",
    "label": "Pool Camera",
    "availability": "offline",
}
BACK_DOOR_SENSOR = {
    "deviceKey": "a539a24d",
    "component": "alarm",
    "label": "Back Door",
    "availability": "online",
}
DEVICES = [FRONT_GATE, PATIO, POOL, BACK_DOOR_SENSOR]


def test_only_camera_components_are_snapshottable():
    assert is_camera(FRONT_GATE)
    assert not is_camera(BACK_DOOR_SENSOR)


def test_a_bare_command_addresses_every_online_camera():
    """/snapshot means the whole house, but never wakes an offline camera."""
    matches, error = select_cameras(DEVICES, None)
    assert error is None
    assert [camera["label"] for camera in matches] == ["Front Gate", "Patio Camera"]


def test_an_exact_label_is_matched_without_regard_to_case():
    matches, error = select_cameras(DEVICES, "front gate")
    assert error is None
    assert matches == [FRONT_GATE]


def test_a_partial_label_is_accepted_when_it_is_unambiguous():
    matches, error = select_cameras(DEVICES, "patio")
    assert error is None
    assert matches == [PATIO]


def test_a_device_id_is_accepted_as_well_as_a_label():
    matches, error = select_cameras(DEVICES, "54e0191a1d8d")
    assert error is None
    assert matches == [FRONT_GATE]


def test_an_offline_camera_can_still_be_named_and_the_server_reports_it():
    """Naming an offline camera is a request, not a guess: let the server say why."""
    matches, error = select_cameras(DEVICES, "pool")
    assert error is None
    assert matches == [POOL]


def test_an_ambiguous_name_is_refused_with_the_candidates():
    devices = [
        {"deviceKey": "1", "component": "camera", "label": "Gate Front", "availability": "online"},
        {"deviceKey": "2", "component": "camera", "label": "Gate Back", "availability": "online"},
    ]
    matches, error = select_cameras(devices, "gate")
    assert matches == []
    assert error is not None
    assert "Gate Front" in error
    assert "Gate Back" in error


def test_an_unknown_name_is_refused_and_the_known_cameras_are_listed():
    matches, error = select_cameras(DEVICES, "garage")
    assert matches == []
    assert error is not None
    assert "garage" in error
    # alarm devices are not offered as snapshot targets
    assert "Back Door" not in error
    assert "Front Gate" in error


def test_a_house_with_only_offline_cameras_says_so():
    matches, error = select_cameras([POOL], None)
    assert matches == []
    assert error is not None
    assert "online" in error


def test_no_known_cameras_says_so():
    matches, error = select_cameras([BACK_DOOR_SENSOR], None)
    assert matches == []
    assert error is not None
    assert "No cameras" in error


def test_a_requested_snapshot_names_the_camera():
    reply = format_snapshot_result(requested=[FRONT_GATE], skipped=[])
    assert "Front Gate" in reply
    assert "Snapshot requested" in reply
    assert "skipped" not in reply


def test_a_partial_request_states_the_count_and_every_skip():
    reply = format_snapshot_result(
        requested=[FRONT_GATE, PATIO],
        skipped=[{"deviceKey": "aa11", "label": "Pool Camera", "reason": "offline"}],
    )
    assert "2 of 3" in reply
    assert "Pool Camera" in reply
    assert "offline" in reply


def test_a_rate_limited_skip_reports_the_retry_window():
    reply = format_snapshot_result(
        requested=[FRONT_GATE],
        skipped=[{"label": "Patio Camera", "reason": "recently_requested", "retry_after_seconds": 7}],
    )
    assert "retry in 7s" in reply


def test_nothing_requested_lists_why():
    reply = format_snapshot_result(
        requested=[],
        skipped=[{"label": "Pool Camera", "reason": "offline"}],
    )
    assert "No camera was snapshotted" in reply
    assert "Pool Camera" in reply


def test_an_empty_device_list_is_reported_rather_than_sending_an_empty_message():
    reply = format_snapshot_result(requested=[], skipped=[])
    assert "No cameras" in reply


def test_a_label_cannot_inject_markdown():
    hostile = {"deviceKey": "x", "component": "camera", "label": "Front*Gate_[now]", "availability": "online"}
    reply = format_snapshot_result(requested=[hostile], skipped=[])
    assert "*Gate" not in reply
    assert "[" not in reply
