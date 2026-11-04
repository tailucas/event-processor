"""Tests for the bot's image-forwarding policy (pure functions)."""

from app.telegram_filter import SNAPSHOT_SOURCE, include_image, is_explicit_snapshot


def test_input_sent_snapshot_bypasses_the_person_filter():
    # a Ring snapshot wired to the SMS output is a deliberate notification
    assert include_image(source=SNAPSHOT_SOURCE, text="Front Gate snapshot (14:02)", only_with_people=True)


def test_unattended_camera_frame_needs_an_annotation():
    assert not include_image(source=None, text="Patio Camera (14:02)", only_with_people=True)
    assert include_image(source=None, text="Patio Camera person detected (14:02)", only_with_people=True)
    assert include_image(source=None, text="Patio Camera face detected (14:02)", only_with_people=True)


def test_every_image_passes_when_the_filter_is_off():
    assert include_image(source=None, text="Patio Camera (14:02)", only_with_people=False)


def test_other_event_details_are_not_treated_as_snapshots():
    assert not include_image(source="person detected", text="Patio Camera (14:02)", only_with_people=True)
    assert not include_image(source="", text="Patio Camera (14:02)", only_with_people=True)


def test_snapshot_detection_tolerates_case_and_whitespace():
    assert is_explicit_snapshot(" snapshot ")
    assert is_explicit_snapshot("Snapshot")
    assert not is_explicit_snapshot(None)
    assert not is_explicit_snapshot("snapshots")
