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
* **Telegram Bot Integration**: Built-in SMS output via Telegram bot with optional AWS SNS fallback
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

* **`task`**: Build orchestration - https://taskfile.dev/installation/#install-script
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
   task datadir
   ```

   Ensure you've reviewed [Makefile](Makefile) assumptions about user IDs for Docker (UID/GID 999).

3. **Configure Runtime Environment**

   ```bash
   task configure
   ```

   This generates `docker-compose.yml` and `.env` from your 1Password secrets and `base.env` template.

4. **Build the Docker Image**

   ```bash
   task build
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
   task run
   ```

   **Background (detached mode, logs to syslog)**:
   ```bash
   task rund
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

When `task run` or `task rund` is executed, the application initializes in this order:

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

## Build System

### Task CLI (Taskfile.yml)

Primary build and deployment orchestration:

- `task build` - Build Docker image with Java compilation, Python dependencies, and asset bundling
- `task run` - Run container in foreground with full log output
- `task rund` - Run container detached (persists after terminal close)
- `task configure` - Generate .env and docker-compose.yml from 1Password secrets
- `task datadir` - Create data directory with proper permissions
- `task java` - Compile Java artifacts with Maven (standalone Java build)
- `task python` - Setup Python virtual environment with uv
- `task push` - Push built image to Docker Hub/registry

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
