"""Tests for the auto-scheduler's time rules (pure functions)."""

from datetime import time

import pytest
from pytz import timezone
import schedule

from app.schedule_time import normalize_schedule_time


def test_a_blank_time_means_the_transition_is_not_scheduled():
    """Regression: an empty auto_schedule_* column reached schedule.every().day.at('')."""
    assert normalize_schedule_time(None) is None
    assert normalize_schedule_time("") is None
    assert normalize_schedule_time("   ") is None


def test_a_time_is_returned_in_the_format_the_scheduler_accepts():
    assert normalize_schedule_time("22:30") == "22:30"
    assert normalize_schedule_time("05:30") == "05:30"
    assert normalize_schedule_time("22:30:15") == "22:30:15"


def test_a_single_digit_hour_is_padded():
    """The form's clock picker can emit 5:30; the scheduler's parser requires two digits."""
    assert normalize_schedule_time("5:30") == "05:30"
    assert normalize_schedule_time(" 5:30 ") == "05:30"


def test_a_time_object_is_accepted():
    assert normalize_schedule_time(time(22, 30)) == "22:30"
    assert normalize_schedule_time(time(22, 30, 15)) == "22:30:15"


@pytest.mark.parametrize("value", ["24:00", "25:00", "12:60", "7:3", "2230", "22:30pm", "tomorrow"])
def test_an_unparseable_time_is_rejected(value):
    with pytest.raises(ValueError):
        normalize_schedule_time(value)


@pytest.mark.parametrize("value", ["00:00", "5:30", "23:59", "22:30:15"])
def test_a_normalised_time_is_accepted_by_schedule(value):
    job = schedule.every().day.at(normalize_schedule_time(value), timezone("Africa/Johannesburg"))
    assert job.at_time == time.fromisoformat(normalize_schedule_time(value))


def test_the_scheduler_rejects_the_blank_value_the_rule_treats_as_unset():
    """Why normalisation exists: ``at('')`` is the ScheduleValueError behind EVENT-PROCESSOR-YN."""
    with pytest.raises(schedule.ScheduleValueError):
        schedule.every().day.at("", timezone("Africa/Johannesburg"))
