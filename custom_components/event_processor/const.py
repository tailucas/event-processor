"""Constants for the Event Processor integration."""

from __future__ import annotations

DOMAIN = "event_processor"
NAME = "Event Processor"
VERSION = "0.1.0"

CONF_HOST = "host"
CONF_PORT = "port"
CONF_TOKEN = "token"
CONF_SCAN_INTERVAL = "scan_interval"
CONF_CREATE_EXTERNAL_DEVICES = "create_external_devices"
CONF_ENABLE_EVENT_ENTITIES = "enable_event_entities"
CONF_ENABLE_DIAGNOSTIC_ENTITIES = "enable_diagnostic_entities"

DEFAULT_PORT = 8095
DEFAULT_SCAN_INTERVAL = 30
DEFAULT_CREATE_EXTERNAL_DEVICES = False
DEFAULT_ENABLE_EVENT_ENTITIES = True
DEFAULT_ENABLE_DIAGNOSTIC_ENTITIES = True

PLATFORMS = [
    "binary_sensor",
    "event",
    "switch",
    "button",
    "sensor",
    "number",
    "alarm_control_panel",
    "notify",
]

# Input device types that are represented as binary sensors.
MOTION_TYPES = {"motion", "Motion Detector", "Camera"}
OPENING_TYPES = {"contact", "Back Door", "Main Door"}
SMOKE_TYPES = {"Smoke Detector", "detector"}
SOUND_TYPES = {"siren"}
CONNECTIVITY_TYPES = {"info"}

# Output device types that get an action button.
ACTION_BUTTONS = {
    "Camera": "Snapshot",
    "Buzzer": "Sound",
    "l2ping": "Ping",
}

# Input tuning fields exposed as CONFIG numbers when they have a value.
INPUT_NUMBER_FIELDS = {
    "trigger_latch_duration": "Latch duration",
    "multi_trigger_rate": "Multi-trigger rate",
    "multi_trigger_interval": "Multi-trigger interval",
    "activation_escalation": "Activation escalation",
}

OUTPUT_NUMBER_FIELDS = {
    "trigger_interval": "Trigger interval",
}

METER_NUMBER_FIELDS = {
    "meter_low_limit": "Low limit",
    "meter_high_limit": "High limit",
}
