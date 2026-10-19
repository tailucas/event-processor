"""Arm/disarm switches and output-enable configuration switches."""

from __future__ import annotations

from homeassistant.components.switch import SwitchEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity import EntityCategory
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .coordinator import EventProcessorCoordinator
from .entity import EventProcessorEntity, add_new_entities, is_managed


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    coordinator: EventProcessorCoordinator = entry.runtime_data.coordinator
    armed_tracked: set[str] = set()
    enabled_tracked: set[str] = set()

    add_new_entities(
        entry,
        coordinator,
        async_add_entities,
        armed_tracked,
        lambda device_key: EventProcessorInputArmedSwitch(coordinator, device_key),
        lambda device_key, device: is_managed(coordinator, device)
        and "input" in device.get("roles", []),
    )
    add_new_entities(
        entry,
        coordinator,
        async_add_entities,
        enabled_tracked,
        lambda device_key: EventProcessorOutputEnabledSwitch(coordinator, device_key),
        lambda device_key, device: is_managed(coordinator, device)
        and "output" in device.get("roles", []),
    )


class EventProcessorInputArmedSwitch(EventProcessorEntity, SwitchEntity):
    """Runtime arm/disarm control for one input."""

    _attr_name = "Armed"
    _attr_icon = "mdi:shield-home"

    def __init__(self, coordinator: EventProcessorCoordinator, device_key: str) -> None:
        super().__init__(coordinator, device_key, "armed")

    @property
    def is_on(self) -> bool:
        return bool(self.input_data.get("device_enabled"))

    async def async_turn_on(self, **kwargs) -> None:
        await self.coordinator.runtime.client.set_input_enabled(self.device_key, True)
        await self.coordinator.async_request_refresh()

    async def async_turn_off(self, **kwargs) -> None:
        await self.coordinator.runtime.client.set_input_enabled(self.device_key, False)
        await self.coordinator.async_request_refresh()


class EventProcessorOutputEnabledSwitch(EventProcessorEntity, SwitchEntity):
    """Whether the hub is allowed to trigger this output."""

    _attr_name = "Enabled"
    _attr_entity_category = EntityCategory.CONFIG
    _attr_icon = "mdi:toggle-switch"

    def __init__(self, coordinator: EventProcessorCoordinator, device_key: str) -> None:
        super().__init__(coordinator, device_key, "output_enabled")

    @property
    def is_on(self) -> bool:
        return bool(self.output_data.get("device_enabled"))

    async def async_turn_on(self, **kwargs) -> None:
        await self.coordinator.runtime.client.patch_device(
            self.device_key, {"device_enabled": True}
        )
        await self.coordinator.async_request_refresh()

    async def async_turn_off(self, **kwargs) -> None:
        await self.coordinator.runtime.client.patch_device(
            self.device_key, {"device_enabled": False}
        )
        await self.coordinator.async_request_refresh()
