"""Telegram notification entity backed by the existing bot."""

from __future__ import annotations

from homeassistant.components.notify import NotifyEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .coordinator import EventProcessorCoordinator
from .entity import EventProcessorHubEntity


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    coordinator: EventProcessorCoordinator = entry.runtime_data.coordinator
    async_add_entities([EventProcessorTelegramNotify(coordinator)])


class EventProcessorTelegramNotify(EventProcessorHubEntity, NotifyEntity):
    """Send a message through the Event Processor Telegram bot."""

    _attr_name = "Telegram"

    def __init__(self, coordinator: EventProcessorCoordinator) -> None:
        super().__init__(coordinator, "notify_telegram")

    async def async_send_message(self, message: str, title: str | None = None) -> None:
        await self.coordinator.runtime.client.notify(message)
