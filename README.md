<a name="readme-top"></a>

[![Contributors][contributors-shield]][contributors-url]
[![Forks][forks-shield]][forks-url]
[![Stargazers][stars-shield]][stars-url]
[![Issues][issues-shield]][issues-url]
[![MIT License][license-shield]][license-url]

## About The Project

### Overview

**Note 1**: See my write-up on [Home Security Automation][blog-url] which provides an architectural overview of how this project works with others in my collection.

**Note 2**: While I use the word *Automation* in these projects, there is no integration with sensible frameworks like [openHAB][oh-url] or [Home Assistant][ha-url]... not yet at least. The goal behind this project was a learning opportunity by employing specific technologies and architectural patterns. The parts most likely to be useful are integrations with libraries like Flask, FastAPI, ZeroMQ, and RabbitMQ, where seamless behavior comes after much trial and error.

### Core Functionality

This project is a comprehensive **event hub for home automation** designed with resource constraints in mind (runs on Raspberry Pi). It extends [base-app][baseapp-url] and takes a git submodule dependency on [pylib][pylib-url]. Related projects include [Snapshot Processor][snapshot-processor-url] and [Remote Monitor][remote-monitor-url].

**Key Features:**

* **Device Registration & Discovery**: Automatically registers devices as input/output sources via [RabbitMQ][rabbit-url] heartbeat messages. Supports multi-role devices (input and output on same source).
* **Web UI Dashboard** (Flask + FastAPI):
  - Display available output devices with enable/disable controls
  - Schedule-based automation for device enable/disable
  - Network accessibility via [ngrok][ngrok-url] tunnel or [Tailscale](https://tailscale.com/)
  - Login/authentication system with role-based access
* **Input-Output Linking**: Create and configure input-to-output triggers
  - Configuration persisted to local [SQLite][sqlite-url] database
  - Database schema available in `config/db_schema.sql`
  - Automated backups to Amazon S3 via cron job (`backup_db.sh`)
* **Message Processing**: Routes messages from inputs to configured outputs according to rules
* **Telegram Bot Integration**: Built-in SMS output via Telegram bot with optional AWS SNS fallback, plus a `/ring` device-status command
* **MQTT Support**: Full [mDash][mdash-url] device discovery integration for MQTT message handling
* **Metrics & Monitoring**:
  - Audit trail in local database
  - Meter device updates sent to InfluxDB bucket
  - Sentry error tracking integration
* **High Availability**: Optional leader election for multi-instance deployments

### Package Structure

This application has grown to 2273 lines in `app/__main__.py`, demonstrating patterns for complex, multi-threaded Python applications. Significant functionality has been factored into the [pylib][pylib-url] shared library.

**Main Application Components** (`app/__main__.py`):

* **`EventProcessor`** (line 1356+): Core event processing thread responsible for the main application loop. Inherits from `AppThread` and manages RabbitMQ message routing through `MQConnection`. Implements the central business logic for connecting device inputs to configured outputs.

* **`MQConnection`**: Manages RabbitMQ exchange connectivity, channel lifecycle, error recovery, and graceful shutdown. Inherits from `AppThread` for thread tracking and `Closable` for ZeroMQ socket management.

* **`TBot`** (line 1786+): Telegram bot wrapper using `asyncio` for async message handling. Provides SMS-like notifications via Telegram with optional AWS SNS fallback. Handles commands, photo uploads, and multi-user access control.

* **`HeartbeatFilter`**: Demonstrates ZeroMQ internal message relay pattern. Receives device heartbeat/status updates from MQTT/RabbitMQ sources, updates device inventory, and relays messages to `EventProcessor` for processing. See blog post for architectural details.

* **Flask Web Server**: Multi-threaded synchronous web application for dashboard, configuration management, and user authentication. Templates render device controls, configuration forms, audit trails, and metrics visualization.

* **FastAPI/uvicorn**: Modern async web framework for API endpoints and real-time capabilities. Provides alternative to Flask for new API development.

* **SQLAlchemy ORM**: Async database layer for SQLite persistence. Manages device configurations, input-output relationships, audit logs, and metrics.

* **Background Schedulers**: Handles scheduled enable/disable of devices and periodic tasks (heartbeat reports, metrics collection, database backups).

**Web UI Templates** (`templates/`):
- `login.html`: User authentication
- `index.html`: Main dashboard with real-time device controls
- `config.html`: Application-level configuration
- `input_config.html` / `output_config.html`: Device-specific settings
- `input_link.html` / `output_link.html`: Input-output automation rules
- `event_log.html`: Audit trail of all system events
- `metrics.html`: Performance metrics and monitoring
- `layout.html`: Base template with navigation and styling

**Static Assets** (`static/`):
- Bootstrap CSS/JS for responsive UI
- Font Awesome icons
- Custom clock picker for schedule configuration
- Favicon

**Integration Architecture:**

The application demonstrates professional integration patterns:

- **ZeroMQ**: Thread-safe inter-component messaging with proper socket lifecycle
- **RabbitMQ**: External device message queue with exchange-based routing
- **MQTT**: IoT device connectivity with mDash automatic discovery
- **Flask/FastAPI**: Dual web framework approach (legacy + modern async)
- **SQLAlchemy**: Async ORM with relationship mapping and data validation
- **Telegram Bot Framework**: Async command handling and media support
- **1Password Secrets Automation**: Encrypted credential and configuration management
- **Sentry SDK**: Production error tracking with integrations for Flask, async, threading
- **Permit.io**: Role-based access control (RBAC) for web UI
- **InfluxDB**: Time-series storage for meter/sensor readings
- **AWS Services**: S3 for database backups, SNS for SMS fallback
- **ngrok/Tailscale**: Network tunneling for remote access

See [tailucas-pylib][pylib-url] for shared architectural patterns and utilities.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Built With

Technologies that help make this project useful:

[![1Password][1p-shield]][1p-url]
[![AWS][aws-shield]][aws-url]
[![Bootstrap][bootstrap-shield]][bootstrap-url]
[![Font Awesome][fontawesome-shield]][fontawesome-url]
[![Docker][docker-shield]][docker-url]
[![InfluxDB][influxdb-shield]][influxdb-url]
[![ngrok][ngrok-shield]][ngrok-url]
[![MQTT][mqtt-shield]][mqtt-url]
[![RabbitMQ][rabbit-shield]][rabbit-url]
[![Poetry][poetry-shield]][poetry-url]
[![Python][python-shield]][python-url]
[![Flask][flask-shield]][flask-url]
[![Sentry][sentry-shield]][sentry-url]
[![SQLite][sqlite-shield]][sqlite-url]
[![Telegram][telegram-shield]][telegram-url]
[![ZeroMQ][zmq-shield]][zmq-url]

Also:

* [mDash][mdash-url]
* [SQLAlchemy][sqlalchemy-url]

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- GETTING STARTED -->
## Getting Started

Here is some detail about the intended use of this project.

### Prerequisites

Beyond the Python dependencies defined in [pyproject.toml](pyproject.toml), the project requires:

* **[1Password Secrets Automation][1p-url]**: Runtime credential and configuration management (paid service with free tier)
* **[Sentry][sentry-url]**: Error tracking and monitoring (free tier available)
* **[RabbitMQ][rabbit-url]**: Message broker for device communication (self-hosted or managed service)
* **[MQTT Broker][mqtt-url]**: IoT device messaging (self-hosted like Mosquitto or managed service)
* **[InfluxDB][influxdb-url]**: Time-series database for meter metrics (optional, self-hosted or cloud)
* **[Telegram Bot][telegram-url]**: SMS-like notifications via Telegram (free)
* **[mDash][mdash-url]**: Device discovery service (free tier available)

Optional services:
* **[ngrok][ngrok-url]**: Remote access tunnel (free tier available)
* **[Tailscale](https://tailscale.com/)**: VPN-based remote access (free tier available)
* **AWS Services**: S3 (database backups), SNS (SMS fallback)
* **[InfluxDB Cloud][influxdb-url]**: Managed metrics storage
* **[Sentry][sentry-url]**: Production error tracking

### Required Tools

Install these tools and ensure they're on your environment `$PATH`:

* **`make`**: Build orchestration (GNU Make) - https://www.gnu.org/software/make/
* **`docker`** and **`docker-compose`**: Container runtime and composition - https://docs.docker.com/engine/install/
* **`mvn`** (Maven 3.9+): Java build tool - https://maven.apache.org/download.cgi
* **`uv`**: Python package manager - https://docs.astral.sh/uv/getting-started/installation/

For local development (optional):
* **`java`** and **`javac`**: Java 25+ runtime/compiler (Amazon Corretto recommended)
* **`python3`**: Python 3.12+ runtime
* **`poetry`**: Legacy dependency management (if not using uv)

### Installation

0. **:stop_sign: Prerequisites - 1Password Secrets Automation Setup**

   This project relies on [1Password Secrets Automation][1p-url] for configuration and credential management. A 1Password Connect server container must be running in your environment.

   Your 1Password Secrets Automation vault must contain an entry called `ENV.event-processor` with the following configuration variables:

   | Variable | Purpose | Example |
   |---|---|---|
   | `APP_FLASK_DEBUG` | Flask debug mode | `false` |
   | `APP_FLASK_HTTP_PORT` | Flask web server port | `8080` |
   | `APP_NAME` | Application identifier | `event-processor` |
   | `AWS_CONFIG_FILE` | AWS configuration path | `/home/app/.aws/config` |
   | `AWS_DEFAULT_REGION` | AWS region for S3/SNS | `us-east-1` |
   | `BACKUP_S3_BUCKET` | S3 bucket for DB backups | `my-backup-bucket` |
   | `CRONITOR_MONITOR_KEY` | Cronitor health check key | *specific to your account* |
   | `DEVICE_NAME` | Container hostname | `event-processor-a` |
   | `HC_PING_URL` | Healthchecks.io URL | *specific to your check* |
   | `HEALTHCHECKS_BADGE_CSV` | Healthchecks badge URLs | *project specific* |
   | `INFLUXDB_BUCKET` | InfluxDB bucket name | `meter` |
   | `LEADER_ELECTION_ENABLED` | Enable leader election | `false` |
   | `MDASH_API_BASE_URL` | mDash discovery API | `https://mdash.net/api/v2/devices` |
   | `MDASH_APP_CONFIG_MQTT_PUB_TOPIC` | mDash config topic | `app.mqtt_pub_topic` |
   | `MDASH_DEVICE_TAGS_CSV` | Filter devices by tags | `meter,sensor` |
   | `MQTT_METER_RESET_TOPIC` | Meter reset control topic | `meter/electricity/control` |
   | `MQTT_PUB_TOPIC_CSV` | MQTT topics to subscribe | `meter/electricity/#,sensor/#` |
   | `MQTT_SERVER_ADDRESS` | MQTT broker IP/hostname | `192.168.1.100` |
   | `NGROK_CLIENT_API_PORT` | ngrok management API port | `4040` |
   | `NGROK_ENABLED` | Enable ngrok tunneling | `true` |
   | `NGROK_TUNNEL_NAME` | ngrok tunnel name | `frontend` |
   | `OP_CONNECT_HOST` | 1Password Connect server URL | `http://1password-connect:8080` |
   | `OP_CONNECT_TOKEN` | 1Password Connect token | *specific to your server* |
   | `OP_VAULT` | 1Password vault ID | *specific to your vault* |
   | `OUTPUT_TYPE_BLUETOOTH` | Bluetooth device type | `l2ping` |
   | `OUTPUT_TYPE_SNAPSHOT` | Snapshot device type | `Camera` |
   | `OUTPUT_TYPE_SWITCH` | Switch device types | `switch,Buzzer` |
   | `OUTPUT_TYPE_TTS` | Text-to-speech type | `TTS` |
   | `RABBITMQ_EXCHANGE` | RabbitMQ exchange name | `home_automation` |
   | `RABBITMQ_SERVER_ADDRESS` | RabbitMQ broker IP | `192.168.1.100` |
   | `SNS_CONTROL_ENABLED` | Enable SQS control messages | `false` |
   | `SQS_QUEUE` | SQS queue name | `automation-control` |
   | `TABLESPACE_PATH` | SQLite database directory | `/data` |
   | `TELEGRAM_CHAT_ROOM` | Telegram chat ID | *specific to your chat* |
   | `TELEGRAM_IMAGE_SEND_ONLY_WITH_PEOPLE` | Filter image sending | `true` |
   | `TELEGRAM_SMS_FALLBACK_ENABLED` | Use SNS SMS fallback | `false` |
   | `TELEGRAM_USERS_CSV` | Authorized Telegram users | `user1,user2,user3` |
   | `USER_TZ` | Timezone override | `America/New_York` |

   Additional runtime configuration (see [app.conf](config/app.conf)) is automatically populated from 1Password.

1. **Clone the Repository and Initialize Submodules**

   ```bash
   git clone https://github.com/tailucas/event-processor.git
   cd event-processor
   git submodule init
   git submodule update
   ```

2. **Create Docker User and Set Directory Permissions**

   ```bash
   make datadir
   ```

   Ensure you've reviewed [Makefile](Makefile) assumptions about user IDs for Docker (UID/GID 999).

3. **Configure Runtime Environment**

   ```bash
   make configure
   ```

   This generates `docker-compose.yml` and `.env` from your 1Password secrets and `base.env` template.

4. **Build the Docker Image**

   ```bash
   make build
   ```

   Multi-stage Docker build:
   - **Builder stage**: Compiles Java artifacts using Maven 3.9+, Java 25 (Amazon Corretto)
   - **Runtime stage**: Extends `tailucas/base-app:latest` with:
     - Additional system packages (sqlite3, html-xml-utils, wget)
     - Python 3.12+ with uv-managed dependencies
     - Compiled Java application (app.jar)
     - Flask web application
     - FastAPI/uvicorn endpoints
     - Telegram bot integration
     - Database backup scripts
     - Cron job configuration

5. **Run the Application**

   **Foreground (interactive, logs to console)**:
   ```bash
   make run
   ```

   **Background (detached mode, logs to syslog)**:
   ```bash
   make rund
   ```

   The application will:
   - Start RabbitMQ client for device messaging
   - Launch broker client for external integrations
   - Initialize background scheduler for automation rules
   - Start main event processing loop
   - Discover MQTT sources via mDash
   - Start Telegram bot (asyncio)
   - Launch thread nanny (monitors thread health)
   - Start Flask web server (port 8080 by default)
   - Start FastAPI server (port 8085 by default)

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Application Startup Sequence

When `make run` or `make rund` is executed, the application initializes in this order:

1. **Configuration Loading**: Reads from 1Password Secrets Automation and `config/app.conf`
2. **Database Initialization**: Creates/upgrades SQLite schema at `config/db_schema.sql`
3. **Sentry Integration**: Initializes error tracking with Flask and async integrations
4. **Message Queue Setup**: Connects to RabbitMQ broker for device communication
5. **Scheduler Initialization**: Loads scheduled automation rules and device enable/disable timers
6. **Threading & Signals**: Starts thread nanny for health monitoring, installs signal handlers
7. **ZeroMQ Relay**: Initializes HeartbeatFilter for device status message relay
8. **MQTT Discovery**: Queries mDash API to discover and register MQTT devices
9. **Telegram Bot**: Starts async Telegram bot with command handlers
10. **Flask Web Server**: Launches synchronous web server (port 8080) with user authentication
11. **FastAPI Server**: Starts modern async API endpoints (port 8085) via uvicorn
12. **Event Processing Loop**: Main `EventProcessor` thread begins processing messages

All components run concurrently with proper error handling and graceful shutdown on system signals.

## Web Interface

The Flask web application (`localhost:8080`) provides:

- **Dashboard**: Real-time display of registered devices, their status, and manual control buttons
- **Device Management**: Configuration of device properties, input/output types, and capabilities
- **Automation Rules**: Create input-to-output relationships with scheduling (cron-like patterns)
- **Event Log**: Audit trail of all device interactions, state changes, and automated actions
- **Metrics**: Historical visualization of meter readings and system performance
- **Administration**: User management, system settings, database maintenance

## Config API (device info posts)

The Java event processor uses this application as its configuration source
(`CONFIG_HOST`/`CONFIG_HOST_PORT`, published as `8095:8080`). On every device
heartbeat it posts the current device state to `POST /api/device_info`
(`tailucas.app.provider.DeviceConfig`), which the FastAPI server forwards into
the in-process ZeroMQ pipeline.

That post travels over a pooled HTTP/1.1 connection, so both ends must agree on
how long an idle connection may live:

- **Server** - uvicorn's `timeout_keep_alive` is `API_KEEP_ALIVE_SECONDS`
  (default `60`). uvicorn closes an idle connection **without sending a
  response** and does not advertise that timeout to clients.
- **Client** - the JVM exports `-Djdk.httpclient.keepalive.timeout`
  (default `2` seconds, `JAVA_CLIENT_KEEPALIVE_SECONDS`) from
  `app_entrypoint.sh`, so pooled sockets are retired long before the server
  closes them (the JDK default is 1200 s, so it cannot infer the server's
  timer without being told).

The failure these settings prevent looks like this:

```
java.io.IOException: HTTP/1.1 header parser received no bytes
Caused by: java.io.EOFException: EOF reached while reading
```

That is the client writing a request onto a socket the peer closed (or was
closing) before writing a single response byte. The JDK retries a request that
fails on a reused, peer-closed pooled socket, so an isolated occurrence is
usually invisible; a WARN from `DeviceConfig` means *both* attempts failed,
which in practice means the peer closed mid-request: uvicorn starting or
stopping (the Python side needs several seconds to come up - 1Password, Sentry,
database, MQTT discovery), the process being resource-starved (EMFILE), or an
intermediary (Docker's userland proxy on `8095`, the tailnet) tearing the
connection down.

`DeviceConfig` therefore logs the WARN with `attempts`/`uri`/`config_host`/
`config_host_port`, backs off between attempts (the endpoint is idempotent), and
increments the `device_config.post.failures` counter (`reason=io|http_status`).
Many WARNs in the same second across different `device_key`s indicate a
server-wide outage; a lone WARN every few hours points at a keep-alive boundary
race.

## Ring events

Ring devices reach the processor over MQTT (`ring2mqtt`/`ring-mqtt`). The HA
discovery topic shape is:

```
ring/<location-id>/<entity>/<device-id>[/<subject>/<update-type>]
```

where `update-type` is `status` (availability), `attributes` (device data),
`state` (the entity's state), `image` (a camera snapshot) or `command` (an
inbound instruction), and `subject` names the entity. The three domains the
bridge publishes all parse into the same model:

| Domain | Example device | Subjects |
|---|---|---|
| `alarm` | alarm base station, keypads, contact/motion sensors, sirens | `alarm`, `contact`, `motion`, `fire`, `police`, `siren`, `battery`, `bypass_mode`, `chirp_tone`, `chirps`, `info`, `volume` |
| `camera` | doorbell / camera | `motion`, `ding`, `stream`, `snapshot`, `battery`, `wireless`, `info`, `motion_detection`, `motion_duration`, `ding_duration`, `snapshot_mode`, `snapshot_interval`, `take_snapshot` |
| `chime` | chime | `snooze`, `snooze_minutes`, `volume`, `nightlight_enabled`, `play_ding_sound`, `play_motion_sound`, `wireless`, `info` |

Camera and chime device ids are twelve-character hex rather than UUIDs; both forms
are used as `input_config.device_key` unchanged. Domains that are only expected to
report (a camera or chime with no input configuration) produce a single
`Device has no input configuration` warning per device rather than one per
message.

Every inbound Ring message is treated as an event, **before** any input
configuration or trigger rules are applied, and reporting is tiered against the
bridge's actual refresh cadence (every device sweeps its entities roughly every
five minutes, ~250 messages per sweep in this installation):

- **Operational events log at `INFO`** — one structured record with a static
  message (`Ring event`) and the fields `ring_topic`, `ring_component_id`,
  `ring_component_name`, `ring_device_id`, `ring_update_type`,
  `ring_update_subject`, `ring_state` and `ring_message_type`. That means a
  `state` change of a trigger-capable entity (`alarm`, `contact`, `ding`,
  `motion`, `fire`, `police`, `siren`), or a device **losing** availability.
- **Telemetry logs at `DEBUG`** — device data (`info`), `battery`, `wireless`,
  `volume`, `brightness`, `chirp_tone`, `bypass_mode`, snapshot/stream settings
  and every `attributes` dump carry no state change an operator acts on. The
  same applies to the healthy `status online` heartbeat the bridge republishes
  for every device on each sweep.
- **Every message increments `ring_event`** with the same context as labels
  (`ring_event_total{application,device,ring_*}` in Prometheus), so nothing is
  lost from the metric side.

Three payload rules keep one message from poisoning the log or the metric store:

- **Oversized payloads are summarised.** Ring publishes camera snapshots as raw
  image bytes on `<camera>/snapshot/image`; any payload longer than
  `MAX_PAYLOAD_TEXT_CHARS` (64 characters) becomes the constant
  `binary_payload` in `state`, which flows to logs, spans and labels. It is a
  constant rather than a size or digest so repeated snapshots reuse one metric
  series instead of creating one per image.
- **Label values are bounded** to the same length, whatever their source.
- **Known non-event update types are quiet.** `image` (a snapshot) and `command`
  (an inbound instruction) are logged at `DEBUG`; any other unmapped update type
  is still a `WARNING`.

Two label rules matter:

- **The label set is fixed.** A Prometheus counter's label names are pinned when
  it first registers, so `Ring.getEventTags()` always emits all seven keys and
  reports context a message does not carry as `unknown` (e.g. a `status`
  message has no `ring_update_subject` or `ring_state`). Adding or dropping a
  key per message shape would raise "invalid number of label values", and the
  shared sanitizer rejects empty label values outright.
- **`ring_update_type` separates signals from heartbeats.** `attributes` and
  `status` messages are periodic device/availability updates, so filter on
  `ring_update_type="state"` when counting real state changes:

```
sum by (ring_update_subject, ring_state) (
  rate(ring_event_total{ring_update_type="state"}[15m])
)
```

Trigger classification is logged afterwards by `Ring.wouldTriggerOutput()` -
`Subject is in a trigger state` at `INFO` (an output will fire), and the
non-triggering variations (`Subject is not a trigger`, `Subject is not in a
trigger state`, `No state information`) at `DEBUG`, because a sweep produces one
of them for every telemetry entity. A trigger-capable entity that is enabled yet
never fires is therefore visible through `ring_event_total` and `triggered`,
not through a stream of classification lines.

If no `ring_event` series appear at all, the Ring bridge is not publishing:
check the `ring-mqtt` container logs for `Refresh token is not valid` and
generate a new token from its web UI before looking at this application.

### Silent-failure guard (startup)

A bridge that cannot authenticate publishes **nothing**, so the only symptom is
absence: no events, no logs, no metrics. Startup therefore refuses to accept
silence as normal. After the MQTT subscription and the Home Assistant discovery
message, the processor waits for the first `ring/#` message:

| Setting | Default | Meaning |
|---|---|---|
| `app.ring-discovery-timeout-seconds` (`APP_RING_DISCOVERY_TIMEOUT_SECONDS`) | `120` | How long to wait; `0` disables the check |
| `app.ring-discovery-required` (`APP_RING_DISCOVERY_REQUIRED`) | `false` | Exit instead of continuing when the check fails |

Outcomes:

- **Information arrives** - `Ring device information is available` at INFO, plus
  `ring_discovery{result="received"}`. `retained_only=true` means only a broker
  replay was seen, so the bridge itself may still be silent.
- **Nothing arrives** - `No Ring device information after MQTT discovery` at
  ERROR, `ring_discovery{result="missing"}`, and a PagerDuty incident with the
  stable dedup key `<app>-ring-discovery` (deduplicated across restarts, so
  repeated restarts update one incident instead of opening a new one). It is
  gated by the `pager-duty-tickets` feature flag like every other incident.
- **`app.ring-discovery-required=true`** - exits with
  `EXIT_CODE_RING_DISCOVERY` (64) so the supervisor restart loop makes the
  failure loud instead of running degraded.
- **Late recovery** - a successful start that failed the check keeps watching
  for up to 12 hours, then resolves the incident as soon as the bridge publishes
  again.

Startup is blocked for at most the configured timeout, and the wait happens after
the Prometheus endpoint and HTTP API are up, so `/metrics` stays scrapeable
while the check is pending. Because a failure here is *Ring-specific*, the
default is to continue (cameras, detectors and meters keep working). Use
`ring-discovery-required` only if a Ring outage should stop the whole processor.

### Health metrics

Device health is exported as gauges rather than only logged, so battery drain and
link quality can be charted and alerted on. Values are posted for every message
that carries them (`info/state` for all devices, `battery/attributes` on sensors,
`wireless/attributes` on cameras and chimes), and only when present — a
mains-powered device or a chime reports no `batteryLevel`, so it produces no
battery series instead of a misleading `0`:

| Metric | Meaning | Labels |
|---|---|---|
| `ring_battery_level` | charge percentage (0-100) | `input_label`, `ring_device_id`, `ring_component_name` |
| `ring_battery_life` | battery health per slot (0-100); a slot reporting 0 has no battery fitted and is not published | `input_label`, `ring_device_id`, `ring_component_name`, `slot` |
| `ring_wireless_signal` | wireless signal strength in dBm (negative) | `input_label`, `ring_device_id`, `ring_component_name` |

`batteryLevel` is the charge, `batteryLife`/`batteryLife2` are the per-slot battery
health the Ring API reports separately (`ring-mqtt` maps `battery_life` /
`battery_life_2`); a dual-battery camera can report `batteryLevel: 58` against
`batteryLife: 100`, `batteryLife2: 0`.

`input_label` is the Home Assistant discovery name, the same label the `event`,
`triggered` and `triggered_duration` counters use, so battery can be joined to
device activity without a lookup table (the label reads `unknown` until the
discovery config is cached):

```
ring_battery_level{input_label="Bedroom Motion"} * on(device) group_left() event_total{input_type="motion"}
```

### Device status (`GET /devices`)

Each Ring message is also merged into a per-device snapshot
(`tailucas.app.provider.DeviceState`), because a device spreads its availability,
entity states and health over several topics. A message that carries only one field
never erases the rest, so the snapshot is always the newest value seen for each
field:

```
GET http://event-processor:8085/devices
```

```json
[{"deviceKey":"2579f711-…","component":"alarm","label":"Home Alarm",
  "model":"Ring Alarm Control Panel","availability":"online",
  "batteryLevel":100,"batteryLife":100,"batteryStatus":"full",
  "wirelessSignal":null,"wirelessNetwork":null,
  "tamperStatus":"ok","commStatus":"ok","linkQuality":"ok",
  "firmwareStatus":"up-to-date","deviceReportedAt":"2026-11-04T09:42:25Z",
  "lastSeen":"2026-11-04T10:02:25Z",
  "states":{"alarm":"disarmed","contact":"OFF","motion":"OFF","siren":"OFF"}}]
```

`deviceReportedAt` is when the device itself last reported to Ring (the staleness
signal for a quiet sensor); `lastSeen` is when this application last received a
message about it. Devices are ordered by label.

### Telegram `/ring`

`/ring` (allowlisted users only) fetches `GET /devices` from the Java processor and
replies with every Ring device, its health and its current entity states:

```
🔔 *Ring devices* — 20 known, 20 online

📷 *Front Gate* — Ring Camera
`online` · battery `100%` full · signal `-67` dBm · reported 4m ago
`motion` OFF · `ding` OFF

🔔 *Garage Door* — Ring Contact Sensor
`online` · battery `95%` full · reported 2h ago
`contact` OFF
```

Unhealthy flags only appear when they are not `ok` (`tamper`, `comm`, `link`), a
mailbox icon marks a device below 20% battery, and an offline device is shown with a
warning icon. Message composition lives in `app/ring_report.py` so it is unit tested
without booting the app; the rules are in `.clinerules/telegram.md`.

## Build System

### Make CLI (Makefile)

Primary build and deployment orchestration (run `make help` for all targets):

- `make build` - Build Docker image with Java compilation, Python dependencies, and asset bundling
- `make run` - Run container in foreground with full log output
- `make rund` - Run container detached (persists after terminal close)
- `make configure` - Generate .env and docker-compose.yml from 1Password secrets
- `make datadir` - Create data directory with proper permissions
- `make java` - Compile Java artifacts with Maven (standalone Java build)
- `make python` - Setup Python virtual environment with uv
- `make push` - Push built image to Docker Hub/registry
- `make dev` - Build and enter the development container (host only)

### Dockerfile

Multi-stage build process:

1. **Builder Stage**: Compiles Java application
   - Uses `tailucas/base-app:latest` as builder base
   - Builds with Maven 3.9+
   - Produces `app-0.1.0.jar`

2. **Runtime Stage**: Extends `tailucas/base-app:latest` with event-processor-specific components
   - System packages: sqlite3, html-xml-utils, wget
   - Python application code and dependencies (uv-managed)
   - Compiled Java JAR from builder stage
   - Flask web application and templates
   - FastAPI application
   - Telegram bot configuration
   - Database backup scripts
   - Cron job configuration
   - Runs as user `app` (UID 999)

### Dependencies

**Python** (`pyproject.toml`, managed via uv):
- `flask>=3.1.2` - Web framework for dashboard
- `fastapi>=0.116.1` - Modern async API framework
- `flask-sqlalchemy>=3.1.1` - ORM integration with Flask
- `sqlalchemy[asyncio]>=2.0.43` - Async database ORM
- `aiosqlite>=0.21.0` - Async SQLite driver
- `pyzmq>=27.0.2` - ZeroMQ bindings
- `python-telegram-bot>=22.4` - Telegram bot integration
- `requests>=2.32.5` - HTTP client library
- `uvicorn[standard]>=0.35.0` - ASGI web server
- `schedule>=1.2.2` - Job scheduling library
- `pytz>=2025.2` - Timezone support
- `permit>=2.8.1` - Authorization/RBAC
- `pydantic>=2.11.9` - Data validation
- `sentry-sdk[flask]>=2.37.0` - Error tracking
- `tailucas-pylib>=0.5.2` - Shared utilities

**Java** (Maven, Spring Boot 3.4.13 parent):
- Core: commons-lang3, ini4j, SLF4J
- Messaging: RabbitMQ client, MQTT (Paho)
- Data: Jackson (JSON/MessagePack)
- Monitoring: Prometheus metrics, Sentry Spring Boot
- Distributed: PagerDuty integration, Unleash feature flags
- JMX: Remote management capabilities

### Port Mappings

From docker-compose.yml:

- `4041:4040` - ngrok API management interface
- `8095:8080` - Flask web server
- `8085:8085` - FastAPI/uvicorn endpoints  
- `9400:9400` - Prometheus metrics endpoint

## Java Configuration

This project includes Spring Boot integration. For local JMX profiling, use these VM arguments:

```
-Djava.net.preferIPv4Stack=true \
-Dcom.sun.management.jmxremote.host=127.0.0.1 \
-Dcom.sun.management.jmxremote.port=3333 \
-Dcom.sun.management.jmxremote.ssl=false \
-Dcom.sun.management.jmxremote.authenticate=false
```

The `EventProcessor` Java class is the main entry point, providing complementary functionality to the Python application.

<!-- LICENSE -->
## License

Distributed under the MIT License. See [LICENSE](LICENSE) for more information.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- ACKNOWLEDGMENTS -->
## Acknowledgments

* [Template on which this README is based](https://github.com/othneildrew/Best-README-Template)
* [All the Shields](https://github.com/progfay/shields-with-icon)

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- MARKDOWN LINKS & IMAGES -->
<!-- https://www.markdownguide.org/basic-syntax/#reference-style-links -->
[contributors-shield]: https://img.shields.io/github/contributors/tailucas/event-processor.svg?style=for-the-badge
[contributors-url]: https://github.com/tailucas/event-processor/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/tailucas/event-processor.svg?style=for-the-badge
[forks-url]: https://github.com/tailucas/event-processor/network/members
[stars-shield]: https://img.shields.io/github/stars/tailucas/event-processor.svg?style=for-the-badge
[stars-url]: https://github.com/tailucas/event-processor/stargazers
[issues-shield]: https://img.shields.io/github/issues/tailucas/event-processor.svg?style=for-the-badge
[issues-url]: https://github.com/tailucas/event-processor/issues
[license-shield]: https://img.shields.io/github/license/tailucas/event-processor.svg?style=for-the-badge
[license-url]: https://github.com/tailucas/event-processor/blob/master/LICENSE

[blog-url]: https://tailucas.github.io/update/2023/06/18/home-security-automation.html
[appconf-url]: https://github.com/tailucas/event-processor/blob/master/config/app.conf
[baseapp-url]: https://github.com/tailucas/base-app
[baseapp-image-url]: https://hub.docker.com/repository/docker/tailucas/base-app/general
[pylib-url]: https://github.com/tailucas/pylib
[snapshot-processor-url]: https://github.com/tailucas/snapshot-processor
[remote-monitor-url]: https://github.com/tailucas/remote-monitor

[ha-url]: https://www.home-assistant.io/
[oh-url]: https://www.openhab.org/docs/

[1p-url]: https://developer.1password.com/docs/connect/
[1p-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=1Password&color=0094F5&logo=1Password&logoColor=FFFFFF&label=
[aws-url]: https://aws.amazon.com/
[aws-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=Amazon+AWS&color=232F3E&logo=Amazon+AWS&logoColor=FFFFFF&label=
[bootstrap-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=Bootstrap&color=7952B3&logo=Bootstrap&logoColor=FFFFFF&label=
[bootstrap-url]: https://getbootstrap.com/
[cronitor-url]: https://cronitor.io/
[docker-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=Docker&color=2496ED&logo=Docker&logoColor=FFFFFF&label=
[docker-url]: https://www.docker.com/
[fontawesome-url]: https://fontawesome.com/
[fontawesome-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=Font+Awesome&color=528DD7&logo=Font+Awesome&logoColor=FFFFFF&label=
[healthchecks-url]: https://healthchecks.io/
[influxdb-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=InfluxDB&color=22ADF6&logo=InfluxDB&logoColor=FFFFFF&label=
[influxdb-url]: https://www.influxdata.com/
[mdash-url]: https://mdash.net/home/
[mqtt-url]: https://mqtt.org/
[mqtt-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=MQTT&color=660066&logo=MQTT&logoColor=FFFFFF&label=
[ngrok-url]: https://ngrok.com/
[ngrok-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=ngrok&color=1F1E37&logo=ngrok&logoColor=FFFFFF&label=
[poetry-url]: https://python-poetry.org/
[poetry-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=Poetry&color=60A5FA&logo=Poetry&logoColor=FFFFFF&label=
[python-url]: https://www.python.org/
[python-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=Python&color=3776AB&logo=Python&logoColor=FFFFFF&label=
[flask-url]: https://flask.palletsprojects.com/
[flask-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=Flask&color=000000&logo=Flask&logoColor=FFFFFF&label=
[rabbit-url]: https://www.rabbitmq.com/
[rabbit-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=RabbitMQ&color=FF6600&logo=RabbitMQ&logoColor=FFFFFF&label=
[rpi-url]: https://www.raspberrypi.org/
[sentry-url]: https://sentry.io/
[sentry-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=Sentry&color=362D59&logo=Sentry&logoColor=FFFFFF&label=
[sqlalchemy-url]: https://www.sqlalchemy.org/
[sqlite-url]: https://www.sqlite.org/
[sqlite-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=SQLite&color=003B57&logo=SQLite&logoColor=FFFFFF&label=
[telegram-url]: https://telegram.org/
[telegram-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=Telegram&color=26A5E4&logo=Telegram&logoColor=FFFFFF&label=
[zmq-url]: https://zeromq.org/
[zmq-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=ZeroMQ&color=DF0000&logo=ZeroMQ&logoColor=FFFFFF&label=
