"""Config and options flows for Event Processor."""

from __future__ import annotations

import logging
from typing import Any

from aiohttp import ClientError
from homeassistant import config_entries
from homeassistant.config_entries import ConfigEntry, OptionsFlowWithReload
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession
import voluptuous as vol

from .client import HubApiError, HubAuthError, HubClient, HubConnectionError
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
    DEFAULT_PORT,
    DEFAULT_SCAN_INTERVAL,
    DOMAIN,
)

_LOGGER = logging.getLogger(__name__)

FORM_SCHEMA = vol.Schema(
    {
        vol.Required(CONF_HOST, default="127.0.0.1"): str,
        vol.Required(CONF_PORT, default=DEFAULT_PORT): vol.All(
            vol.Coerce(int), vol.Range(min=1, max=65535)
        ),
        vol.Required(CONF_TOKEN): str,
    }
)


async def _validate(hass: HomeAssistant, host: str, port: int, token: str) -> dict[str, Any]:
    client = HubClient(async_get_clientsession(hass), host, port, token)
    return await client.get_info()


class EventProcessorConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """Handle initial setup and token reauthentication."""

    VERSION = 1

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> config_entries.ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            try:
                info = await _validate(
                    self.hass,
                    user_input[CONF_HOST],
                    user_input[CONF_PORT],
                    user_input[CONF_TOKEN],
                )
            except HubAuthError:
                errors["base"] = "invalid_auth"
            except HubConnectionError:
                errors["base"] = "cannot_connect"
            except (HubApiError, ClientError):
                errors["base"] = "unknown"
            except Exception:
                _LOGGER.exception("Unexpected error validating the Event Processor hub")
                errors["base"] = "unknown"
            else:
                await self.async_set_unique_id(info["instance_id"])
                self._abort_if_unique_id_configured(
                    updates={
                        CONF_HOST: user_input[CONF_HOST],
                        CONF_PORT: user_input[CONF_PORT],
                    }
                )
                return self.async_create_entry(
                    title="Event Processor",
                    data={
                        CONF_HOST: user_input[CONF_HOST],
                        CONF_PORT: user_input[CONF_PORT],
                        CONF_TOKEN: user_input[CONF_TOKEN],
                    },
                    options={
                        CONF_SCAN_INTERVAL: DEFAULT_SCAN_INTERVAL,
                        CONF_CREATE_EXTERNAL_DEVICES: DEFAULT_CREATE_EXTERNAL_DEVICES,
                        CONF_ENABLE_EVENT_ENTITIES: DEFAULT_ENABLE_EVENT_ENTITIES,
                        CONF_ENABLE_DIAGNOSTIC_ENTITIES: DEFAULT_ENABLE_DIAGNOSTIC_ENTITIES,
                    },
                )

        return self.async_show_form(step_id="user", data_schema=FORM_SCHEMA, errors=errors)

    async def async_step_reauth(
        self, entry_data: dict[str, Any]
    ) -> config_entries.ConfigFlowResult:
        return await self.async_step_reauth_confirm()

    async def async_step_reauth_confirm(
        self, user_input: dict[str, Any] | None = None
    ) -> config_entries.ConfigFlowResult:
        entry = self.hass.config_entries.async_get_entry(self.context["entry_id"])
        assert entry is not None
        errors: dict[str, str] = {}
        if user_input is not None:
            try:
                await _validate(
                    self.hass,
                    entry.data[CONF_HOST],
                    entry.data[CONF_PORT],
                    user_input[CONF_TOKEN],
                )
            except HubAuthError:
                errors["base"] = "invalid_auth"
            except HubConnectionError:
                errors["base"] = "cannot_connect"
            except Exception:
                errors["base"] = "unknown"
            else:
                return self.async_update_reload_and_abort(
                    entry, data_updates={CONF_TOKEN: user_input[CONF_TOKEN]}
                )
        return self.async_show_form(
            step_id="reauth_confirm",
            data_schema=vol.Schema({vol.Required(CONF_TOKEN): str}),
            errors=errors,
        )

    @staticmethod
    def async_get_options_flow(config_entry: ConfigEntry) -> OptionsFlowWithReload:
        return EventProcessorOptionsFlow()


class EventProcessorOptionsFlow(OptionsFlowWithReload):
    """Options for polling, external devices and diagnostic entities."""

    async def async_step_init(
        self, user_input: dict[str, Any] | None = None
    ) -> config_entries.ConfigFlowResult:
        if user_input is not None:
            return self.async_create_entry(data=user_input)

        options = self.config_entry.options
        return self.async_show_form(
            step_id="init",
            data_schema=vol.Schema(
                {
                    vol.Optional(
                        CONF_SCAN_INTERVAL,
                        default=options.get(CONF_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL),
                    ): vol.All(vol.Coerce(int), vol.Range(min=5, max=600)),
                    vol.Optional(
                        CONF_CREATE_EXTERNAL_DEVICES,
                        default=options.get(
                            CONF_CREATE_EXTERNAL_DEVICES, DEFAULT_CREATE_EXTERNAL_DEVICES
                        ),
                    ): bool,
                    vol.Optional(
                        CONF_ENABLE_EVENT_ENTITIES,
                        default=options.get(
                            CONF_ENABLE_EVENT_ENTITIES, DEFAULT_ENABLE_EVENT_ENTITIES
                        ),
                    ): bool,
                    vol.Optional(
                        CONF_ENABLE_DIAGNOSTIC_ENTITIES,
                        default=options.get(
                            CONF_ENABLE_DIAGNOSTIC_ENTITIES, DEFAULT_ENABLE_DIAGNOSTIC_ENTITIES
                        ),
                    ): bool,
                }
            ),
        )

