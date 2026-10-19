"""Configuration numbers for device tuning and meter limits."""

from __future__ import annotations

from typing import Any

from homeassistant.components.number import NumberEntity, NumberMode
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity import EntityCategory
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import INPUT_NUMBER_FIELDS, METER_NUMBER_FIELDS, OUTPUT_NUMBER_FIELDS
from .coordinator import EventProcessorCoordinator
from .entity import EventProcessorEntity, is_managed

LIMITS = {
    "trigger_latch_duration": (0, 86400, 1),
    "multi_trigger_rate": (0, 100, 1),
    "multi_trigger_interval": (0, 86400, 1),
    "activation_escalation": (0, 86400, 1),
    "trigger_interval": (0, 86400, 1),
    "meter_low_limit": (0, 1_000_000, 1),
    "meter_high_limit": (0, 1_000_000, 1),
}


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    coordinator: EventProcessorCoordinator = entry.runtime_data.coordinator
    tracked: set[tuple[str, str]] = set()

    def _source_and_name(device: dict, field: str) -> tuple[str, str] | None:
        input_data = device.get("input") or {}
        if input_data and field in INPUT_NUMBER_FIELDS:
            return "input", INPUT_NUMBER_FIELDS[field]
        output_data = device.get("output") or {}
        if output_data and field in OUTPUT_NUMBER_FIELDS:
            return "output", OUTPUT_NUMBER_FIELDS[field]
        meter_data = device.get("meter") or {}
        if meter_data and field in METER_NUMBER_FIELDS:
            return "meter", METER_NUMBER_FIELDS[field]
        return None

    def _add() -> None:
        if coordinator.data is None:
            return
        new_entities: list[EventProcessorConfigNumber] = []
        for device_key, device in coordinator.data.devices.items():
            if not is_managed(coordinator, device):
                continue
            for field in {**INPUT_NUMBER_FIELDS, **OUTPUT_NUMBER_FIELDS, **METER_NUMBER_FIELDS}:
                if (device_key, field) in tracked:
                    continue
                source_and_name = _source_and_name(device, field)
                if source_and_name is None:
                    continue
                source, name = source_and_name
                value = (device.get(source) or {}).get(field)
                if value is None:
                    continue
                tracked.add((device_key, field))
                new_entities.append(
                    EventProcessorConfigNumber(
                        coordinator, device_key, field, name, source
                    )
                )
        if new_entities:
            async_add_entities(new_entities)

    entry.async_on_unload(coordinator.async_add_listener(_add))
    _add()


class EventProcessorConfigNumber(EventProcessorEntity, NumberEntity):
    """A tunable numeric setting stored by the hub."""

    _attr_entity_category = EntityCategory.CONFIG
    _attr_mode = NumberMode.BOX
    _attr_native_step = 1

    def __init__(
        self,
        coordinator: EventProcessorCoordinator,
        device_key: str,
        field: str,
        name: str,
        source: str,
    ) -> None:
        super().__init__(coordinator, device_key, field, name)
        self._field = field
        self._source = source
        minimum, maximum, step = LIMITS[field]
        self._attr_native_min_value = float(minimum)
        self._attr_native_max_value = float(maximum)
        self._attr_native_step = float(step)

    @property
    def _value(self) -> Any:
        return (getattr(self, f"{self._source}_data") or {}).get(self._field)

    @property
    def native_value(self) -> float | None:
        value = self._value
        return float(value) if value is not None else None

    async def async_set_native_value(self, value: float) -> None:
        await self.coordinator.runtime.client.patch_device(
            self.device_key, {self._field: int(value)}
        )
        await self.coordinator.async_request_refresh()
