---
paths:
  - "src/**"
  - "app/**"
  - "pom.xml"
  - "pyproject.toml"
  - "Dockerfile"
---

# event-processor Coding Standards

Event processing application: a Java Spring Boot service that consumes device
events from RabbitMQ/MQTT and drives output devices (cameras, meters, rings,
detectors, generic HA devices), plus a Python Flask/FastAPI web application
for configuration and event log UI.

## 1. Posture

- This project is functionally **ahead of base-app** (the reference template).
  Proven patterns here (structured logging via SLF4J fluent API + Log4j2 JSON,
  Sentry structured logs, feature flags) are the direction of travel for all
  sibling apps — **backport them to base-app** when they prove out, so every
  fork inherits them.
- Java is the event-processing core; Python serves the web/config surface.
  Keep the boundary clean: they communicate via the same RabbitMQ/ZMQ
  transports and shared configuration conventions, not shared code.
- Sibling standards live in `build.md`, `config.md`, `container.md`, `mq.md`,
  `observability.md`, `logging.md` and `telegram.md` — defer to them, and refresh
  this file as the templates evolve.

## 2. Java (`src/`, `pom.xml`)

- Spring Boot 4 with `@SpringBootApplication` + `@EnableConfigurationProperties`
  (`AppProperties`). Java release pinned via `maven.compiler.release`.
- Packages: `tailucas.app.api` (HTTP/WS handlers), `tailucas.app.device`
  (device models and event logic), `tailucas.app.message` (MQTT/RabbitMQ),
  `tailucas.app.provider` (DeviceConfig, DeviceState, Metrics, OnePassword).
- Loggers are injected via `AppConfig.produceLogger` (SLF4J
  `LoggerFactory.getLogger(classOnWired)`); keep that pattern, one logger per
  class.
- Static analysis: SpotBugs (`spotbugs-exclude.xml` defines the exclusions);
  `make test` runs unit tests + SpotBugs, `make integration` runs the Spring
  Boot integration test. Do not introduce new SpotBugs findings.
- Tests: JUnit under `src/test/java`; test resources include
  `log4j2-test.xml` (pattern layout for test readability).
- Event model: a message may be both a heartbeat and a device event
  (`Generic.carriesEvent()`). A heartbeat registers the device
  (`postDeviceInfo`) and otherwise stops there; when it carries an event it also
  flows through the **standard input→output link** (`wouldTriggerOutput` →
  `getLinkedOutputs` → publish). A camera snapshot is the reference case: the
  image travels in the linked payload, never through a bespoke forwarding path.
- **HTTP surface**: `GET /` (liveness), `GET /devices`, `POST /invalidate_config`,
  `POST /snapshot`. Controllers live in `tailucas.app.api` and are tested with
  `@WebMvcTest` slices, mocking static collaborators (`DeviceState`,
  `EventProcessor`) via `mockStatic`.
- **Outbound MQTT has exactly one owner.** `mqttClient` is not thread-safe and
  belongs to the MQTT thread. Anything else (a REST handler, for instance)
  publishes with `EventProcessor.publishMqtt(topic, payload)`, which hands the
  message to that thread over the `inproc://mqtt-send` queue (see `mq.md` §1).
  Never call `mqttClient.publish` from another thread.
- **Addressing a device needs its location.** `DeviceState.DeviceSnapshot` carries
  `locationId` (topic part 1) as well as `deviceKey`, because every Ring command
  topic is scoped `ring/<locationId>/<category>/<deviceId>/…`. A command cannot be
  built from the device id alone.
- **Commands are requested, not awaited.** `POST /snapshot` publishes the bridge's
  trigger and returns what it asked for and skipped; the picture arrives later as a
  normal event (see `telegram.md` §6). Do not make an endpoint wait for the image —
  that duplicates the event path and makes the chat a second arbiter of delivery.

## 3. Python (`app/`, `pyproject.toml`)

- `app/__main__.py` hosts the Flask + FastAPI (uvicorn) web app with
  SQLAlchemy/aiosqlite persistence and the Telegram bot.
- Framework conventions come from `tailucas_pylib` (logger, creds, flags,
  threading). Follow pylib's coding/logging standards.
- Bot handlers follow the shared Telegram bot conventions (see net-tool's
  standards for the handler skeleton).
- Anything that can be decided without I/O belongs in a **pure sibling module**
  (`ring_report.py`, `telegram_filter.py`, `snapshot_command.py`,
  `snapshot_window.py`, `schedule_time.py`, `telemetry_filter.py`), so `pytest`
  imports it without booting the app — see `telegram.md` §1.
- Lint gate: `make lint` (`ruff check app/` + `mypy app/` — the whole package, so
  a new module is gated too). Keep it green.

## 4. Configuration & Secrets

- Spring config: `src/main/resources/application.properties` with values
  interpolated at build/startup; app-specific properties flow through
  `AppProperties`.
- Runtime secrets are fetched from 1Password at startup (OnePassword provider)
  and cached; never log secret values.
- Feature flags (`FEATURE_FLAG_*`) gate optional behavior (HA discovery,
  PagerDuty tickets); check them via the shared flag helper and log the flag
  name when skipping behavior.

## 5. Messaging & Observability

- RabbitMQ for control/event exchanges; MQTT for device state; ZeroMQ inproc
  for intra-process relays and for serialising outbound MQTT publishes
  (`EventProcessor.publishMqtt`, see `mq.md` §1).
- Prometheus metrics server (port from `metrics.port`); post lifecycle
  metrics via the Metrics provider.
- Sentry for both stacks: Java via `Sentry.captureException` +
  `Sentry.logger()` structured logs; Python via `sentry_sdk` integrations.
  Reduce noise with `ignore_logger` for chatty third-party loggers.

## 6. Web Layer Conventions

- Flask renders templates (`templates/`); FastAPI serves the JSON APIs
  (`/api/...`) mounted under the same server.
- Authentication via Flask-Login; authorization checks must log outcomes in
  structured style (`extra={"user_email": ..., "action": ..., "resource": ...}`).

## 7. Cross-cutting Rules

- Every runtime logs in structured style (see `logging.md`): SLF4J fluent API +
  Log4j2 JSON in Java, pylib `extra` in Python.
- Graceful shutdown is mandatory everywhere: the JVM shutdown hook flushes OTEL
  and Sentry on exit; Python tears down through `SignalHandler` + `thread_nanny`
  + `die()`/`bye()`.
- New integrations get a feature switch (`FEATURE_FLAG_*` / env var), never a
  hard dependency, so the app stays runnable with zero external dependencies.
