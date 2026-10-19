"""Binary sensors for inputs and connectivity checks."""

from __future__ import annotations

from homeassistant.components.binary_sensor import (
    BinarySensorDeviceClass,
    BinarySensorEntity,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import CONNECTIVITY_TYPES, MOTION_TYPES, OPENING_TYPES, SMOKE_TYPES, SOUND_TYPES
from .coordinator import EventProcessorCoordinator
from .entity import EventProcessorEntity, add_new_entities, is_managed


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    coordinator: EventProcessorCoordinator = entry.runtime_data.coordinator
    tracked: set[str] = set()

    def _device_class(device: dict) -> BinarySensorDeviceClass | None:
        device_type = device.get("device_type")
        roles = device.get("roles", [])
        if device_type in MOTION_TYPES and "input" in roles:
            return BinarySensorDeviceClass.MOTION
        if device_type in OPENING_TYPES and "input" in roles:
            return BinarySensorDeviceClass.OPENING
        if device_type in SMOKE_TYPES and "input" in roles:
            return BinarySensorDeviceClass.SMOKE
        if device_type in SOUND_TYPES and "input" in roles:
            return BinarySensorDeviceClass.SOUND
        if device_type in CONNECTIVITY_TYPES and "input" in roles:
            return BinarySensorDeviceClass.CONNECTIVITY
        if device_type == "l2ping" and "output" in roles:
            return BinarySensorDeviceClass.CONNECTIVITY
        return None

    def _factory(device_key: str) -> EventProcessorBinarySensor:
        device = coordinator.data.devices[device_key]
        return EventProcessorBinarySensor(
            coordinator, device_key, _device_class(device) or BinarySensorDeviceClass.CONNECTIVITY
        )

    def _predicate(device_key: str, device: dict) -> bool:
        return is_managed(coordinator, device) and _device_class(device) is not None

    add_new_entities(
        entry, coordinator, async_add_entities, tracked, _factory, _predicate
    )


class EventProcessorBinarySensor(EventProcessorEntity, BinarySensorEntity):
    """State of a motion, contact, smoke, siren or connectivity device."""

    def __init__(
        self,
        coordinator: EventProcessorCoordinator,
        device_key: str,
        device_class: BinarySensorDeviceClass,
    ) -> None:
        super().__init__(coordinator, device_key, "state")
        self._attr_device_class = device_class
        self._attr_name = None

    @property
    def is_on(self) -> bool:
        state = self.state_data
        if "active" in state:
            return bool(state["active"])
        return bool(state.get("connected"))
