"""Normalisation of the auto-scheduler's daily times.

The ``schedule`` library accepts only ``HH:MM``/``HH:MM:SS`` for a daily job and
raises ``ScheduleValueError`` for anything else — including the empty string a blank
configuration form submits. Installing a schedule is therefore not something to do
with a raw configuration value: this module normalises it first and reports "not set"
distinctly from "invalid", so a blank field means "no transition at this time" rather
than a schedule error.

Pure functions only, so the rules are unit tested without booting the application
(see ``tests/test_schedule_time.py``).
"""

from datetime import time
import re

#: ``H:MM``, ``HH:MM`` or ``HH:MM:SS``. The hour range is checked after matching.
SCHEDULE_TIME_PATTERN = re.compile(r"^(?P<hour>\d{1,2}):(?P<minute>[0-5]\d)(?::(?P<second>[0-5]\d))?$")


def normalize_schedule_time(value: str | time | None) -> str | None:
    """Return ``value`` as the ``HH:MM(:SS)`` string a daily ``schedule`` job accepts.

    ``None`` and a blank/whitespace-only string mean "no transition at this time" and
    normalise to ``None``. A single-digit hour is padded (``7:30`` becomes ``07:30``)
    because the scheduler's parser requires two digits.

    Raises:
        ValueError: when a value is present but is not a valid 24-hour time.
    """
    if value is None:
        return None
    if isinstance(value, time):
        return value.strftime("%H:%M:%S") if value.second or value.microsecond else value.strftime("%H:%M")
    if not isinstance(value, str):
        raise ValueError(f"Unsupported schedule time type: {type(value).__name__}")
    normalized = value.strip()
    if not normalized:
        return None
    match = SCHEDULE_TIME_PATTERN.match(normalized)
    if match is None:
        raise ValueError(f"Invalid schedule time: {value!r}")
    hour = int(match.group("hour"))
    if hour > 23:
        raise ValueError(f"Invalid schedule time: {value!r}")
    minute = match.group("minute")
    second = match.group("second")
    if second is None:
        return f"{hour:02d}:{minute}"
    return f"{hour:02d}:{minute}:{second}"
