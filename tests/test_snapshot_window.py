"""Tests for the ``/snapshot`` delivery window (pure, clock-injected)."""

from app.snapshot_window import SnapshotWindows


class FakeClock:
    """A monotonic clock the test advances by hand."""

    def __init__(self, now: float = 1000.0):
        self.now = now

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def test_a_window_opens_for_the_requested_camera_only():
    windows = SnapshotWindows(window_seconds=60, clock=FakeClock())
    assert windows.open("Front Gate")
    assert windows.is_open("Front Gate")
    assert not windows.is_open("Pool Camera")


def test_images_stay_unfiltered_for_the_whole_window():
    """One request yields several images: the raw frame, then the detected copy."""
    clock = FakeClock()
    windows = SnapshotWindows(window_seconds=60, clock=clock)
    windows.open("Front Gate")
    clock.advance(30)
    assert windows.is_open("Front Gate")
    clock.advance(29)
    assert windows.is_open("Front Gate")


def test_the_window_expires_on_its_own():
    clock = FakeClock()
    windows = SnapshotWindows(window_seconds=60, clock=clock)
    windows.open("Front Gate")
    clock.advance(60)
    assert not windows.is_open("Front Gate")
    assert windows.open_labels() == []


def test_reopening_extends_the_deadline():
    clock = FakeClock()
    windows = SnapshotWindows(window_seconds=60, clock=clock)
    windows.open("Front Gate")
    clock.advance(50)
    windows.open("Front Gate")
    clock.advance(50)
    assert windows.is_open("Front Gate")


def test_closing_ends_the_window_early():
    clock = FakeClock()
    windows = SnapshotWindows(window_seconds=60, clock=clock)
    windows.open("Front Gate")
    windows.close("Front Gate")
    assert not windows.is_open("Front Gate")
    # closing an unknown window is a no-op, not an error
    windows.close("Pool Camera")


def test_a_blank_label_never_opens_a_window():
    windows = SnapshotWindows(window_seconds=60, clock=FakeClock())
    assert not windows.open(None)
    assert not windows.open("   ")
    assert not windows.is_open(None)
    assert not windows.is_open("   ")
    assert windows.open_labels() == []


def test_labels_are_compared_after_stripping_whitespace():
    windows = SnapshotWindows(window_seconds=60, clock=FakeClock())
    windows.open(" Front Gate ")
    assert windows.is_open("Front Gate")
    assert windows.open_labels() == ["Front Gate"]
