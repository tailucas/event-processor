"""Contract tests for the Home Assistant hub API."""

from __future__ import annotations

import importlib
from pathlib import Path
import shutil
from urllib.parse import quote

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest

AUTH = {"Authorization": "Bearer test-token"}


@pytest.fixture()
def hub_api(tmp_path, monkeypatch):
    source = Path(__file__).resolve().parent.parent / "event-processor.db"
    if not source.exists():
        pytest.skip("event-processor.db is not available")
    database = tmp_path / "event-processor.db"
    shutil.copy(source, database)
    monkeypatch.setenv("HUB_DB_PATH", str(database))
    monkeypatch.setenv("HUB_API_TOKEN", "test-token")
    monkeypatch.delenv("EVENT_PROCESSOR_ADDRESS", raising=False)

    import app.hub_api as module

    module = importlib.reload(module)
    module.publish_output_command = lambda *args, **kwargs: True
    module.send_telegram = lambda *args, **kwargs: True
    module.send_panic = lambda *args, **kwargs: True

    api = FastAPI()
    api.include_router(module.router)
    return module, TestClient(api)


def test_info_requires_token(hub_api):
    _, client = hub_api
    assert client.get("/api/v1/info").status_code == 401
    response = client.get("/api/v1/info", headers=AUTH)
    assert response.status_code == 200
    assert response.json()["api_version"] == 1


def test_snapshot_shape(hub_api):
    _, client = hub_api
    response = client.get("/api/v1/snapshot", headers=AUTH)
    assert response.status_code == 200
    snapshot = response.json()
    assert len(snapshot["devices"]) == 43
    assert len(snapshot["links"]) == 71
    assert "Garage Security" in snapshot["groups"]
    meter = next(
        device for device in snapshot["devices"] if device["device_key"] == "Electricity Meter"
    )
    assert meter["meter"]["meter_reading"].endswith("kWh")
    assert "device_params" not in (meter.get("output") or {})


def test_device_controls(hub_api):
    _, client = hub_api
    key = quote("Garage Motion Detector", safe="")
    response = client.patch(
        f"/api/v1/devices/{key}",
        headers=AUTH,
        json={"trigger_latch_duration": 12, "multi_trigger_rate": 2},
    )
    assert response.status_code == 200
    assert response.json()["input"]["trigger_latch_duration"] == 12

    response = client.post(
        f"/api/v1/inputs/{key}/enabled", headers=AUTH, json={"enabled": False}
    )
    assert response.status_code == 200
    assert response.json()["input"]["device_enabled"] is False

    response = client.post(
        f"/api/v1/meters/{quote('Electricity Meter', safe='')}/reset",
        headers=AUTH,
        json={"value": 5},
    )
    assert response.status_code == 200
    assert response.json()["register_value"] == 5

    response = client.put(
        f"/api/v1/inputs/{key}/links",
        headers=AUTH,
        json={"outputs": ["SMS", "Garage Camera"]},
    )
    assert response.status_code == 200
    assert len(response.json()["links"]) == 2


def test_event_ingest_and_recent(hub_api):
    _, client = hub_api
    response = client.post(
        "/api/v1/events",
        headers=AUTH,
        json={
            "type": "input_triggered",
            "device_key": "Garage Motion Detector",
            "data": {"event_detail": "motion"},
        },
    )
    assert response.status_code == 200
    seq = response.json()["seq"]
    assert seq >= 1

    response = client.get("/api/v1/events", headers=AUTH)
    assert response.status_code == 200
    events = response.json()
    assert any(event["seq"] == seq for event in events)

    response = client.get("/api/v1/diagnostics", headers=AUTH)
    assert response.status_code == 200
    assert response.json()["counts"]["inputs"] == 39
