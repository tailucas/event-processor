---
paths:
  - "custom_components/**"
  - "app/hub_api.py"
  - "deploy_ha.sh"
  - "deploy_hub.sh"
  - "src/main/java/tailucas/app/provider/HubEventPublisher.java"
---

# Home Assistant Integration & Migration Standard

This is the reference document for the Event Processor → Home Assistant
migration. It records the HA research, the live deployment facts, the phase-1
implementation, the discovered project behavior, the caveats, and the planned
route from the Flask control surface to an end-state where Home Assistant is
the primary runtime configuration surface.

It is intentionally verbosity-heavy. Future agents should read this file
before changing the HA component, the hub API, the Java event publisher, or
any Flask dashboard behavior that is being migrated.

---

## 1. Status at a glance

| Item | State |
|---|---|
| Architecture | **Hybrid** (chosen): HA is the UI/control surface; the Java engine remains the real-time rule engine |
| Flask dashboard | Still present and working (`/admin/`); phase-1 fallback |
| Hub API | Implemented at `/api/v1` (HTTP + WebSocket), bearer-token auth |
| Java event publisher | Implemented; publishes processed lifecycle events to the hub API |
| HA integration | Implemented as a **private, core-grade custom component** in `custom_components/event_processor` |
| Distribution | No HACS, no PyPI, no HA core PR (private configuration) |
| Production HA | Home Assistant **2026.10.2** in a container on `acer` (192.168.0.6), host network |
| Config entry | Created and loaded: `01M58D47AR49TRCHMG0K1VPNNE` |
| Hub instance ID | `23b94fd5-d005-498d-b1d9-68c2af0787b6` |
| Hub devices | 43 unique devices in the snapshot (39 inputs, 12 outputs; 8 cameras are dual-role) |
| HA-owned Event Processor devices | 32 (26 managed child devices + 5 group devices + 1 hub device) |
| HA-owned entities | 120 (5 alarm panels, 34 switches, 25 numbers, 20 binary sensors, 18 event entities, 13 buttons, 4 sensors, 1 notify) |
| Ring devices | Not duplicated; 17 `source=ring` devices skipped in HA |
| Links | 71 valid links retained in the hub in phase 1; 7 orphan rows exist in SQLite |
| Camera/link orchestration | Assessed; per-link HA ownership is the recommended next step for non-Ring camera links; see §11 |
| Event history | 56,347 legacy rows; HA Logbook/History is the new event surface |
| Production DB | `/home/tai/event-processor/data/event-processor.db`, now owned `999:999` |
| DB backup | `/data/event-processor.db.bak-20261018204347` |
| Hub token | `/data/hub_api_token`, mode 600, owned `app`; print with `make hub-token` |
| Hub address from HA | `http://127.0.0.1:8095` (HA uses host networking) |
| Hub address from LAN | `http://192.168.0.6:8095` |

### Dependency and coverage at a glance

| Question | Current answer |
|---|---|
| Is SQLite still required? | **Yes.** Java reads 39 input configs, 12 output configs, 71 valid links and 1 meter config from SQLite through the Python config API. HA is not the runtime source of truth. |
| How much of the hub device identity is in HA? | **26 of 43 unique hub devices (60.5%)** are mirrored; **26/26 = 100% of non-Ring devices**, **0/17 = 0% of Ring devices**. HA has 32 devices total = 26 child + 5 group + 1 hub. |
| How much input/output config is mirrored? | **22/39 input identities and armed states (56%)**; **12/12 output identities and enabled gates (100%)**; tuning is mirrored for the non-Ring subset (25 `number` entities total across input/output/meter). |
| How much linkage is in HA? | **0 of 71 valid links (0%)**. 46 links (65%) originate from non-Ring inputs; 25 (35%) from Ring inputs. All routing is still SQLite/Java. |
| How much schedule/history is in HA? | **0% of schedules** (0 input schedules active; 2 output schedules active); **0 of 56,347 legacy event rows** migrated (new events only in HA Logbook). |
| How much of the DB is removable? | **99.8% of rows are legacy/dead** (56,361 of 56,487 in the reference snapshot), but only ~14 rows are removable now. About **90% of active config is still SQLite-owned**. |

### What is deliberately still hub-owned

The Java engine remains authoritative for:

- input activation detection and device-type-specific `wouldTriggerOutput`
- heartbeat processing and trigger reset
- trigger latch / debounce (`trigger_latch_duration`)
- multi-trigger filtering (`multi_trigger_rate` + `multi_trigger_interval`)
- prolonged-activation escalation (`activation_escalation`) and PagerDuty
- meter threshold evaluation (`meter_low_limit` / `meter_high_limit`)
- output trigger rate limiting (`trigger_interval`)
- the actual output command publish to RabbitMQ
- the existing motion → camera snapshot → Telegram linkage

The Python app remains the config/control plane in phase 1. HA is the primary
UI and device/entity registry, but the SQLite database is still the runtime
source of truth. This is intentional: a security system must keep operating if
HA is restarting or unavailable. See §7.8 for the quantified dependency,
coverage and replacement assessment.

---

## 2. Production environment facts

- HA version: **2026.10.2** (`/home/tai/home-assistant/config/.HA_VERSION`).
- Installation: Docker container named `homeassistant`
  (`ghcr.io/home-assistant/home-assistant:stable`), **`network_mode: host`**.
- Config mount: `/home/tai/home-assistant/config` on `acer` → `/config` in the
  container.
- Custom component path:
  `/home/tai/home-assistant/config/custom_components/event_processor`.
- Restart command: `docker restart homeassistant` (or `make ha-deploy`).
- Existing integrations at first setup: `sun`, `bluetooth`, `analytics`,
  `go2rtc`, `backup`, `cast`, `shopping_list`, `radio_browser`,
  `google_translate`, `met`, `mobile_app`.
- **No MQTT integration and no Ring integration were configured** at the time
  of phase-1 deployment. `go2rtc` is present and is the intended live-camera
  path.
- Event processor container: `event-processor-app-1`, bridge network
  `event-processor_default`, ports `8095:8080` (Python API), `8085:8085`
  (metrics), `9400:9400`, `4041:4040`.
- The event processor reaches host services via `host.docker.internal`; HA uses
  host networking, so it reaches the hub at `127.0.0.1:8095`.
- RabbitMQ and Mosquitto run on `acer`; MQTT `1883`, RabbitMQ `5672`,
  management `15672`. `ring-mqtt` is also running on `acer`.
- 1Password Connect API is published on `8082`; the correct container host is
  `http://host.docker.internal:8082` (or `http://acer.discus-universe.ts.net:8082`).
- HA login used for the config flow: local provider, user `tai` (admin).
- The HA config entry is loaded and one WebSocket subscriber is active; the
  hub has been observed publishing hundreds of Java `input_state` events.

### Important production fix

The mounted SQLite DB was owned by UID 1000 while the container runs as `app`
UID 999. The first new device-discovery insert failed with:

```
sqlite3.OperationalError: attempt to write a readonly database
```

That exception is raised inside `EventProcessor.run` with
`shutdown_on_error=True`, so the nanny shut the entire Python app down and
supervisord crash-looped it. The fix was:

```bash
docker exec -u root event-processor-app-1 \
  chown 999:999 /data/event-processor.db /data/event-processor.db-wal \
  /data/event-processor.db-shm /data/hub_api_token
```

Any future data copy/restore must preserve UID/GID 999 ownership or the same
failure will recur. See `container.md`.

---

## 3. Why the hybrid architecture

The project has two distinct responsibilities:

1. **Real-time security processing** (Java): device events, latching,
   multi-trigger, escalation, meter thresholds, output rate limits, output
   commands. This must run even if HA is down.
2. **Configuration and human control** (Python/Flask): device inventory,
   arming, schedules, links, meter reset, event log, Telegram bot. This is the
   part HA should replace.

A full "move all links and schedules into HA automations" rewrite in phase 1
would make HA part of the security path and risk changing behavior. The hybrid
model instead:

- makes HA the **primary UI and registry** immediately;
- keeps the hub authoritative for runtime behavior;
- exposes HA-native entities and services for every control;
- migrates links/schedules to HA only after parity and with an explicit
  `LINKS_SOURCE` cutover;
- keeps Flask available as a rollback path until phase 3.

This is the strangler-fig migration: build the HA surface alongside the old
surface, move usage, then remove the old surface.

---

## 4. Home Assistant concepts an agent must know

### 4.1 Integration types

`manifest.json` `integration_type` values relevant here:

- **`hub`** — a gateway to many child devices. This integration is a hub.
- `device` — one physical device.
- `service` — a cloud/local service.
- `helper` / `entity` / `system` — not applicable.

`iot_class` for this integration is **`local_push`** because the primary data
path is a local WebSocket push with a safety poll. Other values:
`local_polling`, `cloud_polling`, `cloud_push`, `assumed_state`.

### 4.2 File structure

Core integrations live in `homeassistant/components/<domain>/`; custom
integrations live in `<config>/custom_components/<domain>/`. The folder name
must equal the manifest `domain`. Required files: `manifest.json` and
`__init__.py`. Conventional files:

```
__init__.py          setup/unload, services, runtime data
manifest.json        domain, version, config_flow, requirements, iot_class
const.py             DOMAIN and constants
config_flow.py       config + options + reauth/reconfigure
coordinator.py       DataUpdateCoordinator / push bridge
entity.py            shared base entity + DeviceInfo
binary_sensor.py     one module per platform
event.py
switch.py
button.py
sensor.py
number.py
alarm_control_panel.py
notify.py
services.yaml        action descriptions for the UI
diagnostics.py       redacted diagnostics download
repairs.py           actionable repair issues (optional)
strings.json         English UI strings
translations/en.json translated strings for custom integrations
quality_scale.yaml   per-rule quality status
brand/               custom-integration icon/logo (optional)
```

Tests live outside the integration folder in core
(`tests/components/<domain>/`); a custom component in this private repo uses
`tests/` or a separate HA test venv.

### 4.3 Manifest keys used here

```json
{
  "domain": "event_processor",
  "name": "Event Processor",
  "version": "0.1.0",
  "config_flow": true,
  "documentation": "https://github.com/tailucas/event-processor",
  "issue_tracker": "https://github.com/tailucas/event-processor/issues",
  "codeowners": ["@tailucas"],
  "iot_class": "local_push",
  "integration_type": "hub",
  "requirements": [],
  "dependencies": [],
  "quality_scale": "bronze",
  "loggers": ["custom_components.event_processor"]
}
```

Notes:

- `version` is required for custom integrations.
- `requirements` must be PyPI package specs if used. This private deployment
  vendors `client.py` and uses Home Assistant's existing `aiohttp`, so
  `requirements` is empty. A public/core version would need a PyPI client
  library.
- `dependencies` would list other integrations that must load first. This
  integration deliberately has none.
- `zeroconf`/`dhcp`/`ssdp` discovery keys would be used if the hub advertised
  itself. Phase 1 uses manual host/port/token; mDNS is a later enhancement.
- `quality_scale` is optional for custom integrations; core requires Bronze.

### 4.4 Config flow, options flow, subentries

- A config flow is mandatory for UI-configured integrations.
- `async_step_user` collects host/port/token, validates the hub, sets a stable
  `unique_id`, and calls `_abort_if_unique_id_configured`.
- The unique ID is the hub `instance_id`, not the host/port, so changing the
  host does not create a duplicate entry.
- `reauth` handles a rejected token; `reconfigure` handles host/port changes.
- `OptionsFlowWithReload` is the modern base for options; changing options
  reloads the entry.
- **Subentries** are independently add/change/remove parts of a config entry.
  They are the right future home for per-device settings if the options flow
  becomes too large.
- Config entry `data` = connection details; `options` = behavior; `runtime_data`
  = live objects; `Store` = integration-private cache only.

### 4.5 Coordinator and push

`DataUpdateCoordinator[HubData]` is appropriate here because:

- HA needs one initial snapshot;
- the hub pushes events over WebSocket;
- a slow safety poll (30 s) repairs missed events/reconnects;
- all entities can be `CoordinatorEntity` subclasses.

Rules:

- `always_update=True` when mutating the coordinator data in place.
- `async_config_entry_first_refresh()` during setup; `ConfigEntryNotReady` when
  the hub is offline, `ConfigEntryAuthFailed` on 401.
- `entry.runtime_data = HubRuntimeData(...)`; `hass.data[DOMAIN][entry_id]` for
  services.
- `async_forward_entry_setups(entry, PLATFORMS)` and
  `async_unload_platforms(...)`.
- WebSocket reconnect with exponential backoff; mark entities unavailable after
  a threshold.

### 4.6 Device registry and duplicate devices

`DeviceInfo` fields available in HA 2026.10:

```
configuration_url, connections, entry_type, identifiers, manufacturer,
model, model_id, name, serial_number, suggested_area, sw_version,
hw_version, translation_key, translation_placeholders, via_device_id
```

Rules:

- `identifiers` are scoped to the integration domain. A physical device may
  appear in multiple integrations as **separate device entries**; that is
  expected. Do not try to attach entities to another integration's device.
- `via_device_id` is the current field (not the older `via_device` tuple) for
  "this device is behind a hub".
- The hub device uses `entry_type=DeviceEntryType.SERVICE`.
- Child devices use `identifiers={(DOMAIN, device_key)}` and
  `via_device_id=hub_device.id`.
- User-owned fields (`name_by_user`, `area_id`, `labels`, `disabled_by`) are
  owned by the user, not overwritten by the integration.
- The device registry is **not** a general config store. Arbitrary per-device
  settings belong in config entry options/subentries or config entities.

### 4.7 Areas, labels, groups

- **Areas** are physical locations (room/floor). Do not use them for logical
  security groups.
- **Labels** are logical groupings and can be used as action targets. Use
  labels such as `security_group:garage` for the logical groups.
- Group devices in this project are virtual devices (`group:<name>`) hosting
  one `alarm_control_panel`; they are not physical device duplicates.
- `suggested_area` is only a suggestion; the user owns the final area.

### 4.8 Enabled/disabled semantics

- **Arm/disarm** is a runtime control: a `switch` or
  `alarm_control_panel`, never `disabled_by`.
- **Output enabled** is a configuration gate: a `switch` with
  `entity_category=CONFIG` (or an option).
- **Hiding** a noisy/irrelevant entity uses
  `entity_registry_enabled_default=False`; a user-disabled device uses
  `disabled_by` in the registry.
- Do not conflate these three.

### 4.9 Discovery, dynamic devices, repairs

- HA has no generic "unknown event creates a device" primitive.
- The quality-scale `dynamic-devices` rule expects integrations to add devices
  after setup without a reload: keep a `known_devices` set, add a coordinator
  listener, call `async_add_entities` for the delta.
- Use `device_registry.async_get_or_create(config_entry_id=..., ...)` for child
  devices.
- A config flow started from code uses
  `homeassistant.helpers.discovery_flow.async_create_flow(...)` and
  `async_step_integration_discovery`; this is for devices that need their own
  entry/credentials.
- Repairs are for problems that need user attention, not for "new hardware".
- Unknown/transient devices should be a diagnostic event or a repair, not a
  registry entry.
- Ring UUID devices are skipped here because the HA Ring integration (when
  configured) should own them; the event_processor integration should not
  duplicate them.

### 4.10 Services / actions

- Entity-targeted actions use
  `homeassistant.helpers.service.async_register_platform_entity_service`.
- Hub-level actions use `hass.services.async_register` with a voluptuous
  schema and a `services.yaml` description.
- Actions must raise `HomeAssistantError` on user-visible failure; do not swallow
  errors.
- Device automations are effectively frozen for new integrations; use event
  entities, state triggers and service actions instead.
- An integration cannot create HA automations or `schedule` helpers
  programmatically through a supported API. Ship primitives, blueprints and
  importable YAML instead.

### 4.11 Quality scale, HACS, testing

- Core integrations must meet at least Bronze quality scale and include
  `quality_scale.yaml`.
- Custom integrations are not forced to, but the same rules are a good
  discipline. This private component declares `quality_scale: bronze` and
  tracks `config-flow-test-coverage` as `todo`.
- HACS is becoming the **Marketplace** in HA 2026.11. This private deployment
  does not use HACS; `make ha-deploy` copies the component directly.
- A core submission would require open-sourcing, a PyPI client library, tests
  under `tests/components/<domain>/`, translations, brand assets and a public
  PR. That is intentionally not the path here.
- `pytest-homeassistant-custom-component` is the test harness for HA
  integration tests; use a separate HA-compatible venv because HA pins its own
  dependencies.

### 4.12 HA 2026.10-specific discoveries

Verified against the running 2026.10.2 container:

- `DeviceInfo` has `via_device_id: str`, not `via_device`.
- `ConfigEntry.runtime_data` is an annotation/instance attribute; assign
  `entry.runtime_data = ...`.
- `OptionsFlowWithReload` exists and should be used for options.
- `EventEntity._trigger_event(event_type: str, event_attributes: dict | None)`.
- `EventDeviceClass` values are `doorbell`, `button`, `motion`. If an event
  entity has `_attr_name=None` and `device_class=BUTTON`, HA can append
  "Button" to the friendly name; give the entity an explicit name such as
  `"Pressed"` to avoid "Dash Button Button".
- `EntityCategory` is imported from `homeassistant.helpers.entity`; values are
  `config`, `diagnostic`.
- `AlarmControlPanelEntity` uses the `alarm_state` property and
  `async_alarm_arm_away` / `async_alarm_disarm`.
- `NotifyEntity.async_send_message(message, title=None)`.
- **Config flows are exposed over REST in 2026.10**: `POST
  /api/config/config_entries/flow` with `{"handler": "<domain>"}`, then `POST
  /api/config/config_entries/flow/{flow_id}` with the user input. The old
  WebSocket command `config_entries/flow` returns `unknown_command`.
- The config flow REST endpoints require an admin token and are implemented in
  `homeassistant/components/config/config_entries.py` +
  `homeassistant/helpers/data_entry_flow.py`.
- Device registry storage v3 (HA 2026.8) tightened multi-integration device
  ownership and added child devices; one config entry per device is the safe
  model.

---

## 5. Project discoveries (what the HA integration is replacing)

### 5.1 Current architecture

```
Devices / sensors / ioboard / Ring
        │
        ├── RabbitMQ topic exchange "home_automation" (MessagePack State)
        └── MQTT broker (JSON, ring/#, meter/#, sensor/#, homeassistant/#)
                        │
                        ▼
        Java EventProcessor + Event.run()
        ├── identity resolution
        ├── heartbeat → DeviceConfig.postDeviceInfo()
        ├── config fetch → Python /api/input_config, /api/output_config,
        │                  /api/meter_config, /api/output_link
        ├── latch / multi-trigger / escalation / meter thresholds
        ├── output rate limit
        └── publish output command to RabbitMQ event.trigger.*
                        │
                        ▼
        Python Flask/FastAPI (SQLite)
        ├── dashboard + config CRUD
        ├── config APIs consumed by Java
        ├── /api/device_info → ZMQ → EventProcessor → INSERT disabled rows
        ├── AutoScheduler (per-device enable/disable times)
        ├── Telegram bot
        └── event log page (read-only, historical)
```

**Critical correction to the mental model:** the Python `EventProcessor` does
**not** route input events to outputs. The Java `Event.run()` does. Python's
`EventProcessor` consumes control messages on `inproc://app`
(`device_info_input`, `device_info_output`, `auto-scheduler`, `bot`,
`output_triggered`, `active_input`, SMS-related messages) and forwards
notifications. This is why the HA integration must integrate with the Java
engine's lifecycle, not just replace Flask forms.

### 5.2 Java engine responsibilities

- `Event.java:162-620` — full event pipeline.
- `Event.java:223-229` — heartbeat → `DeviceConfig.postDeviceInfo`.
- `Event.java:294` — config fetch.
- `Event.java:349-381` — latch and multi-trigger filters.
- `Event.java:405-511` — linked output resolution and RabbitMQ publish.
- `Event.java:549-607` — escalation/PagerDuty.
- `EventProcessor.java:91` — `FEATURE_FLAG_HA_DISCOVERY`.
- `EventProcessor.java:431-454` — **bug**: publishes
  `homeassistant/status=online`; HA publishes that topic, clients subscribe.
  It does not create HA entities.
- `DeviceConfig.java:192-276` — config fetch/cache (1 hour, including 404s).
- `DeviceConfig.java:279-349` — `postDeviceInfo` to Python.
- `DeviceConfig.java:86-94` — HA discovery cache used by Ring.
- `Device.java:194-221` — type mapping; unknown types throw.
- `Camera.java:31-33` — camera is both input and output.
- `Ring.java:180-235`, `383-389` — Ring identity/roles.
- `Mqtt.java:59-160` — MQTT ingress and `homeassistant/#` cache.
- `RabbitMq.java:46-103` — RabbitMQ ingress.

### 5.3 Lazy device discovery — the exact current sequence

```
Device heartbeat
  → RabbitMQ/MQTT
  → Java Event.run()
      isHeartbeatEvent() == true                          Event.java:223
      → DeviceConfig.postDeviceInfo(device)                Event.java:229
          → POST http://CONFIG_HOST:CONFIG_PORT/api/device_info
            body: Generic JSON (device_key, device_label, device_type,
                  group_name, location, is_input, is_output, event_detail,
                  message_type)                            DeviceConfig.java:279-349
  → Python FastAPI api_device_info()                       app/__main__.py:737
      if is_input  → ZMQ {"device_info_input": ...}
      if is_output → ZMQ {"device_info_output": ...}
  → Python EventProcessor.run()                            app/__main__.py:1727-1790
      device_info_input  → INSERT input_config  (device_enabled=None)
      device_info_output → INSERT output_config (device_enabled=None)
  → (was missing) invalidate_remote_config(device_key)
  → Java next event: GET /api/input_config?device_key=...
      cache: 1 hour, including 404s                        DeviceConfig.java:192-270
  → User must open Flask and enable the device
```

Problems with this as an HA discovery model:

1. It only happens on heartbeat; event-only devices may never be discovered.
2. New rows are disabled (`device_enabled=None`; Java treats null as false).
3. The old insert did **not** invalidate the Java cache; a 404 could persist
   for an hour. Phase 1 now calls `invalidate_remote_config` after insert.
4. Any unknown key creates a row; transient UUIDs/misconfigured topics can
   pollute the table.
5. `/api/device_info` was unauthenticated and returned `"OK"` even when the ZMQ
   send failed; drops were only visible in OTEL/logs.
6. Device type is not validated/normalized across sources.

HA reconciliation: the hub should classify a stable device announcement and
emit `device_added`; HA creates the device/entities without a reload. Unknown
or transient keys should be diagnostic events, not registry rows. Do not
auto-arm a discovered device; visibility is not the same as arming.

### 5.4 Flask dashboard function inventory (all discovered entry points)

FastAPI endpoints (machine-facing):

| Route / function | Lines | Purpose | HA replacement |
|---|---|---|---|
| `GET /` `api_root` | 628-630 | Redirect to `/admin/` | HA UI |
| `GET /api/ping` `api_ping` | 633-635 | Liveness probe | Hub health |
| `GET /api/running` `api_running` | 638-640 | Readiness; Java polls it | Hub `/api/v1/info` |
| `POST /api/device_info` `api_device_info` | 737-771 | Java heartbeat announcement → ZMQ; inserts disabled rows | `device_added` event → HA |
| `GET /api/input_configs` `api_input_configs` | 1079-1090 | Async single-input config; unused | Hub snapshot |
| `GET /api/input_config` `api_input_config` | 1093-1110 | Input config for Java | Hub `/api/v1/devices` |
| `GET /api/meter_config` `api_meter_config` | 1113-1127 | Meter config; **bug:** filters `device_label` not `device_key` | Hub meter endpoint |
| `GET /api/output_link` `api_output_link` | 1292-1307 | Output links for Java | Hub links/action |
| `GET /api/output_config` `api_output_config` | 1357-1374 | Output config for Java | Hub snapshot |

Flask pages/routes (human-facing):

| Route / function | Lines | Purpose | HA replacement |
|---|---|---|---|
| `GET/POST /` `index` | 892-993 | Control panel: list inputs, toggle `device_enabled`, group rollup, meter reset, panic, badges. **No auth decorator.** | Lovelace dashboard, alarm panels, switches, buttons |
| `GET/POST /metrics` `show_metrics` | 996-998 | Redirect to Grafana | HA History/Energy + optional Grafana |
| `GET/POST /event_log` `event_log` | 1001-1006 | Last 100 `EventLog` rows | HA Logbook/History |
| `GET/POST /config` `show_config` | 1009-1076 | Global auto-scheduler + per-device clockpicker. Bug: `bool(request.form.get(...))` treats `"False"` as true; unguarded time fields. | Integration options + `time`/`schedule` |
| `GET/POST /input_config` `input_config` | 1130-1245 | Input tuning: info_notify, group_name, latch, multi-trigger, escalation, meter fields | Device page CONFIG entities/subentry |
| `GET/POST /input_link` `input_link` | 1247-1289 | Input↔input links; template says “not implemented” | Drop |
| `GET/POST /output_config` `output_config` | 1377-1410 | Output enabled/params/trigger_topic/interval | CONFIG entities; secrets stay out of HA |
| `GET/POST /output_link` `output_link` | 1310-1354 | Input→output link matrix | `set_output_links` action; later HA automations |
| `GET /login`, `GET /logout`, `POST /login`, `load_user` | 827-870 | Flask-Login, one 1Password user | HA users/auth |
| `GET /debug-sentry` `trigger_error` | 873-875 | Deliberate error | Remove |
| `GET /logging` `debug` | 878-881 | Set log level from query | HA `logger` service |
| 500 handler | 884-889 | Error page + Sentry | HA diagnostics/logs |

Support/background classes:

| Function/class | Lines | Purpose | HA replacement |
|---|---|---|---|
| `SessionUser`, `auth_enabled`, `authz_required`, `load_user` | 774-870 | Auth/session/authorization stub (`permitted=True`) | HA auth |
| `DeviceInfo`, `BotMessage` | 643-652, 1510-1519 | Pydantic payloads | Client models / notify |
| `GeneralConfig`, `Heartbeat`, `EventLog`, `InputConfig`, `MeterConfig`, `OutputConfig`, `InputLink`, `OutputLink` | 284-519 | SQLAlchemy models | Hub store; HA registry/entities |
| `InputConfigWrapper`, `InputConfigCollection` | 521-601 | Group rollup for dashboard | Alarm panel / labels |
| `update_meter_config` | 603-625 | Meter normalization/persistence | Meter sensor/number/action |
| `invalidate_remote_config` | 1487-1507 | Clear Java config cache | Hub API write path |
| `EventProcessor` | 1522-1955 | Control-plane consumer: device-info inserts, auto-scheduler, bot commands, Telegram forwarding | Hub event bus + HA coordinator |
| `TBot` | 1972-2278 | Telegram bot; commands `/inputon`, `/inputoff`, `/outputon`, `/outputoff`; images; SNS fallback | Keep bot, add `notify.event_processor_telegram` |
| `AutoScheduler` | 2280-2395 | `schedule` library; global enable; per-device enable/disable times | HA schedules/automations |
| `ApiServer` | 2398-2425 | Uvicorn server | Hub API server |
| `main` | 2427-2510 | Sentry, OTEL, signals, threads, RabbitMQ SMS listener | Hub startup; HA separate |

Templates: `layout.html`, `index.html`, `config.html`, `input_config.html`,
`output_config.html`, `input_link.html`, `output_link.html`, `event_log.html`,
`metrics.html`, `login.html`, `error.html`. All are replaced by HA UI/logbook
or removed in phase 3.

### 5.5 SQLite profile and data-quality findings

| Table | Rows | Purpose | Migration note |
|---|---:|---|---|
| `input_config` | 39 | Input identity, armed state, tuning, schedules | Identity → device registry; armed → switch; tuning → CONFIG entities/subentry |
| `output_config` | 12 | Output identity, enabled, params, topic/interval | Identity → device registry; enabled/interval → CONFIG entities; `device_params` stays secret |
| `output_link` | 78 | Input→output mapping | 71 valid; 7 orphan; keep in hub phase 1; later HA automations |
| `input_link` | 3 | Input↔input mapping | Feature not implemented; drop |
| `meter_config` | 1 | Meter state/config | `sensor` energy + `number` limits + reset action |
| `event_log` | 56,347 | Historical events, 2022–2024 | Archive; HA Logbook/History replaces it |
| `general_config` | 6 | `auto-scheduler`, 4 CloudWatch widgets, `instance_id` | Options/instance ID; drop widgets |
| `heartbeat` | 1 | Hub liveness | Diagnostic sensor |

Key data facts:

- Input types: `Camera` 8, `contact` 8, `Motion Detector` 5, `motion` 5,
  `Main Door` 2, `Smoke Detector` 2, `siren` 2, `Back Door` 1,
  `Dash Button` 1, `Panic Button` 1, `detector` 1, `info` 1, `meter` 1,
  `volume` 1.
- Input groups: `Garage Security` 9, blank 7, `House Security (Indoors)` 6,
  `House Security (Back)` 5, `House Security (Front)` 5, `Garage Main Door` 4,
  `Ring` 3.
- Armed state: 31 enabled, 8 disabled, 0 null.
- Tuning: `multi_trigger_rate` 6, `multi_trigger_interval` 6,
  `activation_escalation` 3, `trigger_latch_duration` 0, `info_notify` 1.
- Schedules: input `auto_schedule=1` 0, `=0` 11, null 28, 16 time strings;
  output `auto_schedule=1` 2, null 10, 2 time strings. Do not activate dormant
  schedules.
- Output types: `Camera` 8, `Buzzer` 2, `SMS` 1, `l2ping` 1.
- Camera credentials are plaintext in `output_config.device_params`; never copy
  them to HA device metadata/attributes/diagnostics.
- `output_link`: SMS 37, cameras 35, buzzers 4, l2ping 2 = 78; 71 valid.
- `PRAGMA integrity_check` = `ok`; `PRAGMA foreign_key_check` reports 7
  `output_link` → `input_config` violations (IDs 26–31, 37).
- `config/db_schema.sql` is stale: lacks `trigger_latch_duration`,
  `multi_trigger_rate`, `multi_trigger_interval`, `activation_escalation`,
  output `trigger_topic`/`trigger_interval`/`device_enabled`/auto-schedule
  columns and `heartbeat`. Use the live DB, not the schema file.
- Legacy columns not in the ORM: `activation_interval`, `multi_trigger`,
  `trigger_window`, `trigger_latch`. Ignore/drop.
- Ring devices use UUID `device_key`s; legacy devices use human labels. Treat
  `device_key` as the immutable identifier and `device_label` as mutable.
- `event_log` is stale and no longer written; do not migrate 56k rows.
- The DB was locked for a plain `sqlite3` connection; use
  `file:...?immutable=1` for a read-only snapshot. The live app uses WAL.
- Production DB ownership must be `999:999` for the `app` user.

### 5.6 Ring, cameras, Telegram, schedules, links

**Ring.** The Java `Mqtt` class listens to `ring/#`, and `Ring.getDeviceKey()`
is the Ring UUID. `Ring.getDeviceLabel()` comes from `homeassistant/.../config`
MQTT discovery data cached by `DeviceConfig`. This is the existing HA-adjacent
discovery path. The user intends to move Ring ownership to the HA Ring
integration. Therefore:

- phase 1: `RING_SOURCE=mqtt`, no duplicate Ring devices in HA;
- phase 2: `RING_SOURCE=ha`, HA Ring owns the devices, EventProcessor accepts
  Ring state through the hub API (planned `POST /api/v1/inputs/{key}/state` or
  an `event_processor.process_input` action), and the Java `ring/#` parser is
  disabled;
- the `homeassistant/#` cache in Java becomes obsolete in `RING_SOURCE=ha`.

**Cameras.** HA supports RTSP/ONVIF/generic camera and already has `go2rtc`.
Use HA for live view; keep EventProcessor for snapshot + Telegram. The
event_processor integration exposes a `button` “Snapshot” per camera (or a
hub-level action), not a duplicate `camera` entity and not camera credentials.
The motion → snapshot → Telegram linkage stays in EventProcessor.

**Telegram.** Keep the existing TBot process and token. Do not add HA's
`telegram_bot` integration with the same token (long-polling conflict). Add
`POST /api/v1/notify` and an HA `notify.event_processor_telegram` entity that
calls it. Keep `/inputon`, `/inputoff`, `/outputon`, `/outputoff`; they call
the hub API and state changes propagate to HA over WebSocket. This preserves
image filtering, SNS fallback, rate limiting and the existing notification
path.

**Schedules.** Phase 1/2a: expose `time` CONFIG entities (enable/disable) or
use the hub's existing `auto_schedule` values. Phase 2b: use HA `schedule`
helpers and automations. Do not programmatically create schedule helpers.

**Links.** HA has no first-class “link” object; use automations. Phase 1 keeps
`LINKS_SOURCE=hub`. Phase 2 generates importable `automations.yaml` snippets
from the 71 valid links for review; a global `LINKS_SOURCE=ha` flag prevents
double triggers. In `ha` mode Java emits `input_triggered` but does not execute
linked outputs; HA automations call `event_processor.trigger_output` so the
hub's output rate limits still apply.

---

## 6. Phase-1 implementation (live)

### 6.1 Hub API

`app/hub_api.py` is a self-contained FastAPI router. It does not import
`app.__main__` (avoiding a circular import) and talks to SQLite directly.

Endpoints:

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/info` | instance ID, version, API version, uptime, capabilities |
| GET | `/api/v1/snapshot` | devices, groups, links, general config, heartbeat |
| GET | `/api/v1/devices` | device list |
| GET | `/api/v1/devices/{key}` | device detail |
| GET | `/api/v1/diagnostics` | counts, orphan links, heartbeat age, event bus stats |
| PATCH | `/api/v1/devices/{key}` | update tuning/config fields |
| POST | `/api/v1/inputs/{key}/enabled` | arm/disarm |
| POST | `/api/v1/outputs/{key}/trigger` | trigger snapshot/buzzer/l2ping/SMS |
| POST | `/api/v1/meters/{key}/reset` | reset register with optional value |
| PUT | `/api/v1/inputs/{key}/links` | replace output links |
| POST | `/api/v1/panic` | legacy panic path + Telegram |
| POST | `/api/v1/notify` | send through the existing Telegram bot |
| POST | `/api/v1/events` | internal Java/event ingestion |
| GET | `/api/v1/events?since=N` | recent event replay |
| WS | `/api/v1/events` | push stream to HA |

Auth: `Authorization: Bearer <hub_token>`. The token is read from
`HUB_API_TOKEN`, else from `HUB_API_TOKEN_FILE` (default
`/data/hub_api_token`), and generated once if missing. It is shared with the
Java container through the mounted `data` volume.

Event envelope:

```json
{
  "seq": 12345,
  "type": "input_triggered",
  "device_key": "Garage Motion Detector",
  "timestamp": "2026-10-18T10:52:30.123Z",
  "data": {"state": "on", "event_detail": "motion", "trace_id": "..."}
}
```

Event types used: `snapshot` (implicit), `device_added`, `device_updated`,
`device_removed`, `input_state`, `input_triggered`, `output_triggered`,
`meter_reading`, `heartbeat`, `config_changed`, `panic`, `hub_started`.
The bus keeps a 500-event ring buffer and fans out to WebSocket subscribers;
slow subscribers drop events rather than block.

Output commands are published to RabbitMQ exchange
`<RABBITMQ_EXCHANGE>_control` (default `home_automation_control`) with a
MessagePack body matching the Java `Event` wire format: `timestamp`,
`active_input`, `output_triggered`, `ha_payload`. `trigger_interval`,
`device_params` and `trigger_topic` come from `output_config`; `SMS` is
special-cased to the Telegram bridge.

The write paths call the Java `/invalidate_config?device_key=...` endpoint and
publish `device_updated`/`config_changed`/`input_state` events. The discovery
insert path now also calls `invalidate_remote_config`, fixing the old 1-hour
404 cache problem.

### 6.2 Java processed-event publisher

`src/main/java/tailucas/app/provider/HubEventPublisher.java`:

- `HttpClient.sendAsync` to `http://CONFIG_HOST:CONFIG_HOST_PORT/api/v1/events`
- token from `HUB_API_TOKEN` or `/data/hub_api_token`
- non-blocking; one warning if disabled; no event-loop stalls
- called from `Event.run()` for `input_state` (heartbeat and inactive),
  `input_triggered`, `output_triggered`, `escalation`, `meter_reading`
- `Meter` gained getters for register/sample/discard fields
- closed in `EventProcessor.shutdown()`

Live evidence: the hub event bus has received hundreds of `input_state`
events and `input_triggered`/`output_triggered` events from the Java engine.

### 6.3 HA component layout

```
custom_components/event_processor/
  __init__.py              setup/unload, device/group creation, services
  manifest.json            private hub integration
  const.py                 DOMAIN, config keys, type maps
  client.py                vendored aiohttp HubClient
  coordinator.py           snapshot + WebSocket bridge
  entity.py                bases, DeviceInfo, dynamic entity helper
  config_flow.py           user/reauth/options flow
  binary_sensor.py         motion, opening, smoke, sound, connectivity
  event.py                 buttons, camera detections, optional motion/contact
  switch.py                input armed, output enabled (CONFIG)
  button.py                camera snapshot, buzzer, l2ping, meter reset, panic
  sensor.py                meter energy, heartbeat age, counts, last triggered
  number.py                input tuning, meter limits, output intervals
  alarm_control_panel.py   one panel per security group
  notify.py                notify.event_processor_telegram
  services.yaml            action descriptions
  diagnostics.py           redacted diagnostics
  strings.json             English UI
  translations/en.json     English strings
  quality_scale.yaml       Bronze tracking
```

Device model:

- hub device: `(DOMAIN, instance_id)`, `DeviceEntryType.SERVICE`,
  manufacturer Tailucas, model “Event Processor Hub”.
- child device: `(DOMAIN, device_key)`, `via_device_id=hub.id`, name
  `device_label`, model `device_type`.
- group device: `(DOMAIN, "group:<name>")`, name `<name>`, model
  “Security group”.
- Ring devices (`source=ring`) are skipped unless the
  `create_external_devices` option is enabled.
- `has_entity_name=True`; primary binary sensor uses `name=None`; switches use
  “Armed”/“Enabled”; tuning uses CONFIG numbers.

### 6.4 Entity mapping in phase 1

| Hub concept | HA entity | Notes |
|---|---|---|
| motion / Motion Detector | `binary_sensor` `motion` | primary entity, `name=None` |
| contact / Back Door / Main Door | `binary_sensor` `opening`/`door` | `opening` generic |
| Smoke Detector / detector | `binary_sensor` `smoke` | |
| siren | `binary_sensor` `sound` | state only in phase 1 |
| Camera input | `binary_sensor` `motion` + `event` detections | snapshot button also |
| `info` | `binary_sensor` `connectivity` | Ring range extender |
| `l2ping` output | `binary_sensor` `connectivity` + button | ping action |
| input armed | `switch` “Armed” | runtime control, not CONFIG |
| output enabled | `switch` “Enabled” (`CONFIG`) | gate, not physical state |
| input tuning | `number` CONFIG | latch, multi-trigger, escalation |
| output interval | `number` CONFIG | trigger rate limit |
| meter limits | `number` CONFIG | low/high |
| meter reading | `sensor` `energy`, `total_increasing`, kWh | `4.0` in live data |
| meter reset | `button` “Reset” | uses configured reset value |
| Camera output | `button` “Snapshot” | no camera credentials |
| Buzzer output | `button` “Sound” | no live actuation in testing |
| SMS output | `notify.event_processor_telegram` | Telegram bridge |
| virtual panic/dash | `event` “Pressed” | `event.dash_button_pressed` / `event.panic_button_pressed` |
| group | `alarm_control_panel` | 5 groups; `armed_away` if all, disarmed if none, `armed_home` if mixed |
| heartbeat | diagnostic sensor “Heartbeat age” | seconds |
| device/link counts | diagnostic sensors | |
| last triggered | diagnostic timestamp sensor | created on first value; disabled by default |
| panic | `button.event_processor_panic` | hub-level |

Live entity registry (after phase-1 verification):

```
32 event_processor devices
120 event_processor entities:
  34 switch
  25 number
  20 binary_sensor
  18 event
  13 button
   5 alarm_control_panel
   4 sensor
   1 notify
```

The 32 devices are 26 managed child devices + 5 group devices + 1 hub device.
The 17 `source=ring` devices are intentionally absent.

### 6.5 Services

| Service | Target | Purpose |
|---|---|---|
| `event_processor.trigger_output` | output device | snapshot/buzzer/l2ping/SMS |
| `event_processor.set_input_enabled` | input device | arm/disarm |
| `event_processor.reset_meter` | meter device | reset with optional value |
| `event_processor.set_output_links` | input device | replace output mapping |
| `event_processor.set_device_config` | device | patch tuning fields |
| `event_processor.panic` | hub | fire panic |
| `event_processor.notify` | hub | send Telegram message |

All actions raise `HomeAssistantError` on hub failure and are described in
`services.yaml`.

Live end-to-end checks performed:

- `number.electricity_meter_low_limit` 50000 → 50001 → 50000 through HA, read
  back from the hub snapshot.
- `event_processor.set_device_config` set `multi_trigger_rate`.
- `event_processor.set_input_enabled` set the same armed state.
- Hub `POST /api/v1/events` (`input_triggered` for Garage Motion Detector)
  updated `event.garage_motion_detector_motion_events`.
- `GET /api/v1/diagnostics` reported `subscribers: 1`.
- Legacy Flask `/admin/` and `/api/input_config` still return 200.

### 6.6 Deployment and operations

| Target | Command | What it does |
|---|---|---|
| HA component | `make ha-deploy` | copies `custom_components/event_processor` to `acer:/home/tai/home-assistant/config/custom_components/event_processor`, restarts `homeassistant`, waits for port 8123 |
| Hub API/Java | `make hub-deploy` | copies source to `acer:/home/tai/event-processor`, `make build`, `docker compose up -d`, waits for `/api/ping` |
| Token | `make hub-token` | prints `/data/hub_api_token` |
| HA lint | `make ha-lint` | ruff + hub API tests + component checks |
| Project lint | `make lint` | ruff + mypy on `app/__main__.py` |

Deployment caveats:

- `deploy_hub.sh` loads `OP_CONNECT_HOST`, `OP_CONNECT_TOKEN`, `OP_VAULT` from
  `/home/tai/.bashrc`; the Connect host is `http://acer.discus-universe.ts.net:8082`
  (or `http://host.docker.internal:8082`).
- `docker-compose.yml` no longer requires the unused
  `OP_SERVICE_ACCOUNT_TOKEN` secret; 1Password Connect is the active path.
- The data directory must be writable by UID 999. If the DB is restored from a
  copy owned by UID 1000, chown it before starting the app.
- `deploy_ha.sh` performs a full HA restart. A config entry reload is not
  enough to pick up changed Python module code.
- The HA config flow REST API requires an admin token:
  `POST /api/config/config_entries/flow` then
  `POST /api/config/config_entries/flow/{flow_id}`.
- HA host networking means the hub URL is `http://127.0.0.1:8095`; from another
  host use `http://192.168.0.6:8095`.

### 6.7 Phase-1 caveats and known limitations

- The HA integration is private and vendored; no PyPI client package.
- `quality_scale.yaml` has `config-flow-test-coverage: todo`; HA-specific
  pytest tests still need to be added in a separate HA venv.
- The virtual-button event entities were created as
  `event.dash_button_button`/`event.panic_button_button` on first install and
  were renamed to `event.dash_button_pressed`/`event.panic_button_pressed`.
  A fresh install gets the clean IDs directly.
- The `House Security (Indoors)` panel includes Ring members for group control
  but has no Ring device entities.
- Camera live entities are not created; use `go2rtc`/RTSP. Snapshot buttons
  are present.
- The HA-initiated output command path (`trigger_output` → RabbitMQ) is
  implemented but was not actuated against a live buzzer/camera during
  verification. Test with a non-critical output first.
- The HA config entry creation and service verification used the local `tai`
  account; extra HA refresh tokens may exist from those logins.
- The `event_processor.db` root copy in the workspace is a reference snapshot;
  it is now gitignored.
- `EventProcessor`/`AutoScheduler`/`TBot` still run in the Python app in phase
  1; they are not removed.
- The Flask dashboard remains available at `/admin/`; do not remove it until
  phase 3 acceptance criteria are met.

---

## 7. Migration from the Flask control surface to HA

### 7.1 Principles

1. **HA becomes the primary configuration surface; the hub remains the runtime
   authority.** A security system must not stop because HA is restarting.
2. **No double triggers.** Every migration step that moves behavior must have
   an explicit source switch (`LINKS_SOURCE`, `managed_by`, `RING_SOURCE`) and a rollback.
3. **HA owns identity and user intent** (device registry, names, areas, labels,
   armed state, tuning). The hub owns real-time filtering and output rate
   limits.
4. **Secrets never move to HA device metadata.** Camera credentials and
   internal broker topics stay in 1Password/the hub.
5. **Do not duplicate devices owned by another integration** (Ring, cameras).
   Use hub-level entities or separate action devices instead.
6. **Migrate by parity, not by deletion.** Flask stays until every dashboard
   function has an HA equivalent and the user has validated it.
7. **The hub DB is a runtime store, not the long-term UI store.** After phase
   3 it should hold only runtime state, credentials and a cache; HA config
   subentries/entities are the editing surface.

### 7.2 Phase plan

| Phase | State | Scope | Exit criteria |
|---|---|---|---|
| 0 | done | Hub API, token, event bus, Java publisher, client, deployment scripts | API tests green; Flask still works |
| 1 | done | HA config flow, snapshot/WebSocket, devices, entities, services, diagnostics, 5 alarm panels | 32 devices/120 entities; controls round-trip; Flask fallback intact |
| 2 | next | Config parity: per-device subentries/CONFIG entities, schedules, link management, Ring cutover preparation, notification polish | Every Flask page has an HA equivalent; user can stop using Flask for day-to-day config |
| 3 | later | HA-native links/schedules, Ring `RING_SOURCE=ha`, remove Flask pages/login/AutoScheduler/TBot dependency, archive event_log, reduce DB | HA is the primary config surface; hub runs headless |
| 4 | optional | Public distribution/core submission | Only if the project is open-sourced; not the private path |

### 7.3 What moves where (extent of HA metadata migration)

HA device metadata cannot hold arbitrary behavior. The split is:

| Bucket | Examples | Approx. share | Destination |
|---|---|---:|---|
| Identity/topology | `device_key`, `device_label`, `device_type`, `group_name` | ~35% | HA device registry: identifiers, name, model, labels, `via_device_id` |
| Runtime state | `device_enabled`, meter reading, last triggered, output state, heartbeat | ~15–20% | HA entities: `switch`, `alarm_control_panel`, `sensor`, `binary_sensor` |
| Behavioral config | latch, multi-trigger, escalation, `info_notify`, `trigger_interval`, meter limits, links | ~30–35% | Config subentries, options, CONFIG `number`/`time`/`select`; links as action/automation |
| Hub-only / secrets / drop | camera `device_params`, `trigger_topic`, register internals, `event_log`, CloudWatch widgets, legacy columns, orphans | ~10–15% | 1Password/hub; archive or delete |

So: **about one-third identity metadata, one-sixth runtime state, one-third
HA-side behavior config, and the remainder stays in the hub or is dropped.**
The 71 valid links and the latch/multi-trigger/escalation tuning are the
largest behavior items.

Table-by-table disposition:

- `input_config`: keep in phase 1/2; identity → registry, armed → switch,
  tuning → CONFIG/subentry. Phase 3 may make HA subentries the editing source,
  with the hub keeping a runtime cache.
- `output_config`: identity → registry; enabled/interval → CONFIG;
  `device_params` → secrets; `trigger_topic` stays internal.
- `meter_config`: `sensor` + `number` + reset action; runtime values can stay
  in the hub initially.
- `input_link`: drop (feature never implemented).
- `output_link`: keep 71 valid; clean 7 orphans; phase 2 action/attributes;
  phase 3 HA automations.
- `general_config`: `auto-scheduler` → option; `instance_id` stays; CloudWatch
  widgets drop.
- `event_log`: archive; HA Logbook/History replaces it.
- `heartbeat`: keep for liveness; expose diagnostic sensor.

### 7.4 Links and schedules migration

**Links (71 valid).** HA has no “link” object; automations are the native
mechanism. Recommended sequence:

1. Keep `LINKS_SOURCE=hub` in phase 1.
2. Add `LINKS_SOURCE=hub|ha` to the hub configuration. In `ha` mode Java emits
   `input_triggered` but does **not** execute linked outputs.
3. Generate an importable `automations.yaml` snippet from `output_link`:
   - trigger: event/state of the input (post-filter)
   - action: `event_processor.trigger_output` for camera/buzzer/l2ping/SMS
   - preserve the current output rate limit by using the action, not direct
     `switch.turn_on` where possible.
4. Review/import the automations; disable the hub links for the migrated
   inputs; flip `LINKS_SOURCE=ha`.
5. Keep the hub table as a fallback/export; do not delete it until the
   automations are validated.

**Refinement (2026-10-19):** prefer a per-link `managed_by` field
(`hub` | `ha`) over a single global cutover. `LINKS_SOURCE=auto` executes
`hub` links and skips `ha` links; `LINKS_SOURCE=hub` forces all links back to
Java; `LINKS_SOURCE=ha` disables all Java links. See §11.6 for the camera-link
migration design.

Do not generate automations programmatically from the integration; HA has no
supported API for that. Ship the YAML + a blueprint and let the user own the
automation objects.

**Schedules.** The current `auto_schedule` data is mostly dormant: 0 input
schedules active, 2 output schedules active; 16 inputs have time strings but
`auto_schedule=0`. Do not activate them during migration. Options:

- phase 2a: `time` CONFIG entities (enable/disable) + a `switch` “Scheduled”;
- phase 2b: HA `schedule` helpers + automations that call
  `alarm_control_panel.*` / `event_processor.set_input_enabled`;
- phase 3: remove the Python `AutoScheduler` and `auto_schedule` columns from
  the runtime surface.

The user can create a schedule helper per device/group; name it after the
device and use labels for grouping. A `schedule` block can carry `data:` for
per-block parameters.

### 7.5 Ring, cameras, notifications, auth, event history

**Ring cutover.** The user will move Ring devices to HA (previously they were
“hacked into EventProcessor” before HA existed). The clean sequence:

1. Keep `RING_SOURCE=mqtt` in phase 1; do not create Ring device entries in the
   event_processor integration.
2. Configure the HA Ring integration (cloud) or `ring-mqtt` + HA MQTT. The Ring
   integration owns the Ring devices/entities.
3. Add `RING_SOURCE=ha`. EventProcessor stops parsing `ring/#` and accepts Ring
   state through:
   - `POST /api/v1/inputs/{key}/state` (planned), or
   - an `event_processor.process_input` action called by HA automations.
4. Remove the Java `homeassistant/#` discovery cache; it becomes obsolete.
5. Keep the `House Security (Indoors)` alarm panel; it can control the hub's
   armed flags for Ring inputs even though the Ring device entities are owned
   by the Ring integration.

Do not attach event_processor entities to the Ring integration's device entries;
device entries are domain-scoped and cannot be shared across integrations.

**Cameras.** HA `go2rtc`/ONVIF/RTSP owns live view and recording. EventProcessor
keeps snapshot + Telegram. Expose snapshot buttons/actions; do not copy
credentials. Duplicate camera device entries are acceptable and can share an
area. A `camera` entity is added only if the hub can return snapshot bytes via a
stable URL (not in phase 1).

**Telegram.** One bot, one token. Keep TBot and commands. Add
`notify.event_processor_telegram` backed by `POST /api/v1/notify`; HA
automations can notify through the existing bot. Do not add HA's
`telegram_bot` integration with the same token. If the bot is later moved into
HA, port the image filtering/SNS fallback/rate limiting first, then retire
TBot.

**Auth.** HA users replace Flask-Login. The hub API keeps a dedicated bearer
token (not an HA long-lived token). Remove Flask `/login`, `/logout`,
`auth_enabled`, `authz_required`, and the single-user 1Password credential
after phase 3.

**Event history.** HA Logbook/History replaces `/event_log`. Archive the 56,347
legacy rows; do not import them. Event entities show discrete occurrences in
Logbook; state entities show history. Use labels/areas to filter.

**Panic.** Keep the virtual panic input and `button.event_processor_panic`.
The current Flask panic path is partly a no-op in the Python EventProcessor; do
not claim it is a fully wired engine path until verified.

### 7.6 End-state target

```
HA
├── Users, auth, UI, dashboards, logbook, history, energy
├── Device registry: identity/area/labels for managed devices
├── Config subentries/entities: per-device tuning and schedules
├── Automations: input→output links (LINKS_SOURCE=ha)
├── Ring integration / go2rtc: physical Ring and camera ownership
├── notify.event_processor_telegram: notification bridge
└── event_processor integration
        │ HTTP + WebSocket
        ▼
Hub API (Python)
├── /api/v1 snapshot, events, controls, diagnostics
├── runtime state cache (last known devices/links/meter)
└── optional thin config cache for Java
        │ RabbitMQ/MQTT
        ▼
Java EventProcessor
├── latch / multi-trigger / escalation / meter thresholds
├── output rate limits
├── output commands
└── no UI, no device registry
```

The hub may keep SQLite for runtime state and credentials, but the editing
surface is HA. Flask, Jinja templates, Flask-Login, the dashboard routes and
the AutoScheduler are removed. The Telegram bot may remain as a bridge.

### 7.7 Acceptance criteria and rollback

Phase 2 acceptance:

- Every Flask page/action has an HA equivalent:
  device list, arm/disarm, group arm, panic, meter reset, input tuning, output
  tuning, links, schedules, event history, metrics.
- Editing a config value in HA reaches Java within the cache TTL.
- Flask and HA do not fight over writes; the hub API is the single writer.
- Ring devices are either absent (phase 1) or owned by the Ring integration
  (phase 2), never duplicated.
- Secrets are absent from HA attributes/diagnostics.
- `make lint`, `make ha-lint`, hub API tests and Java tests are green.

Phase 3 acceptance:

- HA is the primary config surface; Flask can be stopped without losing
  control.
- `LINKS_SOURCE=ha` and `RING_SOURCE=ha` are validated; rollback flags exist.
- Legacy event_log archived; orphan links cleaned; dead columns dropped.
- The hub continues to run if HA is down, using its last-known-good config.

Rollback:

- Phase 1/2: disable the HA integration; Flask and the old API remain.
- Phase 3: keep a tagged release, a DB backup and the feature flags
  (`LINKS_SOURCE=hub`, `RING_SOURCE=mqtt`) so routing can be restored.
- Never destroy the DB/config tables until the HA automations/subentries have
  been validated for a full operational cycle.

### 7.8 Dependency, coverage and replacement assessment

This subsection records the quantitative assessment of how much of the project
still depends on SQLite, how much of the schema is mirrored in HA, how much
device linkage remains in SQLite, and which HA features are safe replacements.

#### 7.8.1 SQLite dependency by table

| Table | Rows | Still required? | Why |
|---|---:|---|---|
| `input_config` | 39 | **Yes** | Java reads armed state, latch, multi-trigger and escalation; hub API reads identity/group |
| `output_config` | 12 | **Yes** | Java reads enabled, `device_params`, `trigger_topic`, `trigger_interval` |
| `output_link` | 78 (71 valid) | **Yes** | Java reads the 71 valid links to decide which outputs to trigger |
| `input_link` | 3 | No | Feature never implemented; dead data |
| `meter_config` | 1 | **Yes** | Java meter threshold evaluation and reset normalization |
| `event_log` | 56,347 | No | No longer written; legacy history only |
| `general_config` | 5 ref / 6 prod | Partly | `auto-scheduler`; production adds `instance_id`; 4 CloudWatch rows are dead |
| `heartbeat` | 1 | Partly | Python liveness write; HA diagnostic sensor mirrors it |

Row-count view (reference snapshot; production adds `instance_id`, 56,488 total):

```
56,487 total rows in reference snapshot
56,347 event_log
     3 input_link
     7 orphan output_link
     4 CloudWatch general_config
------
56,361 legacy/dead rows (99.8%)
```

Active-configuration view (production):

```
126 meaningful config rows (production)
 14 removable rows (3 input_link + 4 CloudWatch + 7 orphan links)
------
140 non-event rows; ~90% of config is still SQLite-owned
```

The reference snapshot is one row smaller (125 meaningful / 139 non-event)
because it predates `instance_id`.

The conclusion is that SQLite **cannot be removed in phase 2 without replacing
the Java engine’s config source**. If HA becomes the editing surface, the hub
still needs a local runtime cache for resilience. The realistic end-state is a
small SQLite/JSON cache, not zero local storage.

#### 7.8.2 How much of the schema is mirrored in HA

The HA device/entity registries hold a **mirror** of identity and UI state, not
the operational schema.

Device coverage:

| Group | Count | In HA? |
|---|---:|---|
| Unique hub devices | 43 | 26 mirrored as child devices |
| Non-Ring devices | 26 | **26/26 = 100%** |
| Ring inputs | 17 | **0/17 = 0%** (intentionally skipped) |
| HA group devices | 5 | HA-only virtual devices |
| HA hub device | 1 | HA-only |
| **Total HA devices** | **32** | 26 child + 5 group + 1 hub |

So **60.5% of hub device identities (26/43)** are represented in HA, but
**100% of non-Ring devices** are. The 17 Ring devices are deliberately absent
so they are not duplicated when the Ring integration takes ownership.

Input/output coverage:

| Schema area | DB rows | HA mirror | Coverage |
|---|---:|---|---:|
| Input identity (`device_key`, label, type, group) | 39 | 22 non-Ring inputs | **22/39 = 56%** |
| Output identity | 12 | 12 outputs | **12/12 = 100%** |
| Input armed state (`device_enabled`) | 39 | 22 individual `switch` entities | **22/39 = 56%** |
| Output enabled | 12 | 12 `switch` entities | **12/12 = 100%** |
| Input tuning | 15 non-null values (13 non-Ring) | 25 `number` entities total across input/output/meter | Mirrored, DB authoritative |
| Meter state | 1 | 1 sensor + 1 number + 1 reset button (high limit is null) | Mirrored, DB authoritative |
| Output links | 71 valid | **0** | **0% in HA** |
| Schedules | 0 input active; 2 output active | **0** | **0% in HA** |
| Event history | 56,347 | New events only in Logbook | **0% legacy migrated** |
| Camera credentials | 8 camera `device_params` (12 outputs have params) | **0** | **0% in HA** (correctly) |

The HA entity registry currently has **120 Event Processor entities**:

```
34 switch
25 number
20 binary_sensor
18 event
13 button
 5 alarm_control_panel
 4 sensor
 1 notify
```

The 25 numbers are 13 non-Ring input tuning values + 11 output
`trigger_interval` values + 1 meter low limit (the meter high limit is null in
the reference DB).

Those entities provide the UI, but their values still come from SQLite through
the hub API. Editing a tuning value in HA writes through the hub API back into
SQLite, and the Java engine continues to work if HA is offline.

**Answer to “how much of the schema has been replaced?”** Very little has been
*replaced*. Identity is *mirrored* for 26 of 43 devices; behavior, links,
schedules, credentials and history remain in SQLite/hub. HA metadata cannot
hold arbitrary device behavior anyway—the device registry is identity/topology
only.

#### 7.8.3 Device linkage still in SQLite

| Linkage | Current state | Needed by Java? | HA coverage |
|---|---|---:|---:|
| `output_link` | **71 valid links** (78 rows; 7 orphan) | **Yes** | **0%** |
| Ring-originated links | **25 of 71 (35%)** | Yes, while `RING_SOURCE=mqtt` | 0% |
| Non-Ring links | **46 of 71 (65%)** | Yes, until `LINKS_SOURCE=ha` | 0% |
| `input_link` | 3 rows | No | 0%; drop |
| Group membership (`group_name`) | 7 values incl blank/Ring; 5 HA panels | No (Java only logs it) | 5 group devices/panels |
| Meter → input (`meter_config.input_device_id`) | 1 | Yes | 1 meter sensor + 1 number + 1 button |
| Dual-role cameras | 8 device keys in both input/output tables | Yes | Merged into 8 HA devices |
| Output transport (`device_params`, `trigger_topic`) | 12 outputs | Yes | 0%; stays secret/internal |

Ring classification note: the hub snapshot marks the 17 Ring devices as
`source=ring`; SQLite has no `source` column. In the reference DB they are the
17 UUID-form `device_key`s (36 chars with hyphens), while the 22 non-Ring
inputs are human-readable keys. The 25/46 link split above uses that key form.

So **100% of the actual input→output routing linkage is still in SQLite.**
HA currently has **no link representation at all**. The 46 non-Ring links are
the natural phase-2 migration target; the 25 Ring links should wait for the
Ring cutover or move with the Ring automations.

If only non-Ring links are migrated to HA automations, **46/71 = 65%** of the
routing table is retired. Migrating Ring links retires the remaining **35%**
and allows `output_link` to be dropped entirely.

#### 7.8.4 HA features that are close to drop-in replacements

| HA feature | Replaces | Why it is close |
|---|---|---|
| Device registry | Device identity: key, name, model, `via_device_id`, group via labels/areas | Direct for identity, not behavior |
| Entity registry | Entity naming, unique IDs, categories, enabled/hidden | Direct for UI metadata |
| Alarm control panel | Group arm/disarm panel | Direct UX; group aggregation logic is custom but small |
| `switch` | Per-input armed, output enabled | Direct UX; still writes through hub API |
| `number` | Latch, multi-trigger, escalation, output interval, meter limits | Direct UX; still writes through hub API |
| `event` entity | Virtual buttons, camera detections, motion/contact occurrences | Direct for new events |
| `button` | Snapshot, buzzer, l2ping, meter reset, panic | Direct action UX; output command still via hub |
| `notify` | Outbound Telegram/SMS | Direct for simple messages; existing bot has image/SNS logic |
| Logbook/History | `/event_log` | Direct for new events; 56k legacy rows do not migrate |
| Energy dashboard/statistics | Meter history | Direct from HA sensor onward; legacy history is separate |
| HA users/auth | Flask-Login | Direct replacement for HA-side access |
| Diagnostics/repairs | `/metrics` and health checks | Direct for health/version/drops |

#### 7.8.5 Approximate replacements — require migration and validation

| Hub behavior | HA primitive | Why it is not drop-in |
|---|---|---|
| 71 output links | Automations with `event`/`state` triggers + `trigger_output` actions | HA has no “link” object; 71 automations or a blueprint; output rate limiting must use the hub action, not direct switch calls |
| `auto_schedule` | `schedule` helper / `time` trigger / automation | User-created helpers; integration cannot create them programmatically; 0 input schedules are active, 2 output schedules are active |
| Meter low/high thresholds | `numeric_state` trigger | Java evaluates thresholds inside the engine; moving it makes HA part of the security path |
| Group rollup | Labels + alarm panel | Labels are metadata, not a native rollup; the integration must aggregate members |
| `info_notify` day/night gating | `time`/`sun` conditions | Direct condition, but currently part of event processing |
| `input_link` | None | No HA equivalent; feature is dead anyway |
| CloudWatch widgets | HA dashboards | Drop/rebuild, not a data migration |
| `customized` flag | None | UI-derived; drop |

HA rule mapping in more detail:

| Java/Flask rule | HA primitive | Drop-in? |
|---|---|---|
| `trigger_latch_duration` | `for:` condition or `timer` helper | Approximate; re-trigger semantics differ |
| `multi_trigger_rate` / `interval` | `counter` + `time` condition/template | Approximate; requires user-created helpers |
| `activation_escalation` | `for:` + notify/PagerDuty/REST action | Approximate; PagerDuty wiring is custom |
| `trigger_interval` (output rate limit) | automation `mode` / `last_triggered` condition | Approximate; not per-output in the same way |
| Meter low/high limits | `numeric_state` trigger | Approximate; threshold crossing vs engine evaluation |
| Output links | `event.received`/state trigger + action | Approximate; migration required |
| Auto-schedule | `schedule` helper / `time` trigger | Close, but user-created |
| Group arm/disarm | `alarm_control_panel` + labels/areas | Close for UX; custom aggregation |
| Event log | Logbook/History | Close for new events |
| Auth | HA users | Direct |
| Health checks | Diagnostics/repairs | Direct |

#### 7.8.6 Features not suitable for HA replacement

- **Latch, multi-trigger, escalation, output rate limit and meter threshold
  evaluation.** These are the Java engine’s security semantics. HA can
  approximate them with `for:`, `counter`, `timer`, automation modes and
  conditions, but the re-trigger/latch behavior is not equivalent. Moving them
  makes HA part of the security path and weakens the “hub keeps running if HA
  is down” guarantee.
- **Camera credentials and internal broker topics.** `device_params` and
  `trigger_topic` must stay in 1Password/the hub.
- **Legacy 56k event history.** HA Logbook starts with new events; archive the
  old rows rather than import.
- **Ring device identity.** The Ring integration should own it; the event
  processor integration must not duplicate it.

#### 7.8.7 Minimal end-state schema

If phase 2/3 are completed, the realistic minimum SQLite schema is:

| Table | Rows | Role after HA becomes primary |
|---|---:|---|
| `input_config` | 39 | Runtime cache: armed state, latch/multi-trigger/escalation for Java |
| `output_config` | 12 | Runtime cache: enabled, interval, `device_params`, `trigger_topic` |
| `meter_config` | 1 | Meter normalization and thresholds |
| `general_config` | 1 | `instance_id` only (if not moved to a file) |
| `heartbeat` | 1 | Optional; can move to in-memory/HA diagnostic |
| **Total** | **~53–54** | Down from ~56.5k rows |

If `LINKS_SOURCE=hub` remains, add back 78 `output_link` rows → ~132 rows
(or ~131 if `heartbeat` is dropped).

Things that can be removed/archived:

- `event_log` (56,347 rows) → HA Logbook/History; archive.
- `input_link` (3 rows) → no equivalent; drop.
- 7 orphan `output_link` rows → clean.
- 4 CloudWatch `general_config` rows → drop.
- `customized` and legacy columns (`activation_interval`, `multi_trigger`,
  `trigger_window`, `trigger_latch`) → drop.
- `output_link` once `LINKS_SOURCE=ha` → drop/archive.
- `auto_schedule` columns once schedules move to HA → drop.
- `heartbeat` if liveness moves to HA/in-memory → drop.

Even then, SQLite remains the **local runtime cache** because Java must keep
working if HA is restarting. The editing surface moves to HA; the runtime store
does not disappear.

#### 7.8.8 Bottom line

- **SQLite is still required** for all real-time Java configuration and
  routing: 39 inputs, 12 outputs, 71 valid links, meter thresholds. HA is not
  the runtime source of truth.
- **HA metadata mirrors identity for 26/43 devices (100% of non-Ring devices)**
  and exposes 120 entities, but it has **not replaced** the schema. The DB
  still holds all behavior, links, schedules, credentials and history.
- **Device linkage is the largest remaining SQLite dependency:** 71 valid
  links, 46 non-Ring (65%) and 25 Ring (35%). HA currently owns **0%** of the
  linkage.
- **Close-to-drop-in HA features:** device/entity registry, alarm panel UX,
  switches/numbers, event/button/notify entities, Logbook/History, Energy
  dashboard, users/auth, diagnostics.
- **Not drop-in:** output links (automations), schedules (helpers/automations),
  latch/multi-trigger/escalation/output rate limits/meter thresholds (Java
  security semantics), credentials/topics, legacy event history.
- **Recommended target:** keep a small SQLite runtime cache (~53–54 rows) for
  Java resilience; move identity/UI/behavior editing to HA; retire `event_log`,
  `input_link`, orphan links, CloudWatch widgets and dead columns; migrate the
  23 non-Ring camera links first (8 HA automations), then the remaining 23
  non-Ring links, then the 25 Ring links with the Ring cutover; see §11.6.

---

## 8. Risks and open questions

### Risks

1. **HA outage vs security uptime.** Do not make HA the runtime dependency for
   arming/triggering. The hub must keep operating with its last config.
2. **Double triggers.** The link cutover must be atomic per link via
   `managed_by` plus a global `LINKS_SOURCE` override; otherwise an input can
   trigger both the hub link and the HA automation. See §11.6.
3. **Duplicate Ring devices.** The Ring integration owns Ring; the event
   processor integration must not create duplicate device entries. The same
   applies to camera integrations.
4. **Event stream reliability.** WebSocket disconnects/backpressure can make HA
   show stale motion/armed states. The safety poll and reconnect/backoff are
   required.
5. **Config write conflicts.** Flask and HA can both edit the same rows during
   coexistence. The hub API is the single writer; add optimistic concurrency
   (`updated_at`/version) if needed.
6. **Secrets.** Camera `device_params`, hub tokens and HA tokens must be
   redacted from diagnostics/logs/attributes.
7. **DB ownership.** UID 999 is required; a restore from a UID 1000 copy
   crashes the Python app on the first discovery insert.
8. **HA version churn.** Device registry v3 (2026.8), config-flow REST API
   (2026.10), HACS→Marketplace (2026.11). Pin/test against the target HA.
9. **HA refresh tokens.** The config-flow verification created normal HA login
   sessions; extra refresh tokens may remain.
10. **1Password Connect.** The compose file no longer requires the service
    account token; Connect host/port must be correct (8082) and the vault must
    be reachable at container start.
11. **Premature SQLite removal.** About 90% of active config and 100% of the
    71 links are still SQLite-owned; HA mirrors only 26/43 devices. Do not
    remove the DB/config tables until phase 3 acceptance and `LINKS_SOURCE=ha`
    are validated (see §7.8).

### Open questions

- Is the long-term goal to remove Flask entirely, or keep it as a break-glass
  UI? The current plan removes it after phase 3; a read-only fallback could be
  retained.
- Should per-device settings live in config entry **subentries** or in CONFIG
  entities? Subentries give a form per device; entities are more automatable.
  A mixed model is likely best.
- Should the event_processor integration own camera snapshot devices, or should
  camera snapshot actions live on the hub device? The current implementation
  uses per-camera action devices/buttons.
- Should Ring be owned by the HA Ring cloud integration or `ring-mqtt` + MQTT?
  Both are viable; `ring-mqtt` is already running on `acer`.
- Should the Telegram bot be kept indefinitely as the notification path, or
  migrated to HA `telegram_bot`/notify? Keeping one bot avoids token conflicts.
- Should the hub DB remain the source of truth for tuning, with HA as a mirror,
  or should HA subentries become authoritative in phase 3? The latter is the
  stated end-state, but it requires a config-push path to Java.

---

## 9. Sources and research material

Official HA documentation (2026-10 snapshot):

- Integration creation: <https://contributors.home-assistant.io/core/integrations/create/>
- File structure: <https://developers.home-assistant.io/docs/creating_integration_file_structure/>
- Manifest: <https://developers.home-assistant.io/docs/creating_integration_manifest/>
- Config flow: <https://contributors.home-assistant.io/core/integrations/config-flow/>
- Options flow: <https://contributors.home-assistant.io/core/integrations/options-flow/>
- Subentries: <https://contributors.home-assistant.io/core/integrations/subentries/>
- Device registry: <https://contributors.home-assistant.io/core/integrations/device-registry/>
- Entity registry: <https://contributors.home-assistant.io/core/entities/registry/>
- Coordinator: <https://contributors.home-assistant.io/core/integrations/fetching-data/>
- Discovery: <https://contributors.home-assistant.io/core/integrations/discovery/>
- Repairs: <https://contributors.home-assistant.io/core/integrations/repairs/>
- Quality scale: <https://contributors.home-assistant.io/core/quality-scale/>
- Dynamic devices: <https://contributors.home-assistant.io/core/quality-scale/dynamic-devices/>
- Device automations: <https://contributors.home-assistant.io/core/integrations/device-automations/>
- MQTT discovery: <https://www.home-assistant.io/integrations/mqtt/#mqtt-discovery>
- Event entity: <https://www.home-assistant.io/integrations/event/>
- Alarm control panel: <https://www.home-assistant.io/integrations/alarm_control_panel/>
- Sensor state classes: <https://www.home-assistant.io/integrations/sensor/#state-class>
- Schedule helper: <https://www.home-assistant.io/integrations/schedule/>
- Notify: <https://www.home-assistant.io/integrations/notify/>
- Camera: <https://www.home-assistant.io/integrations/camera/>
- Labels: <https://www.home-assistant.io/docs/organizing/labels/>
- Areas: <https://www.home-assistant.io/docs/organizing/areas/>
- HACS publishing: <https://hacs.xyz/docs/publish/start>
- HACS Marketplace: <https://hacs.xyz/docs/marketplace>
- Testing harness: <https://github.com/MatthewFlamm/pytest-homeassistant-custom-component>

HA core source inspected in the running container:

- `/usr/src/homeassistant/homeassistant/helpers/device_registry.py`
- `/usr/src/homeassistant/homeassistant/helpers/data_entry_flow.py`
- `/usr/src/homeassistant/homeassistant/components/config/config_entries.py`
- `/usr/src/homeassistant/homeassistant/components/event/entity.py`
- `/usr/src/homeassistant/homeassistant/components/event/const.py`

Local research artifacts:

- `/home/tai/dev/event-processor/event-processor.db` — reference SQLite snapshot
  (gitignored).
- `/home/tai/dev/event-processor/app/__main__.py` — Flask/FastAPI app.
- `/home/tai/dev/event-processor/src/main/java/tailucas/app/` — Java engine.
- `/home/tai/dev/event-processor/custom_components/event_processor/` — HA
  component.
- `/home/tai/dev/event-processor/app/hub_api.py` — hub API.
- `/home/tai/dev/event-processor/tests/test_hub_api.py` — API tests.

Sub-agent research included: HA core integration architecture, MQTT discovery
vs custom integration, device/entity registry semantics, config subentries,
alarm/event/notify/camera entity modeling, HACS/Marketplace, HA testing, and a
probe of the local HA instance. The probe found HA 2026.10.2, a fresh install,
no MQTT/Ring integration, and `go2rtc` present.

---

## 10. Appendix: useful commands and payloads

```bash
# Hub token
make hub-token

# Hub API smoke tests (from acer)
TOKEN=$(docker exec -u app event-processor-app-1 cat /data/hub_api_token)
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8095/api/v1/info
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8095/api/v1/snapshot
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8095/api/v1/diagnostics

# Arm/disarm
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"enabled":true}' \
  http://127.0.0.1:8095/api/v1/inputs/Garage%20Motion%20Detector/enabled

# Trigger an output (careful: live action)
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"payload":{"message":"test"}}' \
  http://127.0.0.1:8095/api/v1/outputs/SMS/trigger

# Synthetic event to HA
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"input_triggered","device_key":"Garage Motion Detector","data":{"event_detail":"motion"}}' \
  http://127.0.0.1:8095/api/v1/events

# Deploy
make ha-lint
make ha-deploy
make hub-deploy
```

HA config-flow REST sequence (admin token required):

```text
POST /api/config/config_entries/flow        {"handler": "event_processor"}
POST /api/config/config_entries/flow/{id}   {"host":"127.0.0.1","port":8095,"token":"..."}
```

HA WebSocket auth sequence:

```text
connect ws://<ha>:8123/api/websocket
recv {"type":"auth_required"}
send {"type":"auth","access_token":"..."}
recv {"type":"auth_ok"}
```

Useful HA state checks:

```text
binary_sensor.garage_motion_detector
switch.garage_motion_detector_armed
alarm_control_panel.garage_security
alarm_control_panel.house_security_indoors
sensor.electricity_meter_energy
number.garage_motion_detector_multi_trigger_rate
event.dash_button_pressed
event.panic_button_pressed
button.event_processor_panic
notify.event_processor_telegram
```

Known live counts (phase 1):

```
hub snapshot: 43 devices, 71 valid links, 39 inputs, 12 outputs
HA: 32 event_processor devices, 120 event_processor entities
   5 alarm panels, 34 switches, 25 numbers, 20 binary sensors, 18 events,
   13 buttons, 4 sensors, 1 notify
17 Ring devices skipped
```

Deployment paths:

```
HA config:      /home/tai/home-assistant/config
HA component:   /home/tai/home-assistant/config/custom_components/event_processor
Event processor:/home/tai/event-processor
Hub data:       /home/tai/event-processor/data
Hub token:      /home/tai/event-processor/data/hub_api_token
DB backup:      /home/tai/event-processor/data/event-processor.db.bak-20261018204347
```

---

## 11. HA-native camera/link orchestration assessment (2026-10-19)

This section records the follow-up research across `event-processor`,
`/home/tai/dev/remote-monitor`, and `/home/tai/dev/snapshot-processor`, plus
the HA 2026.10.2 automation/action model. It refines the link migration in
§7.4 and the dependency assessment in §7.8. It does not change the phase-1
boundary: the hub remains the security authority.

### 11.1 Bottom line

HA can coordinate this chain, but it cannot guarantee it. The correct split is:

1. **Java hub** — detection/debounce, latch, multi-trigger, escalation, input
   armed state, output enabled/rate limits, and the actual RabbitMQ publish.
2. **HA** — user intent and link ownership: `event.received` → camera snapshot
   action; dashboards, Logbook, manual control, and future schedules.
3. **snapshot-processor** — Foscam capture, YOLO/Rekognition, image storage.
4. **TBot** — Telegram image filtering/batching/rate limiting/SNS fallback.

Do **not** move the detection filter chain into HA. Do **not** use
`camera.snapshot` for this flow. Do **not** put camera credentials in HA.

### 11.2 Current flow and link map

```text
remote-monitor / Ring / MQTT devices
        │
        ├── RabbitMQ topic exchange home_automation (MessagePack State)
        └── MQTT broker (ring/#, meter/#, sensor/#, homeassistant/#)
                        │
                        ▼
Java Event.run()
  ├── identity + heartbeat / device discovery
  ├── input config fetch
  ├── wouldTriggerOutput()
  ├── trigger latch / multi-trigger / input armed
  ├── linked outputs
  └── output enabled / trigger_interval
                        │
                        ▼
RabbitMQ direct exchange home_automation_control
  ├── event.trigger.snapshot  → snapshot-processor
  ├── event.trigger.sms       → Python TBot
  ├── event.trigger.ioboard   → remote-monitor relay
  └── event.trigger.bluetooth → l2ping output
                        │
                        ▼
snapshot-processor
  ├── Foscam CGI capture
  ├── YOLO / AWS Rekognition
  ├── save + optional Google Drive upload
  └── publish image event back to home_automation
                        │
                        ▼
Java Event.run() sees a Camera input with image bytes
  └── Camera → SMS link
                        │
                        ▼
event.trigger.sms → Python TBot → Telegram
```

The `Camera` device is dual-role: it is an output (snapshot trigger) and an
input (image bytes). That is why the current routing has two stages:

1. motion/contact/button input → `Camera` output → snapshot-processor;
2. `Camera` input (image) → `SMS` output → TBot → Telegram.

`remote-monitor` samples the ADC inputs every 0.1 s, applies its own tolerance
and history debounce, and publishes only state changes or a 5 s heartbeat.
Java applies the latch/multi-trigger/armed/output filters on top. This is why
HA should consume Java's `input_triggered` outcome, not raw ADC events.

Valid-link map from the live SQLite snapshot and `output_config` rows:

- **71 valid links** = **31 camera links** + **40 non-camera links**.
- **31 camera links** = **23 non-Ring** + **8 Ring siren**.
  - The 8 Ring siren → camera links come from the UUID siren input in the
    `Ring` group; keep them in the hub until Ring is owned by HA.
- **40 non-camera links** include:
  - **8 Camera → SMS links** (the image-to-Telegram path);
  - **17 Ring SMS links**;
  - **15 other non-Ring links** (SMS/buzzer/l2ping).
- The 23 non-Ring camera links come from 8 inputs:
  - App Dash Button (7 camera links);
  - Garage Main Door Left / Right (3 each);
  - Yard, Patio, Garage, Back Door, Pool motion detectors (3/2/2/2/1).
- Eight camera outputs exist: Back Door, Back Gate, Driveway, Garage, Home,
  Patio, Pool, Yard. All eight have `trigger_topic=event.trigger.snapshot`
  and `trigger_interval=1` in the reference snapshot.
- Apart from the Ring siren's 8 camera links, the Ring inputs link only to
  SMS. Ring motion/contact does not trigger Foscam snapshots today; adding
  that would be a new HA-owned capability after the Ring integration is
  configured.

Consequence: the 23 non-Ring camera links can be represented by **8 HA
automations, one per camera**, not 23 individual link automations.

### 11.3 How much HA can coordinate

| Need | HA capability | Finding |
|---|---|---|
| Trigger on a filtered motion event | `event.received` trigger (HA 2026.10) | **Excellent fit.** The existing event entities already expose the Java-filtered events; no new bridge is needed. |
| Run motion → camera actions | Automations/scripts, sequential actions | Good for short orchestration; actions run in order. |
| Call snapshot-processor | No native action; use `button.<camera>_snapshot` or `event_processor.trigger_output` | **Good.** The button calls the hub, which publishes the same RabbitMQ command Java uses. |
| Take a still directly | `camera.snapshot` | **Wrong primitive.** It writes a file and returns no image; it requires an HA camera entity and credentials; it does no detection. |
| Wait for recognition | `wait_for_trigger` on a result event | Possible, but not durable: an HA restart aborts the wait. |
| Return recognition data from a service | Custom service with `SupportsResponse` | Possible, but holds the automation open; still not durable. |
| Call an external HTTP service | `rest_command` | 10-second default timeout; not ideal for capture/detection. |
| Run a shell/curl command | `shell_command` | 60-second hard timeout; HA explicitly says it is not for long-running processes. |
| Send Telegram images | `notify` entity is text-only; `telegram_bot.send_photo` exists | The existing TBot path is better; do not add a second polling bot. |
| Durable retry/queue | Not native | Use the hub/RabbitMQ; HA is not a job queue. |

HA behavior that matters for this migration:

- **`event.received` is native in 2026.10.** It targets event entities and
  matches `event_type`; it is implemented in
  `homeassistant/components/event/trigger.py` in the running 2026.10.2
  container.
- **Event entities restore their last event across restart, but HA does not
  replay events missed while HA was down.** The hub WebSocket also has no
  replay; the 30 s safety poll repairs state, not missed discrete events.
- **Automation runs are not durable.** `wait_for_trigger` and `delay` are
  aborted by a restart; HA docs recommend timers when a wait must survive a
  restart.
- **HA cannot create automations programmatically.** Ship a blueprint and
  importable YAML; the user owns the automation objects.
- **HA has no native RabbitMQ client.** The hub API is the correct boundary;
  do not add a broker client to the HA component.
- **The Foscam integration has no motion-detection entity.** It provides a
  camera, switches, numbers and PTZ; use `go2rtc`/RTSP for live view and keep
  snapshot-processor for capture/detection.

### 11.4 Options considered

| Option | What HA owns | Verdict |
|---|---|---|
| **A. HA link orchestration, hub detection/media** | HA triggers on filtered events and presses camera snapshot buttons. Java keeps debounce/latching; snapshot-processor and TBot remain unchanged. | **Recommended.** Most HA-native without making HA the security engine. |
| **B. HA synchronous workflow** | HA calls `camera.snapshot` or a custom capture action, waits for recognition, then sends Telegram. | Brittle: HA restart loses the run; camera credentials/image handling get worse; the existing bot's filtering/batching/SNS behavior would be lost. |
| **C. Full HA-native** | HA owns motion detection, camera capture, recognition orchestration and Telegram. | Most “native,” but makes HA part of the security path and requires reimplementing latch/multi-trigger/escalation/rate limits. |
| **D. Hub-only with HA UI** | HA only shows dashboards, arm/disarm and manual snapshot buttons. | Lowest risk, but does not move link behavior into HA. |

### 11.5 Recommended target architecture

```text
remote-monitor / Ring
        │ RabbitMQ / MQTT
        ▼
Java EventProcessor
  ├── debounce / latch / multi-trigger / escalation
  ├── input armed / auto-schedule gate
  ├── hub event: input_triggered
  └── hub-owned links only
        │
        ▼
HA event entity
  └── event.received trigger
        │
        ▼
HA automation / blueprint
  └── button.<camera>_snapshot
        │
        ▼
Hub API → RabbitMQ control exchange
        │
        ▼
snapshot-processor
  ├── Foscam capture
  ├── YOLO / Rekognition
  └── image event back to hub
        │
        ▼
Java Camera input → Camera→SMS link
        │
        ▼
Python TBot → Telegram
```

This keeps every existing security behavior and media behavior intact while
moving only the **link decision** for selected camera links into HA.

### 11.6 Per-link migration design

#### 11.6.1 Data model: `managed_by` on `output_link`

Add a `managed_by` column to `output_link`:

- `hub` (default) — Java executes the link.
- `ha` — Java skips the link; an HA automation owns it.

Add a global override, `LINKS_SOURCE`:

- `auto` (new default) — Java executes `hub` links and skips `ha` links.
- `hub` — rollback; Java executes all links, ignoring `managed_by`.
- `ha` — full HA ownership; Java executes no links and only emits events.

This is a refinement of the `LINKS_SOURCE=hub|ha` idea in §7.4. The per-link
field allows a staged migration: camera links can move to HA while SMS,
buzzer, l2ping and Ring links stay in the hub.

#### 11.6.2 Java behavior — skip, do not filter at the API

Do **not** filter `ha` links out of `/api/output_link`. Java must still see
that the input has linked outputs so it reaches the `input_triggered` publish.
The current `Event.run()` returns early when `linkedOutputs == null`; if the API
returns no links, HA never receives the event.

Correct sequence:

1. Java fetches all links with `managed_by`.
2. Java emits `input_triggered` after the latch/multi-trigger/armed checks.
3. In the output loop, skip `managed_by=ha` links.
4. Execute `hub` links exactly as today.
5. `LINKS_SOURCE=hub` forces all links back to Java; `ha` disables all.

`OutputConfig` gains a `managed_by` property; `OutputLink`/`api_output_link`
must expose it. Invalidate the Java config cache for the input whenever
`managed_by` changes.

#### 11.6.3 What moves first

Set `managed_by='ha'` only for the **23 non-Ring camera links**. Keep:

- all SMS links in the hub, including Camera → SMS image delivery;
- all buzzer and l2ping links in the hub;
- the 8 Ring siren → camera links in the hub until Ring is owned by HA.

Create **8 HA automations**, one per camera. Example for Garage Camera:

```yaml
alias: "Garage Camera snapshot on security event"
mode: queued
max: 10

triggers:
  - trigger: event.received
    target:
      entity_id:
        - event.garage_motion_detector_motion_events
        - event.garage_main_door_left_open_close_events
        - event.garage_main_door_right_open_close_events
        - event.app_dash_button_pressed
    options:
      event_type:
        - motion
        - opened
        - pressed

actions:
  - action: button.press
    target:
      entity_id: button.garage_camera_snapshot
```

Why `button.press`:

- It is the existing HA-native camera action.
- It calls the hub, which publishes the same RabbitMQ command Java uses.
- HA never sees camera credentials.
- The snapshot button already exists for each Camera output.

For source correlation later, use `event_processor.trigger_output` with a
payload containing the source device/event, because `button.press` does not
carry source data. The hub event payload should include `device_key` and
`event_detail` so the automation can template the source.

#### 11.6.4 Keep the image/Telegram path in the hub

Do **not** move Camera → SMS to HA yet. The current path already handles:

- image bytes over RabbitMQ;
- person/face filtering (`TELEGRAM_IMAGE_SEND_ONLY_WITH_PEOPLE`);
- image batching;
- Telegram rate limiting;
- SNS fallback.

HA's generic `notify` entity is text-only. The `event_processor.notify`
service's `image_url` is currently passed through as a caption link, not
fetched as an image. Rebuilding this in HA would be a regression unless the
filtering/batching/rate-limiting behavior is ported first.

If HA must eventually send the final Telegram message, treat that as a
separate project:

1. give snapshot-processor an authenticated image URL or shared media path;
2. add image bytes/URL support to the hub notify path;
3. add a `camera_detection` result event with the image reference;
4. have HA call `event_processor.notify` with the image;
5. preserve the existing bot's filtering/batching/rate limiting.

#### 11.6.5 Required event-contract changes

- Add `event_detail` to the Java `input_triggered` hub payload. The camera
  event entity declares `person`, `vehicle`, `animal` and `package`, but
  currently only receives `trigger_state` such as “N bytes of image data
  present”; the declared types are effectively unused.
- Add `device_key` to the event data so HA automations can correlate the
  source without parsing entity IDs.
- Optionally add `source_device_key` and `trace_id`; snapshot-processor must
  propagate the source from the output command if end-to-end correlation is
  required. The hub API currently hardcodes `active_input` to “Home
  Assistant” for HA-originated output commands.
- Add `requested_by`/`source` and `triggered`/`reason` to the hub's
  `output_triggered` event.

#### 11.6.6 Required output-trigger semantics

The current `POST /api/v1/outputs/{key}/trigger` publishes directly and
bypasses:

- output `device_enabled`;
- output `trigger_interval`.

If HA owns camera links, that loses two filters. Add:

```json
{
  "payload": {
    "source_device_key": "Garage Motion Detector"
  },
  "source": "ha",
  "force": false
}
```

Behavior:

- `source=ha` — enforce `device_enabled` and `trigger_interval`.
- `source=manual` / `force=true` — manual snapshot button may override.
- Return `{"ok": true, "triggered": false, "reason": "disabled"|"rate_limited"}`
  when filtered; return `502` only when the RabbitMQ publish fails.
- Use the source device in `active_input` when provided, instead of the
  hardcoded “Home Assistant”.

#### 11.6.7 Reliability prerequisites

Before calling HA the owner of any security-critical link:

- Add an event-bus epoch/session ID to the hub `hello`/info.
- Persist the last seen sequence in HA; on reconnect fetch
  `/api/v1/events?since=N` and replay recent events only.
- Reset on epoch change because the hub `seq` restarts at 1 on process restart.
- Ignore replayed events older than a short freshness window.
- Make the HA event entity process all new events for a device, not just the
  latest one per coordinator update.
- Increase the WebSocket queue if HA is expected to process bursts.

### 11.7 Implementation plan

#### Phase 0 — safety and data model

1. Back up the production SQLite DB as UID 999.
2. Add `managed_by` to `output_link` with default `hub`.
3. Add `OutputLink.managed_by` to the SQLAlchemy model and
   `OutputConfig.managed_by` to the Java model.
4. Update `HubStore._load_rows` and `HubStore.set_links` to expose/preserve
   `managed_by`.
5. Update the Flask `output_link` page and hub `set_links` so editing links
   does not reset HA-owned links to `hub`.
6. Add a `set_link_owner` action/endpoint (or a one-time migration script)
   that updates `managed_by` and invalidates the Java cache.

#### Phase 1 — event contract

1. Add `event_detail`, `device_key`, and optional source/trace fields to the
   Java `input_triggered` payload.
2. Add `source`/`force` to the output-trigger request and enforce
   `device_enabled`/`trigger_interval` for `source=ha`.
3. Add `requested_by`/`source` to the `output_triggered` hub event.
4. Add tests for `managed_by` selection, event payloads and output limits.

#### Phase 2 — HA blueprint and pilot

1. Add `blueprints/automation/event_processor/camera_snapshot.yaml`.
2. Copy it in `deploy_ha.sh` to
   `.../config/blueprints/automation/event_processor/`.
3. Create one automation for a non-critical camera (Patio or Pool).
4. Set only that camera's non-Ring links to `managed_by='ha'`.
5. Verify motion → HA → snapshot → detection → Telegram, with no duplicate
   snapshot and with output disabled/interval respected.
6. Roll back with `LINKS_SOURCE=hub` or `managed_by='hub'` plus disabling the
   automation.

#### Phase 3 — expand

1. Create the remaining seven camera automations.
2. Keep the 8 Ring siren camera links in the hub until Ring integration owns
   the siren.
3. Decide whether to migrate the remaining 23 non-Ring non-camera links or
   leave them in the hub.

#### Phase 4 — harden

1. Add event replay/epoch handling.
2. Add HA tests for the event entity and blueprint.
3. Add a snapshot failure/result event if HA needs to display delivery state.
4. Add a dashboard with camera snapshot buttons, alarm panels, event entities
   and last-detection timestamps.

#### Phase 5 — optional Ring/notification work

- Move Ring siren → camera links to HA after Ring is configured.
- Move Ring motion → camera links if desired; Ring motion currently links
  only to SMS.
- Add a `camera_detection` event for HA dashboards/automations on
  person/vehicle.
- Only then consider moving the final Telegram notification to HA.

### 11.8 Critical findings and implementation traps

1. **`input_triggered` currently lacks `event_detail`.**
   `Event.java` publishes `device_label`, `device_type` and `trigger_state`.
   For a Camera, `trigger_state` is “N bytes of image data present”, so the
   HA camera event entity cannot emit `person`/`vehicle` even though those
   event types are declared. Add `device.getEventDetail()` to the payload.
2. **`publish_output_command` bypasses output gates.**
   It publishes whenever the output exists; it does not check
   `device_enabled` or `trigger_interval`. HA-triggered snapshots would
   therefore bypass two security semantics. Enforce them for `source=ha`.
3. **Do not filter HA links out of `/api/output_link`.**
   `Event.run()` returns before `input_triggered` when `linkedOutputs` is null.
   If the API hides HA-managed links, HA never sees the event. Return all links
   with `managed_by` and skip `ha` inside the output loop.
4. **`set_links` deletes and reinserts rows.**
   The Flask `output_link` page and `HubStore.set_links` currently delete all
   links and insert fresh rows. That resets `managed_by` to `hub` and can
   create duplicate snapshots. Preserve ownership for retained outputs.
5. **`active_input` is hardcoded to “Home Assistant”.**
   For HA-originated camera commands, the source motion device is lost.
   Accept `source_device_key`/`source_device_label` and use them in
   `active_input`; snapshot-processor can then propagate the source.
6. **Event entities must be enabled.**
   `event.received` only fires for existing, enabled event entities. The
   integration's `enable_event_entities` option defaults to true; document it
   and verify the motion/contact event entities are enabled.
7. **Hub sequence numbers reset on restart.**
   `HubEventBus._seq` starts at 0 on every process start. HA event entities
   remember `_last_seq`; after a hub restart they can ignore new events until
   the new sequence catches up. Add an epoch/session ID and reset HA state on
   change.
8. **The hub WebSocket has no replay.**
   `/api/v1/events?since=N` exists, but the WebSocket only sends new events.
   A short disconnect can drop motion events; the safety poll repairs state,
   not discrete events. Add reconnect replay with a freshness bound.
9. **The event entity processes only the latest event per update.**
   `event.py` iterates the coordinator buffer in reverse and breaks after the
   first new event for the device. A burst can lose all but the last event.
   Process all new events in sequence order, or explicitly dedupe at the
   coordinator.
10. **snapshot-processor captures serially.**
    `Snapshot` is a single `ZmqRelay`; the unused `capture_threads` suggests a
    future worker pool. A dash button with 7 camera links will queue captures.
    This is existing behavior, but HA makes multi-camera links easier to use,
    so latency becomes more visible.
11. **`camera.snapshot` is not a remote snapshot action.**
    HA source `camera/services.py` writes the image to a file and returns no
    image. It requires an HA camera entity and credentials. Use
    `button.<camera>_snapshot` or `event_processor.trigger_output` instead.
12. **Telegram `image_url` is not an image send.**
    `NotifyRequest.image_url` is passed to `send_telegram`, which only adds a
    `url` caption link to the bot message. The bot sends images only when the
    `BotMessage.image` bytes field is populated (the existing Camera→SMS
    path). Do not assume HA notify can send the snapshot without code changes.
13. **Camera credentials must stay in the hub.**
    `output_config.device_params` is plaintext in SQLite and is passed over
    RabbitMQ to snapshot-processor. Never put it in HA attributes, diagnostics,
    logs, or an HA camera entity for this flow.
14. **HA downtime stops HA-owned links.**
    If `managed_by='ha'`, Java skips the link. A HA outage means no snapshot,
    even though Java detection continues. Keep critical camera links in the
    hub until reliability and fallback are proven.

### 11.9 Open decisions

- **Availability posture.** Do you want HA to own only non-critical camera
  links (recommended), all camera links, or should the hub automatically take
  over when HA is offline? The last option needs a health/lease mechanism and
  duplicate-suppression design.
- **Notification ownership.** Keep Camera→SMS→TBot (recommended) or move the
  final Telegram send to HA? Moving it requires an image URL/shared media path
  and a new notify implementation that preserves filtering/batching/rate
  limiting.
- **Ring timing.** Keep the 8 Ring siren camera links in the hub until Ring is
  owned by HA, or add the Ring integration now and move them? Ring motion
  currently links only to SMS, so there is no Ring motion→camera behavior to
  preserve.
- **Camera live view.** Use `go2rtc`/RTSP for live view and keep
  snapshot-processor as the only snapshot credential owner. Add Foscam camera
  entities only if PTZ/live controls are needed; do not use them for this
  snapshot path.
- **Non-camera links.** Migrate only camera links first. Decide later whether
  SMS/buzzer/l2ping links should become HA automations or stay hub-owned.

### 11.10 Sources and evidence

Official HA documentation (2026.10.2):

- Event received trigger:
  <https://www.home-assistant.io/triggers/event.received/>
- Event entity:
  <https://www.home-assistant.io/integrations/event/>
- Camera snapshot action:
  <https://www.home-assistant.io/actions/camera.snapshot/>
- Camera integration:
  <https://www.home-assistant.io/integrations/camera/>
- Wait for a trigger:
  <https://www.home-assistant.io/building-blocks/wait_for_trigger>
- Automation modes:
  <https://www.home-assistant.io/docs/automation/modes/>
- RESTful command:
  <https://www.home-assistant.io/integrations/rest_command/>
- Shell command:
  <https://www.home-assistant.io/integrations/shell_command/>
- Notify:
  <https://www.home-assistant.io/integrations/notify/>
- Telegram bot:
  <https://www.home-assistant.io/integrations/telegram_bot/>
- Foscam:
  <https://www.home-assistant.io/integrations/foscam/>

Local code verified for this assessment:

- `src/main/java/tailucas/app/device/Event.java` — latch, multi-trigger,
  output enabled/interval, `input_triggered` payload.
- `src/main/java/tailucas/app/device/config/OutputConfig.java` — output fields
  and `toString`.
- `src/main/java/tailucas/app/provider/DeviceConfig.java` — `/api/output_link`
  fetch/cache.
- `app/__main__.py` — `OutputLink`, `api_output_link`, Flask link editor,
  `EventProcessor`, `TBot`.
- `app/hub_api.py` — link snapshot, `publish_output_command`, event bus,
  output trigger endpoint, notify placeholder.
- `custom_components/event_processor/event.py` — event types and
  `_handle_coordinator_update`.
- `custom_components/event_processor/button.py` — snapshot button.
- `custom_components/event_processor/coordinator.py` — WebSocket and event
  buffer.
- `/home/tai/dev/snapshot-processor/app/__main__.py` — `Snapshot`,
  `ObjectDetector`, RabbitMQ image event.
- `/home/tai/dev/remote-monitor/app/__main__.py` — ADC sampling, local
  debounce, RabbitMQ publish.

Live data used for the link counts: reference SQLite snapshot at
`/home/tai/dev/event-processor/event-processor.db` (`immutable=1`), plus the
production HA entity counts recorded in §1. Re-verify counts before executing
a migration; they are snapshot-specific.

---

## 12. Instruction to future agents

When asked to extend or migrate this project further:

1. Read this file plus `coding.md`, `build.md`, `container.md`, `config.md`,
   `mq.md`, `logging.md`, and `observability.md` first.
2. Never make HA the only runtime dependency for security processing. The hub
   must run headless with its last-known-good config.
3. Do not duplicate Ring or camera devices. Check `source` before creating a
   device entry.
4. Do not copy camera credentials, hub tokens or broker topics into HA
   attributes, diagnostics or logs.
5. Keep the hub API as the single writer while Flask and HA coexist.
6. Add an explicit source flag and a rollback for every behavior move
   (`LINKS_SOURCE`, `managed_by`, `RING_SOURCE`).
7. Run `make lint`, `make ha-lint`, the hub API tests and the Java tests before
   deploying.
8. Preserve UID/GID 999 ownership of `/data` files.
9. Use the HA config-flow REST API for 2026.10, not the old WebSocket command.
10. Update this file when a new HA behavior is verified or a new integration
    constraint is discovered.
