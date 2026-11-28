"""Time-bounded, per-camera exception to the object-detection image filter.

``/snapshot`` asks a camera for a picture. Everything that camera returns as a
consequence of that request must reach the chat *as taken*: the operator asked
for it, so a detector's verdict cannot override the request. The setting
``[telegram] image_send_only_with_people`` keeps applying to unattended frames.

The exception is a window rather than a per-image flag because one request can
produce more than one message for the same camera — the raw frame, then the
annotated copy the snapshot processor republishes — and both are "images after
the command". Consuming the exception on the first image would drop the second.

Pure and clock-injectable, so the policy is unit tested without booting the
application (see ``tests/test_snapshot_window.py``); the rules are documented in
``.clinerules/telegram.md``.
"""

from collections.abc import Callable
import time

#: How long after the command a camera's images are forwarded unfiltered. The
#: round trip is seconds (a Ring on-demand snapshot was measured at ~6s) and the
#: margin covers snapshot-processor's detection pass as well as Telegram's own
#: ``min_send_interval`` deferral.
DEFAULT_WINDOW_SECONDS = 60.0


class SnapshotWindows:
    """Per-camera delivery windows, keyed by device label.

    The label is the key because that is what a queued bot message carries and
    what the operator sees; the closed set of labels is owned by the command
    (see ``app.snapshot_command``), which resolves the operator's argument to a
    canonical label before opening a window.
    """

    def __init__(
        self,
        window_seconds: float = DEFAULT_WINDOW_SECONDS,
        clock: Callable[[], float] = time.monotonic,
    ):
        self._window_seconds = window_seconds
        self._clock = clock
        self._deadlines: dict[str, float] = {}

    @property
    def window_seconds(self) -> float:
        return self._window_seconds

    def open(self, device_label: str | None) -> bool:
        """Start (or extend) the window for one camera; False for a blank label."""
        label = (device_label or "").strip()
        if not label:
            return False
        self._deadlines[label] = self._clock() + self._window_seconds
        return True

    def is_open(self, device_label: str | None) -> bool:
        """True while this camera's images must bypass the detection filter."""
        label = (device_label or "").strip()
        if not label:
            return False
        deadline = self._deadlines.get(label)
        if deadline is None:
            return False
        if self._clock() >= deadline:
            # expire on read: no timer thread, and the map cannot grow unbounded
            del self._deadlines[label]
            return False
        return True

    def close(self, device_label: str | None) -> None:
        """Close one camera's window early (no-op when it is not open)."""
        label = (device_label or "").strip()
        if label:
            self._deadlines.pop(label, None)

    def open_labels(self) -> list[str]:
        """Cameras whose window is still open, for logging and diagnostics."""
        return sorted(label for label in list(self._deadlines) if self.is_open(label))
