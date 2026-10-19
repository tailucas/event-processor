"""Small async client for the Event Processor hub API.

The client is vendored with the custom component because this deployment is
private. It only depends on aiohttp, which Home Assistant already provides.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
import json
from typing import Any
from urllib.parse import quote

import aiohttp

REQUEST_TIMEOUT = aiohttp.ClientTimeout(total=20)


class HubError(Exception):
    """Base class for hub client errors."""


class HubAuthError(HubError):
    """The hub rejected the token."""


class HubConnectionError(HubError):
    """The hub could not be reached."""


class HubApiError(HubError):
    """The hub returned an error response."""


class HubClient:
    """Async client for the Event Processor hub API."""

    def __init__(self, session: aiohttp.ClientSession, host: str, port: int, token: str) -> None:
        self._session = session
        self.base_url = f"http://{host}:{port}/api/v1"
        self._token = token

    @property
    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._token}", "Accept": "application/json"}

    async def _request(
        self,
        method: str,
        path: str,
        *,
        json_data: dict[str, Any] | None = None,
    ) -> Any:
        url = f"{self.base_url}{path}"
        try:
            async with self._session.request(
                method,
                url,
                headers=self._headers,
                json=json_data,
                timeout=REQUEST_TIMEOUT,
            ) as response:
                if response.status == 401:
                    raise HubAuthError("Invalid hub API token")
                if response.status >= 400:
                    body = await response.text()
                    raise HubApiError(f"Hub API error {response.status}: {body}")
                if response.content_type == "application/json":
                    return await response.json()
                return await response.text()
        except aiohttp.ClientError as exc:
            raise HubConnectionError(str(exc)) from exc
        except TimeoutError as exc:
            raise HubConnectionError("Timed out talking to the hub") from exc

    @staticmethod
    def _device_path(device_key: str) -> str:
        return quote(device_key, safe="")

    async def get_info(self) -> dict[str, Any]:
        return await self._request("GET", "/info")

    async def get_snapshot(self) -> dict[str, Any]:
        return await self._request("GET", "/snapshot")

    async def patch_device(self, device_key: str, fields: dict[str, Any]) -> dict[str, Any]:
        return await self._request(
            "PATCH", f"/devices/{self._device_path(device_key)}", json_data=fields
        )

    async def set_input_enabled(self, device_key: str, enabled: bool) -> dict[str, Any]:
        return await self._request(
            "POST",
            f"/inputs/{self._device_path(device_key)}/enabled",
            json_data={"enabled": enabled},
        )

    async def trigger_output(
        self, device_key: str, payload: dict[str, Any] | None = None
    ) -> dict[str, Any]:
        return await self._request(
            "POST",
            f"/outputs/{self._device_path(device_key)}/trigger",
            json_data={"payload": payload or {}},
        )

    async def reset_meter(self, device_key: str, value: int | None = None) -> dict[str, Any]:
        return await self._request(
            "POST",
            f"/meters/{self._device_path(device_key)}/reset",
            json_data={"value": value},
        )

    async def set_output_links(
        self, device_key: str, outputs: list[str]
    ) -> dict[str, Any]:
        return await self._request(
            "PUT",
            f"/inputs/{self._device_path(device_key)}/links",
            json_data={"outputs": outputs},
        )

    async def panic(self) -> dict[str, Any]:
        return await self._request("POST", "/panic", json_data={})

    async def notify(
        self, message: str, image_url: str | None = None, chat_id: str | None = None
    ) -> dict[str, Any]:
        return await self._request(
            "POST",
            "/notify",
            json_data={"message": message, "image_url": image_url, "chat_id": chat_id},
        )

    async def ws_events(self) -> AsyncIterator[dict[str, Any]]:
        url = f"{self.base_url}/events"
        try:
            async with self._session.ws_connect(
                url,
                headers=self._headers,
                heartbeat=30,
                timeout=REQUEST_TIMEOUT,
            ) as websocket:
                async for message in websocket:
                    if message.type == aiohttp.WSMsgType.TEXT:
                        yield json.loads(message.data)
                    elif message.type == aiohttp.WSMsgType.ERROR:
                        raise HubConnectionError(f"WebSocket error: {websocket.exception()}")
                    elif message.type in (
                        aiohttp.WSMsgType.CLOSED,
                        aiohttp.WSMsgType.CLOSING,
                    ):
                        break
        except aiohttp.ClientError as exc:
            raise HubConnectionError(str(exc)) from exc
