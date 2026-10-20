"""Action buttons for outputs, meter reset and the hub panic input."""

from __future__ import annotations

from homeassistant.components.button import ButtonEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import ACTION_BUTTONS
from .coordinator import EventProcessorCoordinator
from .entity import (
    EventProcessorEntity,
    EventProcessorHubEntity,
    add_new_entities,
    is_managed,
)

ICONS = {
    "Camera": "mdi:camera",
    "Buzzer": "mdi:bullhorn",
    "l2ping": "mdi:bluetooth",
}


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    coordinator: EventProcessorCoordinator = entry.runtime_data.coordinator
    action_tracked: set[str] = set()
    meter_tracked: set[str] = set()

    add_new_entities(
        entry,
        coordinator,
        async_add_entities,
        action_tracked,
        lambda device_key: EventProcessorActionButton(coordinator, device_key),
        lambda device_key, device: is_managed(coordinator, device)
        and "output" in device.get("roles", [])
        and device.get("device_type") in ACTION_BUTTONS,
    )
    add_new_entities(
        entry,
        coordinator,
        async_add_entities,
        meter_tracked,
        lambda device_key: EventProcessorMeterResetButton(coordinator, device_key),
        lambda device_key, device: is_managed(coordinator, device)
        and device.get("meter") is not None,
    )
    async_add_entities([EventProcessorPanicButton(coordinator)])


class EventProcessorActionButton(EventProcessorEntity, ButtonEntity):
    """Trigger one output action through the hub."""

    def __init__(self, coordinator: EventProcessorCoordinator, device_key: str) -> None:
        device = coordinator.data.devices[device_key]
        label = ACTION_BUTTONS.get(device.get("device_type"), "Trigger")
        super().__init__(coordinator, device_key, "action", label)
        self._attr_icon = ICONS.get(device.get("device_type"))

    async def async_press(self) -> None:
        await self.coordinator.runtime.client.trigger_output(self.device_key, {})


class EventProcessorMeterResetButton(EventProcessorEntity, ButtonEntity):
    """Reset the meter using the value configured in the hub."""

    _attr_name = "Reset"
    _attr_icon = "mdi:counter"

    def __init__(self, coordinator: EventProcessorCoordinator, device_key: str) -> None:
        super().__init__(coordinator, device_key, "meter_reset")

    async def async_press(self) -> None:
        await self.coordinator.runtime.client.reset_meter(self.device_key, None)


class EventProcessorPanicButton(EventProcessorHubEntity, ButtonEntity):
    """Fire the legacy panic input."""

    _attr_name = "Panic"
    _attr_icon = "mdi:alert-octagon"

    def __init__(self, coordinator: EventProcessorCoordinator) -> None:
        super().__init__(coordinator, "panic")

    async def async_press(self) -> None:
        await self.coordinator.runtime.client.panic()
