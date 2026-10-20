"""Authenticated control-plane API for the Home Assistant integration.

This module is deliberately self-contained: it talks to the existing SQLite
database directly and does not import ``app.__main__`` (which would create a
circular import and pull in the whole web application). The existing Flask
dashboard and the legacy ``/api/*`` endpoints remain untouched.

The API is versioned under ``/api/v1`` and is protected by a bearer token. The
token is read from ``HUB_API_TOKEN`` or generated once in
``HUB_API_TOKEN_FILE`` (default ``/data/hub_api_token``) so that both the
Python and Java containers can share it through the mounted ``data`` volume.
"""

from __future__ import annotations

import asyncio
from collections import deque
from collections.abc import AsyncIterator, Iterator
from contextlib import contextmanager, suppress
from datetime import UTC, datetime
import hmac
import logging
import os
from pathlib import Path
import secrets
import sqlite3
import threading
import time
from typing import Any, cast
from urllib.parse import urlencode
from urllib.request import Request, urlopen
import uuid

from fastapi import APIRouter, Depends, Header, HTTPException, Query, WebSocket, WebSocketDisconnect, status
from pydantic import BaseModel, ConfigDict, Field

log = logging.getLogger("event_processor.hub_api")

HUB_API_VERSION = 1
HUB_TOKEN_ENV = "HUB_API_TOKEN"
HUB_TOKEN_FILE_ENV = "HUB_API_TOKEN_FILE"
DEFAULT_TOKEN_FILE = "/data/hub_api_token"

APP_NAME = os.getenv("APP_NAME", "event-processor")
APP_VERSION = os.getenv("APP_VERSION", "0.1.0")
_STARTED_AT = time.time()

_token_lock = threading.Lock()
_cached_token: str | None = None


def _repo_db_path() -> Path:
    return Path(__file__).resolve().parent.parent / "data" / f"{APP_NAME}.db"


def get_db_path() -> Path:
    """Return the SQLite path used by the running event processor."""
    explicit = os.getenv("HUB_DB_PATH")
    if explicit:
        return Path(explicit)
    candidate = Path(os.getenv("TABLESPACE_PATH", "/data")) / f"{APP_NAME}.db"
    if candidate.exists():
        return candidate
    repo_candidate = _repo_db_path()
    if repo_candidate.exists():
        return repo_candidate
    return candidate


def get_token_path() -> Path:
    return Path(os.getenv(HUB_TOKEN_FILE_ENV, DEFAULT_TOKEN_FILE))


def get_hub_token() -> str:
    """Return the shared hub token, generating a stable one if necessary."""
    global _cached_token
    with _token_lock:
        if _cached_token:
            return _cached_token

        configured = os.getenv(HUB_TOKEN_ENV)
        if configured and configured.strip():
            _cached_token = configured.strip()
            return _cached_token

        path = get_token_path()
        try:
            if path.exists():
                token = path.read_text(encoding="utf-8").strip()
                if token:
                    _cached_token = token
                    return token

            token = secrets.token_urlsafe(32)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"{token}\n", encoding="utf-8")
            with suppress(OSError):
                path.chmod(0o600)
            _cached_token = token
            log.warning("Generated hub API token", extra={"token_file": str(path)})
            return token
        except OSError:
            # Last-resort fallback for local development or read-only /data.
            token = secrets.token_urlsafe(32)
            _cached_token = token
            log.warning("Cannot persist hub API token; using an ephemeral token")
            return token


def _extract_bearer(value: str | None) -> str | None:
    if not value:
        return None
    scheme, _, token = value.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        return None
    return token.strip()


def _token_matches(provided: str | None) -> bool:
    expected = get_hub_token()
    if not provided:
        return False
    return hmac.compare_digest(provided, expected)


async def require_token(authorization: str | None = Header(default=None)) -> None:
    """FastAPI dependency that enforces the shared bearer token."""
    if not _token_matches(_extract_bearer(authorization)):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing hub API token",
            headers={"WWW-Authenticate": "Bearer"},
        )


class HubEventBus:
    """Thread-safe fan-out bus for WebSocket and REST event consumers."""

    def __init__(self, maxlen: int = 500) -> None:
        self._loop: asyncio.AbstractEventLoop | None = None
        self._subscribers: set[asyncio.Queue[dict[str, Any]]] = set()
        self._lock = threading.Lock()
        self._seq = 0
        self._recent: deque[dict[str, Any]] = deque(maxlen=maxlen)

    def bind_loop(self, loop: asyncio.AbstractEventLoop) -> None:
        self._loop = loop

    def _next_seq(self) -> int:
        with self._lock:
            self._seq += 1
            return self._seq

    def publish(self, event: dict[str, Any]) -> int:
        payload = dict(event)
        payload.setdefault("timestamp", datetime.now(UTC).isoformat())
        payload["seq"] = self._next_seq()
        with self._lock:
            self._recent.append(payload)

        loop = self._loop
        if loop is not None and not loop.is_closed():
            loop.call_soon_threadsafe(self._deliver, payload)
        else:
            log.debug("Hub event published before event loop bind", extra={"event_type": payload.get("type")})
        return int(payload["seq"])

    def publish_threadsafe(self, event: dict[str, Any]) -> int:
        return self.publish(event)

    def _deliver(self, event: dict[str, Any]) -> None:
        for queue in list(self._subscribers):
            try:
                queue.put_nowait(event)
            except asyncio.QueueFull:
                with suppress(asyncio.QueueEmpty):
                    queue.get_nowait()
                try:
                    queue.put_nowait(event)
                except asyncio.QueueFull:
                    log.warning("Dropping hub event for slow subscriber", extra={"event_type": event.get("type")})

    def subscribe_queue(self, maxsize: int = 100) -> asyncio.Queue[dict[str, Any]]:
        queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=maxsize)
        self._subscribers.add(queue)
        return queue

    def unsubscribe_queue(self, queue: asyncio.Queue[dict[str, Any]]) -> None:
        self._subscribers.discard(queue)

    async def subscribe(self) -> AsyncIterator[dict[str, Any]]:
        queue = self.subscribe_queue()
        try:
            while True:
                yield await queue.get()
        finally:
            self.unsubscribe_queue(queue)

    def recent(self, since: int = 0) -> list[dict[str, Any]]:
        with self._lock:
            return [event for event in self._recent if int(event.get("seq", 0)) > since]

    def stats(self) -> dict[str, Any]:
        with self._lock:
            return {"seq": self._seq, "recent": len(self._recent), "subscribers": len(self._subscribers)}


hub_events = HubEventBus()
_state: dict[str, dict[str, Any]] = {}
_state_lock = threading.Lock()


def update_state(event: dict[str, Any]) -> None:
    """Fold live events into the snapshot state cache."""
    device_key = event.get("device_key")
    if not device_key:
        return
    data = event.get("data") or {}
    with _state_lock:
        current = _state.setdefault(str(device_key), {})
        current["last_event"] = event.get("type")
        current["last_event_at"] = event.get("timestamp")
        match event.get("type"):
            case "input_state":
                current["active"] = data.get("active")
                current["enabled"] = data.get("enabled")
            case "input_triggered":
                current["active"] = True
                current["last_triggered"] = event.get("timestamp")
                current["last_event_detail"] = data.get("event_detail")
            case "output_triggered":
                current["last_triggered"] = event.get("timestamp")
            case "meter_reading":
                current["meter"] = data
            case "device_added" | "device_updated" | "device_removed":
                current["config_changed_at"] = event.get("timestamp")


def get_state(device_key: str) -> dict[str, Any]:
    with _state_lock:
        return dict(_state.get(device_key, {}))



class HubStore:
    """SQLite-backed snapshot and mutation layer for the hub API."""

    INPUT_FIELDS = {
        "device_label",
        "group_name",
        "info_notify",
        "trigger_latch_duration",
        "multi_trigger_rate",
        "multi_trigger_interval",
        "activation_escalation",
        "auto_schedule",
        "auto_schedule_enable",
        "auto_schedule_disable",
        "device_enabled",
    }
    OUTPUT_FIELDS = {
        "device_label",
        "device_enabled",
        "trigger_interval",
        "auto_schedule",
        "auto_schedule_enable",
        "auto_schedule_disable",
    }
    METER_FIELDS = {
        "meter_low_limit",
        "meter_high_limit",
        "meter_reset_value",
        "meter_reset_additive",
        "meter_reading_unit",
        "meter_reading_unit_factor",
        "meter_reading_unit_precision",
    }

    def __init__(self, db_path: str | Path | None = None, timeout: float = 30.0) -> None:
        self.db_path = str(db_path or get_db_path())
        self.timeout = timeout

    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(self.db_path, timeout=self.timeout)
        connection.row_factory = sqlite3.Row
        try:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute(f"PRAGMA busy_timeout={int(self.timeout * 1000)}")
            yield connection
        finally:
            connection.close()

    @staticmethod
    def _row(row: sqlite3.Row | None) -> dict[str, Any] | None:
        return dict(row) if row is not None else None

    def _ensure_instance_id(self, connection: sqlite3.Connection) -> str:
        row = connection.execute(
            "SELECT config_value FROM general_config WHERE config_key = 'instance_id' ORDER BY id LIMIT 1"
        ).fetchone()
        if row and row["config_value"]:
            return str(row["config_value"])
        instance_id = str(uuid.uuid4())
        connection.execute(
            "INSERT INTO general_config (config_key, config_value) VALUES ('instance_id', ?)",
            (instance_id,),
        )
        connection.commit()
        log.info("Created hub instance identifier", extra={"instance_id": instance_id})
        return instance_id

    def info(self) -> dict[str, Any]:
        with self._connect() as connection:
            instance_id = self._ensure_instance_id(connection)
        return {
            "instance_id": instance_id,
            "name": "Event Processor",
            "version": APP_VERSION,
            "api_version": HUB_API_VERSION,
            "uptime_s": round(time.time() - _STARTED_AT, 3),
            "server_time": datetime.now(UTC).isoformat(),
            "capabilities": [
                "snapshot",
                "events",
                "websocket",
                "input_control",
                "output_trigger",
                "meter_reset",
                "output_links",
                "telegram_notify",
            ],
        }

    def instance_id(self) -> str:
        with self._connect() as connection:
            return self._ensure_instance_id(connection)

    @staticmethod
    def _classify_source(device_key: str, input_row: dict[str, Any] | None, output_row: dict[str, Any] | None) -> str:
        if device_key in {"App Panic Button", "App Dash Button", "SMS"}:
            return "virtual"
        if input_row and (
            input_row.get("group_name") == "Ring" or (len(device_key) == 36 and device_key.count("-") == 4)
        ):
            return "ring"
        if input_row and input_row.get("device_type") == "Camera":
            return "camera"
        if output_row and output_row.get("device_type") == "Camera":
            return "camera"
        if input_row and output_row:
            return "multi"
        return "hub"

    @staticmethod
    def _bool(value: Any) -> bool:
        return bool(value) if value is not None else False


    def _load_rows(self, connection: sqlite3.Connection) -> dict[str, Any]:
        link_sql = (
            "SELECT l.id, i.device_key AS input_device_key, o.device_key AS output_device_key "
            "FROM output_link l "
            "JOIN input_config i ON i.id = l.input_device_id "
            "JOIN output_config o ON o.id = l.output_device_id "
            "ORDER BY i.device_key, o.device_key"
        )
        general_sql = (
            "SELECT config_key, config_value FROM general_config "
            "WHERE config_key NOT LIKE 'cloudwatch%' ORDER BY config_key"
        )
        return {
            "inputs": [dict(row) for row in connection.execute("SELECT * FROM input_config ORDER BY device_key")],
            "outputs": [dict(row) for row in connection.execute("SELECT * FROM output_config ORDER BY device_key")],
            "meters": [dict(row) for row in connection.execute("SELECT * FROM meter_config ORDER BY id")],
            "links": [dict(row) for row in connection.execute(link_sql)],
            "general": [dict(row) for row in connection.execute(general_sql)],
            "heartbeat": self._row(connection.execute("SELECT dt, ts FROM heartbeat ORDER BY id LIMIT 1").fetchone()),
        }


    @staticmethod
    def _public_input(row: dict[str, Any]) -> dict[str, Any]:
        return {
            "device_key": row["device_key"],
            "device_type": row["device_type"],
            "device_label": row.get("device_label"),
            "group_name": row.get("group_name"),
            "customized": bool(row.get("customized")),
            "device_enabled": bool(row.get("device_enabled")),
            "info_notify": bool(row.get("info_notify")),
            "trigger_latch_duration": row.get("trigger_latch_duration"),
            "multi_trigger_rate": row.get("multi_trigger_rate"),
            "multi_trigger_interval": row.get("multi_trigger_interval"),
            "activation_escalation": row.get("activation_escalation"),
            "auto_schedule": bool(row.get("auto_schedule")),
            "auto_schedule_enable": row.get("auto_schedule_enable"),
            "auto_schedule_disable": row.get("auto_schedule_disable"),
        }

    @staticmethod
    def _public_output(row: dict[str, Any]) -> dict[str, Any]:
        # device_params and trigger_topic are intentionally not exposed.
        return {
            "device_key": row["device_key"],
            "device_type": row["device_type"],
            "device_label": row.get("device_label"),
            "device_enabled": bool(row.get("device_enabled")),
            "trigger_interval": row.get("trigger_interval"),
            "auto_schedule": bool(row.get("auto_schedule")),
            "auto_schedule_enable": row.get("auto_schedule_enable"),
            "auto_schedule_disable": row.get("auto_schedule_disable"),
        }

    @staticmethod
    def _public_meter(row: dict[str, Any]) -> dict[str, Any]:
        keys = (
            "meter_value",
            "register_value",
            "meter_reading",
            "meter_low_limit",
            "meter_high_limit",
            "meter_reset_value",
            "meter_reset_additive",
            "meter_reading_unit",
            "meter_reading_unit_factor",
            "meter_reading_unit_precision",
        )
        return {key: row.get(key) for key in keys}



    def _build_device(
        self,
        device_key: str,
        input_row: dict[str, Any] | None,
        output_row: dict[str, Any] | None,
        meter_row: dict[str, Any] | None,
    ) -> dict[str, Any]:
        roles = []
        if input_row:
            roles.append("input")
        if output_row:
            roles.append("output")
        enabled = bool(input_row.get("device_enabled")) if input_row else bool(output_row.get("device_enabled"))
        return {
            "device_key": device_key,
            "device_label": (input_row or output_row or {}).get("device_label") or device_key,
            "device_type": (input_row or output_row or {}).get("device_type"),
            "group_name": (input_row or {}).get("group_name"),
            "source": self._classify_source(device_key, input_row, output_row),
            "roles": roles,
            "enabled": enabled,
            "input": self._public_input(input_row) if input_row else None,
            "output": self._public_output(output_row) if output_row else None,
            "meter": self._public_meter(meter_row) if meter_row else None,
            "state": get_state(device_key),
        }

    def snapshot(self) -> dict[str, Any]:
        with self._connect() as connection:
            instance_id = self._ensure_instance_id(connection)
            rows = self._load_rows(connection)

        inputs_by_key = {row["device_key"]: row for row in rows["inputs"]}
        outputs_by_key = {row["device_key"]: row for row in rows["outputs"]}
        meters_by_input_id = {row["input_device_id"]: row for row in rows["meters"]}

        devices = []
        for device_key in sorted(set(inputs_by_key) | set(outputs_by_key)):
            input_row = inputs_by_key.get(device_key)
            meter_row = meters_by_input_id.get(input_row["id"]) if input_row else None
            devices.append(self._build_device(device_key, input_row, outputs_by_key.get(device_key), meter_row))

        return {
            "info": {
                "instance_id": instance_id,
                "name": "Event Processor",
                "version": APP_VERSION,
                "api_version": HUB_API_VERSION,
                "server_time": datetime.now(UTC).isoformat(),
            },
            "devices": devices,
            "groups": sorted({device["group_name"] for device in devices if device.get("group_name")}),
            "links": rows["links"],
            "general": {row["config_key"]: row["config_value"] for row in rows["general"]},
            "heartbeat": rows["heartbeat"],
        }


    def get_device(self, device_key: str) -> dict[str, Any] | None:
        with self._connect() as connection:
            input_row = connection.execute(
                "SELECT * FROM input_config WHERE device_key = ?", (device_key,)
            ).fetchone()
            output_row = connection.execute(
                "SELECT * FROM output_config WHERE device_key = ?", (device_key,)
            ).fetchone()
            meter_row = None
            if input_row:
                meter_row = connection.execute(
                    "SELECT * FROM meter_config WHERE input_device_id = ?", (input_row["id"],)
                ).fetchone()
        if not input_row and not output_row:
            return None
        return self._build_device(
            device_key,
            dict(input_row) if input_row else None,
            dict(output_row) if output_row else None,
            dict(meter_row) if meter_row else None,
        )

    @staticmethod
    def _sql_value(value: Any) -> Any:
        if isinstance(value, bool):
            return 1 if value else 0
        return value

    def update_device(self, device_key: str, fields: dict[str, Any]) -> dict[str, Any] | None:
        with self._connect() as connection:
            input_row = connection.execute(
                "SELECT * FROM input_config WHERE device_key = ?", (device_key,)
            ).fetchone()
            output_row = connection.execute(
                "SELECT * FROM output_config WHERE device_key = ?", (device_key,)
            ).fetchone()
            if not input_row and not output_row:
                return None

            meter_row = None
            if input_row:
                meter_row = connection.execute(
                    "SELECT * FROM meter_config WHERE input_device_id = ?", (input_row["id"],)
                ).fetchone()

            input_updates = {
                key: self._sql_value(value)
                for key, value in fields.items()
                if key in self.INPUT_FIELDS and (input_row is not None)
            }
            output_updates = {
                key: self._sql_value(value)
                for key, value in fields.items()
                if key in self.OUTPUT_FIELDS and (output_row is not None)
            }
            meter_updates = {
                key: self._sql_value(value)
                for key, value in fields.items()
                if key in self.METER_FIELDS and (meter_row is not None)
            }
            if any(key in self.METER_FIELDS for key in fields) and meter_row is None:
                raise ValueError(f"No meter configuration for {device_key}")

            if input_updates:
                assignments = ", ".join(f"{key} = ?" for key in input_updates)
                connection.execute(
                    f"UPDATE input_config SET {assignments} WHERE id = ?",
                    (*input_updates.values(), input_row["id"]),
                )
            if output_updates:
                assignments = ", ".join(f"{key} = ?" for key in output_updates)
                connection.execute(
                    f"UPDATE output_config SET {assignments} WHERE id = ?",
                    (*output_updates.values(), output_row["id"]),
                )
            if meter_updates:
                assert meter_row is not None
                assignments = ", ".join(f"{key} = ?" for key in meter_updates)
                connection.execute(
                    f"UPDATE meter_config SET {assignments} WHERE id = ?",
                    (*meter_updates.values(), int(meter_row["id"])),
                )
            connection.commit()

        return self.get_device(device_key)


    def set_enabled(self, device_key: str, enabled: bool, role: str = "input") -> dict[str, Any] | None:
        table = "input_config" if role == "input" else "output_config"
        with self._connect() as connection:
            row = connection.execute(
                f"SELECT id FROM {table} WHERE device_key = ?", (device_key,)
            ).fetchone()
            if not row:
                return None
            connection.execute(
                f"UPDATE {table} SET device_enabled = ? WHERE id = ?",
                (1 if enabled else 0, row["id"]),
            )
            connection.commit()
        return self.get_device(device_key)

    def reset_meter(self, device_key: str, value: int | None = None) -> dict[str, Any] | None:
        with self._connect() as connection:
            input_row = connection.execute(
                "SELECT id FROM input_config WHERE device_key = ?", (device_key,)
            ).fetchone()
            if not input_row:
                return None
            meter_row = connection.execute(
                "SELECT * FROM meter_config WHERE input_device_id = ?", (input_row["id"],)
            ).fetchone()
            if not meter_row:
                return None

            reset_value = value if value is not None else meter_row["meter_reset_value"]
            if reset_value is None:
                reset_value = 0
            reset_value = int(reset_value)
            if meter_row["meter_reset_additive"]:
                register_value = int(meter_row["register_value"] or 0) + reset_value
            else:
                register_value = reset_value
            register_value = max(register_value, 0)

            factor = int(meter_row["meter_reading_unit_factor"] or 1)
            precision = int(meter_row["meter_reading_unit_precision"] or 0)
            unit = meter_row["meter_reading_unit"] or ""
            reading = f"{register_value / float(factor):.{precision}f}"
            if unit:
                reading = f"{reading} {unit}"

            connection.execute(
                "UPDATE meter_config SET register_value = ?, meter_reading = ? WHERE id = ?",
                (register_value, reading, meter_row["id"]),
            )
            connection.commit()
            updated = dict(
                connection.execute("SELECT * FROM meter_config WHERE id = ?", (meter_row["id"],)).fetchone()
            )
        return self._public_meter(updated)

    def set_links(self, device_key: str, output_keys: list[str]) -> list[dict[str, Any]] | None:
        with self._connect() as connection:
            input_row = connection.execute(
                "SELECT id FROM input_config WHERE device_key = ?", (device_key,)
            ).fetchone()
            if not input_row:
                return None
            output_ids: list[int] = []
            for output_key in output_keys:
                output_row = connection.execute(
                    "SELECT id FROM output_config WHERE device_key = ?", (output_key,)
                ).fetchone()
                if not output_row:
                    raise ValueError(f"Unknown output device: {output_key}")
                output_ids.append(int(output_row["id"]))

            connection.execute(
                "DELETE FROM output_link WHERE input_device_id = ?", (input_row["id"],)
            )
            for output_id in output_ids:
                connection.execute(
                    "INSERT INTO output_link (input_device_id, output_device_id) VALUES (?, ?)",
                    (input_row["id"], output_id),
                )
            connection.commit()

        return cast(list[dict[str, Any]], self.links_for_input(device_key).get("links", []))

    def links_for_input(self, device_key: str) -> dict[str, Any]:
        snapshot = self.snapshot()
        return {"links": [link for link in snapshot["links"] if link["input_device_key"] == device_key]}

    def trigger_output_config(self, device_key: str) -> dict[str, Any] | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM output_config WHERE device_key = ?", (device_key,)
            ).fetchone()
        if row is None:
            return None
        return dict(row)


    def diagnostics(self) -> dict[str, Any]:
        with self._connect() as connection:
            counts = {
                "inputs": connection.execute("SELECT COUNT(*) FROM input_config").fetchone()[0],
                "outputs": connection.execute("SELECT COUNT(*) FROM output_config").fetchone()[0],
                "links": connection.execute("SELECT COUNT(*) FROM output_link").fetchone()[0],
                "meters": connection.execute("SELECT COUNT(*) FROM meter_config").fetchone()[0],
                "events": connection.execute("SELECT COUNT(*) FROM event_log").fetchone()[0],
            }
            orphan_links = connection.execute(
                "SELECT COUNT(*) FROM output_link l "
                "LEFT JOIN input_config i ON i.id = l.input_device_id "
                "LEFT JOIN output_config o ON o.id = l.output_device_id "
                "WHERE i.id IS NULL OR o.id IS NULL"
            ).fetchone()[0]
            heartbeat = connection.execute("SELECT dt, ts FROM heartbeat ORDER BY id LIMIT 1").fetchone()
            instance_id = self._ensure_instance_id(connection)

        heartbeat_age = None
        if heartbeat and heartbeat["ts"]:
            heartbeat_age = round(time.time() - float(heartbeat["ts"]), 3)

        return {
            "instance_id": instance_id,
            "db_path": self.db_path,
            "counts": counts,
            "orphan_links": orphan_links,
            "heartbeat": dict(heartbeat) if heartbeat else None,
            "heartbeat_age_s": heartbeat_age,
            "event_bus": hub_events.stats(),
            "token_file": str(get_token_path()),
            "api_version": HUB_API_VERSION,
            "version": APP_VERSION,
        }


store = HubStore()


def invalidate_java(device_key: str) -> None:
    """Best-effort invalidation of the Java configuration cache."""
    base = os.getenv("EVENT_PROCESSOR_ADDRESS", "").rstrip("/")
    if not base:
        log.debug("No event processor address configured; skipping cache invalidation")
        return
    url = f"{base}/invalidate_config?{urlencode({'device_key': device_key})}"
    try:
        request = Request(url, method="POST")
        with urlopen(request, timeout=2) as response:
            log.debug(
                "Invalidated Java configuration cache",
                extra={"device_key": device_key, "response_code": response.status},
            )
    except Exception as exc:
        log.warning(
            "Unable to invalidate Java configuration cache",
            extra={"device_key": device_key, "url": url},
            exc_info=exc,
        )


def send_telegram(message: str, url: str | None = None, chat_id: str | None = None) -> bool:
    """Send through the existing Telegram bot process (single bot token)."""
    try:
        from tailucas_pylib.handler import exception_handler

        payload: dict[str, Any] = {"device_label": "notification", "message": message}
        if url:
            payload["url"] = url
        if chat_id:
            payload["chat_id"] = chat_id
        with exception_handler(connect_url="inproc://telegram-bot", and_raise=False) as socket:
            socket.send_pyobj(payload)
        return True
    except Exception:
        log.warning("Cannot send Telegram notification", exc_info=True)
        return False


def send_panic() -> bool:
    """Mirror the legacy dashboard panic path into the Python event processor."""
    try:
        from tailucas_pylib.handler import exception_handler

        device_name = os.getenv("DEVICE_NAME", "event-processor")
        active_devices = [
            {"device_key": "App Panic Button", "device_label": "Panic Button", "type": "Button"}
        ]
        with exception_handler(connect_url="inproc://app", and_raise=False) as socket:
            socket.send_pyobj({device_name: {"active_devices": active_devices, "inputs": active_devices}})
        return True
    except Exception:
        log.warning("Cannot send panic event", exc_info=True)
        return False


def _slug(value: str) -> str:
    return "".join(char if char.isalnum() else "_" for char in value.lower()).strip("_")


def publish_output_command(device_key: str, payload: dict[str, Any] | None = None) -> bool:
    """Publish an output command in the same wire format as the Java engine."""
    config = store.trigger_output_config(device_key)
    if config is None:
        log.warning("Cannot trigger unknown output", extra={"device_key": device_key})
        return False

    if (config.get("device_type") or "").lower() == "sms":
        message = (payload or {}).get("message") or "Event Processor notification"
        return send_telegram(str(message), url=(payload or {}).get("url"))

    server_addresses = [
        address.strip()
        for address in os.getenv("RABBITMQ_SERVER_ADDRESS", "").split(",")
        if address.strip()
    ]
    if not server_addresses:
        log.warning("No RabbitMQ address configured; cannot trigger output", extra={"device_key": device_key})
        return False

    try:
        import pika
        import umsgpack
    except ImportError:
        log.warning("RabbitMQ publish dependencies are unavailable", extra={"device_key": device_key})
        return False

    exchange_base = os.getenv("RABBITMQ_EXCHANGE", "home_automation")
    exchange = f"{exchange_base}_control"
    routing_key = str(
        config.get("trigger_topic") or f"event.trigger.{_slug(config.get('device_type') or 'output')}"
    ).lower()
    output_payload = {
        key: config.get(key)
        for key in (
            "device_key",
            "device_type",
            "device_label",
            "device_params",
            "trigger_topic",
            "trigger_interval",
            "device_enabled",
        )
    }
    active_input = {
        "device_key": "Home Assistant",
        "device_label": "Home Assistant",
        "device_type": "ha",
        "type": "ha",
        "message_type": "event",
        "state": "active",
        "timestamp": int(time.time()),
    }
    body = umsgpack.packb(
        {
            "timestamp": int(time.time()),
            "active_input": active_input,
            "output_triggered": output_payload,
            "ha_payload": payload or {},
        }
    )

    connection = None
    try:
        connection = pika.BlockingConnection(
            pika.ConnectionParameters(
                host=server_addresses[0],
                blocked_connection_timeout=2,
                socket_timeout=2,
            )
        )
        channel = connection.channel()
        channel.exchange_declare(exchange=exchange, exchange_type="direct", durable=False)
        channel.basic_publish(
            exchange=exchange,
            routing_key=routing_key,
            body=body,
            properties=pika.BasicProperties(content_type="application/msgpack", expiration="60000"),
        )
        log.info(
            "Published output command",
            extra={
                "device_key": device_key,
                "device_type": config.get("device_type"),
                "exchange": exchange,
                "routing_key": routing_key,
                "payload_bytes": len(body),
            },
        )
        return True
    except Exception:
        log.warning(
            "Cannot publish output command",
            extra={"device_key": device_key, "exchange": exchange, "routing_key": routing_key},
            exc_info=True,
        )
        return False
    finally:
        if connection is not None and connection.is_open:
            connection.close()


class InputEnabledRequest(BaseModel):
    enabled: bool


class TriggerOutputRequest(BaseModel):
    model_config = ConfigDict(extra="allow")
    payload: dict[str, Any] | None = None


class ResetMeterRequest(BaseModel):
    value: int | None = None


class LinksRequest(BaseModel):
    outputs: list[str] = Field(default_factory=list)


class DevicePatchRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    device_label: str | None = None
    group_name: str | None = None
    info_notify: bool | None = None
    trigger_latch_duration: int | None = None
    multi_trigger_rate: int | None = None
    multi_trigger_interval: int | None = None
    activation_escalation: int | None = None
    auto_schedule: bool | None = None
    auto_schedule_enable: str | None = None
    auto_schedule_disable: str | None = None
    device_enabled: bool | None = None
    trigger_interval: int | None = None
    meter_low_limit: int | None = None
    meter_high_limit: int | None = None
    meter_reset_value: int | None = None
    meter_reset_additive: bool | None = None
    meter_reading_unit: str | None = None
    meter_reading_unit_factor: int | None = None
    meter_reading_unit_precision: int | None = None


class NotifyRequest(BaseModel):
    message: str
    title: str | None = None
    chat_id: str | None = None
    image_url: str | None = None
    image_base64: str | None = None


class EventRequest(BaseModel):
    model_config = ConfigDict(extra="allow")

    type: str
    device_key: str | None = None
    timestamp: str | None = None
    data: dict[str, Any] = Field(default_factory=dict)


router = APIRouter(prefix="/api/v1", tags=["hub"])


@router.get("/info", dependencies=[Depends(require_token)])
async def hub_info() -> dict[str, Any]:
    return await asyncio.to_thread(store.info)


@router.get("/snapshot", dependencies=[Depends(require_token)])
async def hub_snapshot() -> dict[str, Any]:
    return await asyncio.to_thread(store.snapshot)


@router.get("/devices", dependencies=[Depends(require_token)])
async def hub_devices() -> list[dict[str, Any]]:
    snapshot = await asyncio.to_thread(store.snapshot)
    return cast(list[dict[str, Any]], snapshot["devices"])


@router.get("/devices/{device_key:path}", dependencies=[Depends(require_token)])
async def hub_device(device_key: str) -> dict[str, Any]:
    device = await asyncio.to_thread(store.get_device, device_key)
    if device is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown device")
    return device


@router.get("/diagnostics", dependencies=[Depends(require_token)])
async def hub_diagnostics() -> dict[str, Any]:
    return await asyncio.to_thread(store.diagnostics)


@router.patch("/devices/{device_key:path}", dependencies=[Depends(require_token)])
async def hub_patch_device(device_key: str, request: DevicePatchRequest) -> dict[str, Any]:
    fields = request.model_dump(exclude_unset=True)
    if not fields:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="No fields supplied")
    try:
        updated = await asyncio.to_thread(store.update_device, device_key, fields)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    if updated is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown device")
    await asyncio.to_thread(invalidate_java, device_key)
    hub_events.publish({"type": "device_updated", "device_key": device_key, "data": {"device": updated}})
    return updated


@router.post("/inputs/{device_key:path}/enabled", dependencies=[Depends(require_token)])
async def hub_set_input_enabled(device_key: str, request: InputEnabledRequest) -> dict[str, Any]:
    updated = await asyncio.to_thread(store.set_enabled, device_key, request.enabled, "input")
    if updated is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown input device")
    await asyncio.to_thread(invalidate_java, device_key)
    hub_events.publish(
        {
            "type": "input_state",
            "device_key": device_key,
            "data": {"enabled": request.enabled, "active": updated.get("state", {}).get("active")},
        }
    )
    return updated


@router.post("/outputs/{device_key:path}/trigger", dependencies=[Depends(require_token)])
async def hub_trigger_output(device_key: str, request: TriggerOutputRequest) -> dict[str, Any]:
    if await asyncio.to_thread(store.trigger_output_config, device_key) is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown output device")
    published = await asyncio.to_thread(publish_output_command, device_key, request.payload)
    if not published:
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail="Unable to publish output command")
    hub_events.publish(
        {
            "type": "output_triggered",
            "device_key": device_key,
            "data": {"requested_by": "home_assistant", "payload": request.payload or {}},
        }
    )
    return {"ok": True, "device_key": device_key}


@router.post("/meters/{device_key:path}/reset", dependencies=[Depends(require_token)])
async def hub_reset_meter(device_key: str, request: ResetMeterRequest) -> dict[str, Any]:
    meter = await asyncio.to_thread(store.reset_meter, device_key, request.value)
    if meter is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown meter device")
    hub_events.publish({"type": "meter_reading", "device_key": device_key, "data": meter})
    return meter


@router.put("/inputs/{device_key:path}/links", dependencies=[Depends(require_token)])
async def hub_set_links(device_key: str, request: LinksRequest) -> dict[str, Any]:
    try:
        links = await asyncio.to_thread(store.set_links, device_key, request.outputs)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    if links is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown input device")
    await asyncio.to_thread(invalidate_java, device_key)
    hub_events.publish({"type": "config_changed", "device_key": device_key, "data": {"links": links}})
    return {"device_key": device_key, "links": links}


@router.post("/panic", dependencies=[Depends(require_token)])
async def hub_panic() -> dict[str, Any]:
    sent = await asyncio.to_thread(send_panic)
    await asyncio.to_thread(send_telegram, "Panic button pressed from Home Assistant")
    hub_events.publish({"type": "panic", "device_key": "App Panic Button", "data": {"sent": sent}})
    return {"ok": sent}


@router.post("/notify", dependencies=[Depends(require_token)])
async def hub_notify(request: NotifyRequest) -> dict[str, Any]:
    sent = await asyncio.to_thread(send_telegram, request.message, request.image_url, request.chat_id)
    if not sent:
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail="Unable to send notification")
    return {"ok": True}


@router.post("/events", dependencies=[Depends(require_token)])
async def hub_ingest_event(request: EventRequest) -> dict[str, Any]:
    event = request.model_dump(exclude_none=True)
    event["data"] = event.get("data") or {}
    update_state(event)
    seq = hub_events.publish(event)
    return {"ok": True, "seq": seq}


@router.get("/events", dependencies=[Depends(require_token)])
async def hub_recent_events(since: int = Query(default=0, ge=0)) -> list[dict[str, Any]]:
    return hub_events.recent(since)


@router.websocket("/events")
async def hub_event_stream(websocket: WebSocket) -> None:
    if not _token_matches(_extract_bearer(websocket.headers.get("authorization"))):
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    await websocket.accept()
    queue = hub_events.subscribe_queue()
    try:
        await websocket.send_json(
            {
                "type": "hello",
                "api_version": HUB_API_VERSION,
                "instance_id": await asyncio.to_thread(store.instance_id),
                "timestamp": datetime.now(UTC).isoformat(),
            }
        )
        while True:
            try:
                event = await asyncio.wait_for(queue.get(), timeout=25)
            except TimeoutError:
                await websocket.send_json({"type": "heartbeat", "timestamp": datetime.now(UTC).isoformat()})
                continue
            await websocket.send_json(event)
    except WebSocketDisconnect:
        log.debug("Hub event WebSocket disconnected")
    except Exception:
        log.warning("Hub event WebSocket failed", exc_info=True)
    finally:
        hub_events.unsubscribe_queue(queue)

