"""Alarm panels that arm and disarm a security group."""

from __future__ import annotations

from typing import Any

from homeassistant.components.alarm_control_panel import (
    AlarmControlPanelEntity,
    AlarmControlPanelEntityFeature,
    AlarmControlPanelState,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import DOMAIN
from .coordinator import EventProcessorCoordinator


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    coordinator: EventProcessorCoordinator = entry.runtime_data.coordinator
    if coordinator.data is None:
        return
    entities = []
    for group in coordinator.data.groups:
        if group == "Ring":
            continue
        members = _members(coordinator, group)
        if members:
            entities.append(EventProcessorGroupAlarmPanel(coordinator, group))
    async_add_entities(entities)


def _members(coordinator: EventProcessorCoordinator, group: str) -> list[dict[str, Any]]:
    if coordinator.data is None:
        return []
    return [
        device
        for device in coordinator.data.devices.values()
        if device.get("group_name") == group and "input" in device.get("roles", [])
    ]


class EventProcessorGroupAlarmPanel(
    CoordinatorEntity[EventProcessorCoordinator], AlarmControlPanelEntity
):
    """Arm or disarm every managed input in one security group."""

    _attr_has_entity_name = True
    _attr_name = None
    _attr_code_arm_required = False
    _attr_supported_features = AlarmControlPanelEntityFeature.ARM_AWAY

    def __init__(self, coordinator: EventProcessorCoordinator, group: str) -> None:
        super().__init__(coordinator)
        self.group = group
        self._attr_unique_id = f"group_{group}_alarm"

    @property
    def device_info(self) -> DeviceInfo:
        return DeviceInfo(
            identifiers={(DOMAIN, f"group:{self.group}")},
            name=self.group,
            manufacturer="Event Processor",
            model="Security group",
            via_device_id=self.coordinator.runtime.hub_device_id,
        )

    @property
    def alarm_state(self) -> AlarmControlPanelState:
        members = _members(self.coordinator, self.group)
        enabled = [m for m in members if (m.get("input") or {}).get("device_enabled")]
        if not enabled:
            return AlarmControlPanelState.DISARMED
        if len(enabled) == len(members):
            return AlarmControlPanelState.ARMED_AWAY
        return AlarmControlPanelState.ARMED_HOME

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        members = _members(self.coordinator, self.group)
        return {
            "group_name": self.group,
            "members": [m["device_key"] for m in members],
            "enabled_members": [
                m["device_key"] for m in members if (m.get("input") or {}).get("device_enabled")
            ],
        }

    async def _set_members(self, enabled: bool) -> None:
        for member in _members(self.coordinator, self.group):
            await self.coordinator.runtime.client.set_input_enabled(
                member["device_key"], enabled
            )
        await self.coordinator.async_request_refresh()

    async def async_alarm_arm_away(self, code: str | None = None) -> None:
        await self._set_members(True)

    async def async_alarm_disarm(self, code: str | None = None) -> None:
        await self._set_members(False)
