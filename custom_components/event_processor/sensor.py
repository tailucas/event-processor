"""Meter, heartbeat and last-triggered sensors."""

from __future__ import annotations

from datetime import datetime
import time

from homeassistant.components.sensor import (
    SensorDeviceClass,
    SensorEntity,
    SensorStateClass,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity import EntityCategory
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.util import dt as dt_util

from .coordinator import EventProcessorCoordinator
from .entity import (
    EventProcessorEntity,
    EventProcessorHubEntity,
    add_new_entities,
    is_managed,
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    coordinator: EventProcessorCoordinator = entry.runtime_data.coordinator
    runtime = entry.runtime_data
    meter_tracked: set[str] = set()
    triggered_tracked: set[str] = set()

    add_new_entities(
        entry,
        coordinator,
        async_add_entities,
        meter_tracked,
        lambda device_key: EventProcessorMeterSensor(coordinator, device_key),
        lambda device_key, device: is_managed(coordinator, device)
        and device.get("meter") is not None,
    )
    add_new_entities(
        entry,
        coordinator,
        async_add_entities,
        triggered_tracked,
        lambda device_key: EventProcessorLastTriggeredSensor(coordinator, device_key),
        lambda device_key, device: is_managed(coordinator, device)
        and "input" in device.get("roles", [])
        and bool((device.get("state") or {}).get("last_triggered")),
    )

    if runtime.enable_diagnostic_entities:
        async_add_entities(
            [
                EventProcessorHeartbeatAgeSensor(coordinator),
                EventProcessorDeviceCountSensor(coordinator),
                EventProcessorLinkCountSensor(coordinator),
            ]
        )


class EventProcessorMeterSensor(EventProcessorEntity, SensorEntity):
    """Normalized meter reading."""

    _attr_name = "Energy"
    _attr_device_class = SensorDeviceClass.ENERGY
    _attr_state_class = SensorStateClass.TOTAL_INCREASING
    _attr_native_unit_of_measurement = "kWh"

    def __init__(self, coordinator: EventProcessorCoordinator, device_key: str) -> None:
        super().__init__(coordinator, device_key, "energy")

    @property
    def native_value(self) -> float | None:
        meter = (self.device or {}).get("meter") or {}
        value = meter.get("meter_value")
        if value is None:
            reading = meter.get("meter_reading")
            if reading:
                try:
                    return float(str(reading).split()[0])
                except (TypeError, ValueError):
                    return None
        try:
            return float(value)
        except (TypeError, ValueError):
            return None

    @property
    def extra_state_attributes(self) -> dict:
        meter = dict((self.device or {}).get("meter") or {})
        meter.pop("device_params", None)
        return meter


class EventProcessorLastTriggeredSensor(EventProcessorEntity, SensorEntity):
    """Timestamp of the most recent trigger for an input."""

    _attr_name = "Last triggered"
    _attr_device_class = SensorDeviceClass.TIMESTAMP
    _attr_entity_category = EntityCategory.DIAGNOSTIC
    _attr_entity_registry_enabled_default = False

    def __init__(self, coordinator: EventProcessorCoordinator, device_key: str) -> None:
        super().__init__(coordinator, device_key, "last_triggered")

    @property
    def native_value(self) -> datetime | None:
        value = self.state_data.get("last_triggered")
        if not value:
            return None
        return dt_util.parse_datetime(str(value))


class EventProcessorHeartbeatAgeSensor(EventProcessorHubEntity, SensorEntity):
    """Age of the hub heartbeat in seconds."""

    _attr_name = "Heartbeat age"
    _attr_device_class = SensorDeviceClass.DURATION
    _attr_native_unit_of_measurement = "s"
    _attr_state_class = SensorStateClass.MEASUREMENT
    _attr_entity_category = EntityCategory.DIAGNOSTIC

    def __init__(self, coordinator: EventProcessorCoordinator) -> None:
        super().__init__(coordinator, "heartbeat_age", entity_category=EntityCategory.DIAGNOSTIC)

    @property
    def native_value(self) -> float | None:
        heartbeat = self.coordinator.data.heartbeat if self.coordinator.data else None
        if not heartbeat or heartbeat.get("ts") is None:
            return None
        return round(time.time() - float(heartbeat["ts"]), 1)


class EventProcessorDeviceCountSensor(EventProcessorHubEntity, SensorEntity):
    """Number of devices known to the hub."""

    _attr_name = "Devices"
    _attr_entity_category = EntityCategory.DIAGNOSTIC

    def __init__(self, coordinator: EventProcessorCoordinator) -> None:
        super().__init__(coordinator, "device_count", entity_category=EntityCategory.DIAGNOSTIC)

    @property
    def native_value(self) -> int:
        return len(self.coordinator.data.devices) if self.coordinator.data else 0


class EventProcessorLinkCountSensor(EventProcessorHubEntity, SensorEntity):
    """Number of valid input to output links."""

    _attr_name = "Links"
    _attr_entity_category = EntityCategory.DIAGNOSTIC

    def __init__(self, coordinator: EventProcessorCoordinator) -> None:
        super().__init__(coordinator, "link_count", entity_category=EntityCategory.DIAGNOSTIC)

    @property
    def native_value(self) -> int:
        return len(self.coordinator.data.links) if self.coordinator.data else 0
