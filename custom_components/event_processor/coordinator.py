"""Data coordinator and WebSocket bridge for Event Processor."""

from __future__ import annotations

import asyncio
from collections import deque
from contextlib import suppress
from dataclasses import dataclass, field
from datetime import timedelta
import logging
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryAuthFailed
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed

from .client import HubAuthError, HubClient, HubConnectionError
from .const import DEFAULT_SCAN_INTERVAL, DOMAIN

_LOGGER = logging.getLogger(__name__)


@dataclass
class HubData:
    """Snapshot plus the recent event ring buffer."""

    info: dict[str, Any] = field(default_factory=dict)
    devices: dict[str, dict[str, Any]] = field(default_factory=dict)
    links: list[dict[str, Any]] = field(default_factory=list)
    groups: list[str] = field(default_factory=list)
    general: dict[str, Any] = field(default_factory=dict)
    heartbeat: dict[str, Any] | None = None
    events: deque[dict[str, Any]] = field(default_factory=lambda: deque(maxlen=200))


class EventProcessorCoordinator(DataUpdateCoordinator[HubData]):
    """Coordinates REST snapshots and WebSocket push events."""

    def __init__(
        self,
        hass: HomeAssistant,
        client: HubClient,
        scan_interval: int = DEFAULT_SCAN_INTERVAL,
    ) -> None:
        super().__init__(
            hass,
            _LOGGER,
            name=DOMAIN,
            update_interval=timedelta(seconds=scan_interval),
            always_update=True,
        )
        self.client = client
        self.runtime: Any = None
        self._stop = asyncio.Event()
        self._ws_task: asyncio.Task[None] | None = None
        self._fail_count = 0

    async def _async_update_data(self) -> HubData:
        try:
            snapshot = await self.client.get_snapshot()
        except HubAuthError as exc:
            raise ConfigEntryAuthFailed(str(exc)) from exc
        except HubConnectionError as exc:
            raise UpdateFailed(str(exc)) from exc

        data = HubData(
            info=snapshot.get("info", {}),
            devices={device["device_key"]: device for device in snapshot.get("devices", [])},
            links=snapshot.get("links", []),
            groups=snapshot.get("groups", []),
            general=snapshot.get("general", {}),
            heartbeat=snapshot.get("heartbeat"),
        )
        if self.data is not None:
            data.events = self.data.events
        return data

    def async_start(self) -> None:
        """Start the WebSocket listener."""
        if self._ws_task is None or self._ws_task.done():
            self._stop.clear()
            self._ws_task = self.hass.async_create_background_task(
                self._async_ws_loop(), f"{DOMAIN}_events"
            )

    async def async_shutdown(self) -> None:
        """Stop the WebSocket listener."""
        self._stop.set()
        if self._ws_task is not None:
            self._ws_task.cancel()
            with suppress(asyncio.CancelledError):
                await self._ws_task
            self._ws_task = None

    async def _async_ws_loop(self) -> None:
        while not self._stop.is_set():
            try:
                async for event in self.client.ws_events():
                    await self._handle_event(event)
                self._fail_count = 0
            except HubAuthError:
                _LOGGER.error("WebSocket authentication failed; waiting for reauth")
            except HubConnectionError as exc:
                self._fail_count += 1
                delay = min(60, 2**self._fail_count)
                _LOGGER.warning("Hub event stream disconnected: %s; retrying in %ss", exc, delay)
                with suppress(TimeoutError):
                    await asyncio.wait_for(self._stop.wait(), timeout=delay)
            except asyncio.CancelledError:
                return
            except Exception:
                _LOGGER.exception("Unexpected hub event stream failure")
                with suppress(TimeoutError):
                    await asyncio.wait_for(self._stop.wait(), timeout=10)

    async def _handle_event(self, event: dict[str, Any]) -> None:
        event_type = event.get("type")
        if event_type in {"hello", "heartbeat", "hub_started"}:
            return

        if self.data is None:
            return
        self.data.events.append(event)

        device_key = event.get("device_key")
        if not device_key:
            return

        if device_key not in self.data.devices:
            await self.async_request_refresh()
            return

        if event_type in {"device_added", "device_updated", "device_removed", "config_changed"}:
            await self.async_request_refresh()
            return

        device = dict(self.data.devices[device_key])
        state = dict(device.get("state") or {})
        state.update(event.get("data") or {})
        state["last_event"] = event_type
        state["last_event_at"] = event.get("timestamp")
        device["state"] = state

        if event_type == "input_state" and "enabled" in state:
            device["enabled"] = bool(state["enabled"])
        if event_type == "meter_reading" and device.get("meter") is not None:
            meter = dict(device["meter"])
            meter.update(event.get("data") or {})
            device["meter"] = meter

        self.data.devices[device_key] = device
        self.async_set_updated_data(self.data)
