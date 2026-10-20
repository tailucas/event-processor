"""Diagnostics support for Event Processor."""

from __future__ import annotations

from typing import Any

from homeassistant.components.diagnostics import async_redact_data
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant

from .const import CONF_TOKEN

TO_REDACT = {
    CONF_TOKEN,
    "token",
    "device_params",
    "trigger_topic",
    "image_url",
    "image_base64",
}


async def async_get_config_entry_diagnostics(
    hass: HomeAssistant, entry: ConfigEntry
) -> dict[str, Any]:
    runtime = entry.runtime_data
    data = runtime.coordinator.data
    return {
        "entry": {
            "data": async_redact_data(dict(entry.data), TO_REDACT),
            "options": dict(entry.options),
        },
        "info": data.info if data else {},
        "device_count": len(data.devices) if data else 0,
        "link_count": len(data.links) if data else 0,
        "groups": data.groups if data else [],
        "general": data.general if data else {},
        "heartbeat": data.heartbeat if data else None,
        "recent_events": list(data.events)[-20:] if data else [],
    }
