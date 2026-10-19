"""Event Processor Home Assistant integration."""

from __future__ import annotations

import logging

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.exceptions import ConfigEntryAuthFailed, ConfigEntryNotReady, HomeAssistantError
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers.aiohttp_client import async_get_clientsession
import voluptuous as vol

from .client import HubAuthError, HubClient, HubConnectionError, HubError
from .const import (
    CONF_CREATE_EXTERNAL_DEVICES,
    CONF_ENABLE_DIAGNOSTIC_ENTITIES,
    CONF_ENABLE_EVENT_ENTITIES,
    CONF_HOST,
    CONF_PORT,
    CONF_SCAN_INTERVAL,
    CONF_TOKEN,
    DEFAULT_CREATE_EXTERNAL_DEVICES,
    DEFAULT_ENABLE_DIAGNOSTIC_ENTITIES,
    DEFAULT_ENABLE_EVENT_ENTITIES,
    DEFAULT_SCAN_INTERVAL,
    DOMAIN,
    PLATFORMS,
)
from .coordinator import EventProcessorCoordinator
from .entity import HubRuntimeData

_LOGGER = logging.getLogger(__name__)


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up Event Processor from a config entry."""
    host = entry.data[CONF_HOST]
    port = entry.data[CONF_PORT]
    token = entry.data[CONF_TOKEN]
    client = HubClient(async_get_clientsession(hass), host, port, token)

    try:
        info = await client.get_info()
    except HubAuthError as exc:
        raise ConfigEntryAuthFailed(str(exc)) from exc
    except HubConnectionError as exc:
        raise ConfigEntryNotReady(str(exc)) from exc

    coordinator = EventProcessorCoordinator(
        hass,
        client,
        scan_interval=int(entry.options.get(CONF_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL)),
    )
    await coordinator.async_config_entry_first_refresh()

    hub_device = dr.async_get(hass).async_get_or_create(
        config_entry_id=entry.entry_id,
        identifiers={(DOMAIN, info["instance_id"])},
        name="Event Processor",
        manufacturer="Tailucas",
        model="Event Processor Hub",
        sw_version=info.get("version"),
        configuration_url=f"http://{host}:{port}",
        entry_type=dr.DeviceEntryType.SERVICE,
    )

    runtime = HubRuntimeData(
        client=client,
        coordinator=coordinator,
        hub_device_id=hub_device.id,
        instance_id=info["instance_id"],
        host=host,
        port=port,
        create_external_devices=bool(
            entry.options.get(CONF_CREATE_EXTERNAL_DEVICES, DEFAULT_CREATE_EXTERNAL_DEVICES)
        ),
        enable_event_entities=bool(
            entry.options.get(CONF_ENABLE_EVENT_ENTITIES, DEFAULT_ENABLE_EVENT_ENTITIES)
        ),
        enable_diagnostic_entities=bool(
            entry.options.get(CONF_ENABLE_DIAGNOSTIC_ENTITIES, DEFAULT_ENABLE_DIAGNOSTIC_ENTITIES)
        ),
    )
    coordinator.runtime = runtime
    entry.runtime_data = runtime
    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = runtime

    _async_create_devices(hass, entry, runtime)
    coordinator.async_start()
    await hass.config_entries.async_forward_entry_setups(
        entry, [Platform(platform) for platform in PLATFORMS]
    )
    _async_register_services(hass)
    return True


def _async_create_devices(
    hass: HomeAssistant, entry: ConfigEntry, runtime: HubRuntimeData
) -> None:
    """Create the hub, child and group devices before entity platforms load."""
    registry = dr.async_get(hass)
    data = runtime.coordinator.data
    if data is None:
        return

    for device in data.devices.values():
        if device.get("source") == "ring" and not runtime.create_external_devices:
            continue
        registry.async_get_or_create(
            config_entry_id=entry.entry_id,
            identifiers={(DOMAIN, device["device_key"])},
            name=device.get("device_label") or device["device_key"],
            manufacturer="Event Processor",
            model=device.get("device_type"),
            via_device_id=runtime.hub_device_id,
        )

    for group in data.groups:
        if group == "Ring":
            continue
        if not any(
            device.get("group_name") == group for device in data.devices.values()
        ):
            continue
        registry.async_get_or_create(
            config_entry_id=entry.entry_id,
            identifiers={(DOMAIN, f"group:{group}")},
            name=group,
            manufacturer="Event Processor",
            model="Security group",
            via_device_id=runtime.hub_device_id,
        )


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a config entry."""
    unloaded = await hass.config_entries.async_unload_platforms(
        entry, [Platform(platform) for platform in PLATFORMS]
    )
    if unloaded:
        runtime: HubRuntimeData | None = hass.data.get(DOMAIN, {}).pop(entry.entry_id, None)
        if runtime is not None:
            await runtime.coordinator.async_shutdown()
    return unloaded


async def async_remove_config_entry_device(
    hass: HomeAssistant, entry: ConfigEntry, device_entry: dr.DeviceEntry
) -> bool:
    """Allow the user to remove stale child devices."""
    return (DOMAIN, f"group:{device_entry.name}") not in device_entry.identifiers and not any(
        identifier == (DOMAIN, entry.runtime_data.instance_id)
        for identifier in device_entry.identifiers
    )


async def async_migrate_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Migrate old entry versions."""
    return True


def _get_runtime(hass: HomeAssistant) -> HubRuntimeData:
    runtimes = list(hass.data.get(DOMAIN, {}).values())
    if not runtimes:
        raise HomeAssistantError("Event Processor is not configured")
    return runtimes[0]


def _async_register_services(hass: HomeAssistant) -> None:
    """Register hub-level services once per HA instance."""
    if hass.services.has_service(DOMAIN, "trigger_output"):
        return

    async def _call(call: ServiceCall) -> None:
        runtime = _get_runtime(hass)
        try:
            await runtime.client.trigger_output(
                call.data["device_key"], call.data.get("payload") or {}
            )
        except HubError as exc:
            raise HomeAssistantError(str(exc)) from exc

    async def _set_input_enabled(call: ServiceCall) -> None:
        runtime = _get_runtime(hass)
        try:
            await runtime.client.set_input_enabled(
                call.data["device_key"], bool(call.data["enabled"])
            )
        except HubError as exc:
            raise HomeAssistantError(str(exc)) from exc

    async def _reset_meter(call: ServiceCall) -> None:
        runtime = _get_runtime(hass)
        try:
            await runtime.client.reset_meter(
                call.data["device_key"], call.data.get("value")
            )
        except HubError as exc:
            raise HomeAssistantError(str(exc)) from exc

    async def _set_links(call: ServiceCall) -> None:
        runtime = _get_runtime(hass)
        try:
            await runtime.client.set_output_links(
                call.data["device_key"], list(call.data["outputs"])
            )
        except HubError as exc:
            raise HomeAssistantError(str(exc)) from exc

    async def _set_config(call: ServiceCall) -> None:
        runtime = _get_runtime(hass)
        try:
            await runtime.client.patch_device(
                call.data["device_key"], dict(call.data["config"])
            )
        except HubError as exc:
            raise HomeAssistantError(str(exc)) from exc

    async def _panic(call: ServiceCall) -> None:
        runtime = _get_runtime(hass)
        try:
            await runtime.client.panic()
        except HubError as exc:
            raise HomeAssistantError(str(exc)) from exc

    async def _notify(call: ServiceCall) -> None:
        runtime = _get_runtime(hass)
        try:
            await runtime.client.notify(
                call.data["message"],
                call.data.get("image_url"),
                call.data.get("chat_id"),
            )
        except HubError as exc:
            raise HomeAssistantError(str(exc)) from exc

    hass.services.async_register(
        DOMAIN,
        "trigger_output",
        _call,
        schema=vol.Schema(
            {
                vol.Required("device_key"): cv.string,
                vol.Optional("payload"): dict,
            }
        ),
    )
    hass.services.async_register(
        DOMAIN,
        "set_input_enabled",
        _set_input_enabled,
        schema=vol.Schema(
            {vol.Required("device_key"): cv.string, vol.Required("enabled"): bool}
        ),
    )
    hass.services.async_register(
        DOMAIN,
        "reset_meter",
        _reset_meter,
        schema=vol.Schema(
            {vol.Required("device_key"): cv.string, vol.Optional("value"): int}
        ),
    )
    hass.services.async_register(
        DOMAIN,
        "set_output_links",
        _set_links,
        schema=vol.Schema(
            {
                vol.Required("device_key"): cv.string,
                vol.Required("outputs"): [cv.string],
            }
        ),
    )
    hass.services.async_register(
        DOMAIN,
        "set_device_config",
        _set_config,
        schema=vol.Schema(
            {vol.Required("device_key"): cv.string, vol.Required("config"): dict}
        ),
    )
    hass.services.async_register(DOMAIN, "panic", _panic, schema=vol.Schema({}))
    hass.services.async_register(
        DOMAIN,
        "notify",
        _notify,
        schema=vol.Schema(
            {
                vol.Required("message"): cv.string,
                vol.Optional("image_url"): cv.string,
                vol.Optional("chat_id"): cv.string,
            }
        ),
    )

