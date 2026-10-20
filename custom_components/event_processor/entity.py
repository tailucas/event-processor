"""Base entities and dynamic entity helpers."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.helpers.device_registry import DeviceEntryType, DeviceInfo
from homeassistant.helpers.entity import EntityCategory
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .client import HubClient
from .const import DOMAIN
from .coordinator import EventProcessorCoordinator


@dataclass
class HubRuntimeData:
    """Runtime objects shared by all platforms."""

    client: HubClient
    coordinator: EventProcessorCoordinator
    hub_device_id: str
    instance_id: str
    host: str
    port: int
    create_external_devices: bool = False
    enable_event_entities: bool = True
    enable_diagnostic_entities: bool = True


class EventProcessorEntity(CoordinatorEntity[EventProcessorCoordinator]):
    """Base entity attached to one hub device."""

    _attr_has_entity_name = True
    _attr_should_poll = False

    def __init__(
        self,
        coordinator: EventProcessorCoordinator,
        device_key: str,
        entity_key: str,
        name: str | None = None,
    ) -> None:
        super().__init__(coordinator)
        self.device_key = device_key
        self.entity_key = entity_key
        if name is not None:
            self._attr_name = name
        self._attr_unique_id = f"{device_key}_{entity_key}"

    @property
    def device(self) -> dict[str, Any] | None:
        return (self.coordinator.data.devices if self.coordinator.data else {}).get(self.device_key)

    @property
    def input_data(self) -> dict[str, Any]:
        return (self.device or {}).get("input") or {}

    @property
    def output_data(self) -> dict[str, Any]:
        return (self.device or {}).get("output") or {}

    @property
    def state_data(self) -> dict[str, Any]:
        return (self.device or {}).get("state") or {}

    @property
    def meter_data(self) -> dict[str, Any]:
        return (self.device or {}).get("meter") or {}

    @property
    def device_info(self) -> DeviceInfo | None:
        if self.device is None:
            return None
        return DeviceInfo(
            identifiers={(DOMAIN, self.device_key)},
            name=self.device.get("device_label") or self.device_key,
            manufacturer="Event Processor",
            model=self.device.get("device_type"),
            via_device_id=self.coordinator.runtime.hub_device_id,
        )

    @property
    def available(self) -> bool:
        return super().available and self.device is not None

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        return {
            "device_key": self.device_key,
            "device_type": (self.device or {}).get("device_type"),
            "group_name": (self.device or {}).get("group_name"),
            "roles": (self.device or {}).get("roles", []),
            "source": (self.device or {}).get("source"),
        }


class EventProcessorHubEntity(CoordinatorEntity[EventProcessorCoordinator]):
    """Base entity attached to the hub device itself."""

    _attr_has_entity_name = True
    _attr_should_poll = False

    def __init__(
        self,
        coordinator: EventProcessorCoordinator,
        entity_key: str,
        name: str | None = None,
        entity_category: EntityCategory | None = None,
    ) -> None:
        super().__init__(coordinator)
        self.entity_key = entity_key
        if name is not None:
            self._attr_name = name
        self._attr_entity_category = entity_category
        self._attr_unique_id = f"{coordinator.runtime.instance_id}_{entity_key}"

    @property
    def device_info(self) -> DeviceInfo:
        runtime = self.coordinator.runtime
        return DeviceInfo(
            identifiers={(DOMAIN, runtime.instance_id)},
            name="Event Processor",
            manufacturer="Tailucas",
            model="Event Processor Hub",
            sw_version=(self.coordinator.data.info or {}).get("version") if self.coordinator.data else None,
            configuration_url=f"http://{runtime.host}:{runtime.port}",
            entry_type=DeviceEntryType.SERVICE,
        )


def is_managed(coordinator: EventProcessorCoordinator, device: dict[str, Any]) -> bool:
    """Return True when this integration should own entities for the device."""
    if device.get("source") == "ring":
        return coordinator.runtime.create_external_devices
    return True


def add_new_entities(
    entry: ConfigEntry,
    coordinator: EventProcessorCoordinator,
    async_add_entities: Callable[[list[Any]], None],
    tracked: set[str],
    factory: Callable[[str], Any],
    predicate: Callable[[str, dict[str, Any]], bool],
) -> None:
    """Add entities for devices that appear after setup, without a reload."""

    def _add() -> None:
        if coordinator.data is None:
            return
        new_keys = [
            device_key
            for device_key, device in coordinator.data.devices.items()
            if device_key not in tracked and predicate(device_key, device)
        ]
        if not new_keys:
            return
        tracked.update(new_keys)
        async_add_entities([factory(device_key) for device_key in new_keys])

    entry.async_on_unload(coordinator.async_add_listener(_add))
    _add()
