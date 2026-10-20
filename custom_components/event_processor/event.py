"""Discrete event entities for buttons and camera detections."""

from __future__ import annotations

from typing import Any

from homeassistant.components.event import EventDeviceClass, EventEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import MOTION_TYPES, OPENING_TYPES
from .coordinator import EventProcessorCoordinator
from .entity import EventProcessorEntity, add_new_entities, is_managed

BUTTON_TYPES = {"Dash Button", "Panic Button"}
CAMERA_TYPES = {"Camera"}


def _event_spec(device: dict) -> tuple[list[str], str | None, EventDeviceClass | None, bool] | None:
    device_type = device.get("device_type")
    if device_type in BUTTON_TYPES:
        return ["pressed"], "Pressed", EventDeviceClass.BUTTON, True
    if device_type in CAMERA_TYPES:
        return (
            ["motion", "person", "vehicle", "animal", "package"],
            "Detections",
            EventDeviceClass.MOTION,
            True,
        )
    if device_type in MOTION_TYPES:
        return ["motion"], "Motion events", EventDeviceClass.MOTION, False
    if device_type in OPENING_TYPES:
        return ["opened", "closed"], "Open/close events", None, False
    return None


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    coordinator: EventProcessorCoordinator = entry.runtime_data.coordinator
    runtime = entry.runtime_data
    tracked: set[str] = set()

    def _factory(device_key: str) -> EventProcessorEvent:
        device = coordinator.data.devices[device_key]
        spec = _event_spec(device)
        assert spec is not None
        event_types, name, device_class, always = spec
        enabled = always or runtime.enable_event_entities
        return EventProcessorEvent(
            coordinator, device_key, event_types, name, device_class, enabled
        )

    def _predicate(device_key: str, device: dict) -> bool:
        spec = _event_spec(device)
        if spec is None or not is_managed(coordinator, device):
            return False
        return "input" in device.get("roles", []) and (spec[3] or runtime.enable_event_entities)

    add_new_entities(entry, coordinator, async_add_entities, tracked, _factory, _predicate)


class EventProcessorEvent(EventProcessorEntity, EventEntity):
    """A discrete trigger emitted by the hub."""

    def __init__(
        self,
        coordinator: EventProcessorCoordinator,
        device_key: str,
        event_types: list[str],
        name: str | None,
        device_class: EventDeviceClass | None,
        enabled_default: bool,
    ) -> None:
        super().__init__(coordinator, device_key, "events", name)
        self._attr_event_types = event_types
        self._attr_device_class = device_class
        self._attr_entity_registry_enabled_default = enabled_default
        self._last_seq = 0

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        events = self.coordinator.data.events if self.coordinator.data else []
        self._last_seq = max(
            (int(event.get("seq", 0)) for event in events if event.get("device_key") == self.device_key),
            default=0,
        )

    def _handle_coordinator_update(self) -> None:
        events = self.coordinator.data.events if self.coordinator.data else []
        for event in reversed(events):
            if event.get("device_key") != self.device_key or event.get("type") != "input_triggered":
                continue
            seq = int(event.get("seq", 0))
            if seq <= self._last_seq:
                break
            self._last_seq = seq
            data = event.get("data") or {}
            event_type = self._event_type(data)
            self._trigger_event(event_type, data)
            self.async_write_ha_state()
            break
        super()._handle_coordinator_update()

    def _event_type(self, data: dict[str, Any]) -> str:
        detail = str(data.get("event_detail") or data.get("trigger_state") or "").lower()
        for event_type in self._attr_event_types:
            if event_type in detail:
                return event_type
        if "motion" in detail and "motion" in self._attr_event_types:
            return "motion"
        return self._attr_event_types[0]
