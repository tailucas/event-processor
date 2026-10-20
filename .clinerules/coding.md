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
  `observability.md`, and `logging.md` — defer to them, and refresh this file
  as the templates evolve.

## 2. Java (`src/`, `pom.xml`)

- Spring Boot 4 with `@SpringBootApplication` + `@EnableConfigurationProperties`
  (`AppProperties`). Java release pinned via `maven.compiler.release`.
- Packages: `tailucas.app.api` (HTTP/WS handlers), `tailucas.app.device`
  (device models and event logic), `tailucas.app.message` (MQTT/RabbitMQ),
  `tailucas.app.provider` (DeviceConfig, Metrics, OnePassword).
- Loggers are injected via `AppConfig.produceLogger` (SLF4J
  `LoggerFactory.getLogger(classOnWired)`); keep that pattern, one logger per
  class.
- Static analysis: SpotBugs (`spotbugs-exclude.xml` defines the exclusions);
  `make test` runs unit tests + SpotBugs, `make integration` runs the Spring
  Boot integration test. Do not introduce new SpotBugs findings.
- Tests: JUnit under `src/test/java`; test resources include
  `log4j2-test.xml` (pattern layout for test readability).

## 3. Python (`app/`, `pyproject.toml`)

- `app/__main__.py` hosts the Flask + FastAPI (uvicorn) web app with
  SQLAlchemy/aiosqlite persistence and the Telegram bot.
- Framework conventions come from `tailucas_pylib` (logger, creds, flags,
  threading). Follow pylib's coding/logging standards.
- Bot handlers follow the shared Telegram bot conventions (see net-tool's
  standards for the handler skeleton).
- Lint gate: `make lint` (ruff check + mypy on `app/__main__.py`). Keep it
  green.

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
  for intra-process relays.
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

## 8. Home Assistant Integration (optional experiment)

- **Optional, not committed.** The production architecture is the Flask/FastAPI
  + SQLite hub with the Java engine. Do not start or expand HA work unless the
  user explicitly requests it; `ha.md` is research, not a roadmap.
- The HA integration is a **private, core-grade custom component** in
  `custom_components/event_processor`; no HACS, no PyPI client, no HA core PR.
  It is deployed directly into the HA container only for the optional experiment.
- **Boundary:** HA talks only to the hub API (`/api/v1`, HTTP + WebSocket). It
  never opens SQLite, RabbitMQ or MQTT directly. The Python app is the single
  writer while Flask and HA coexist.
- **Device/entity rules:** one HA device per `device_key`, `via_device_id` to
  the hub, `has_entity_name=True`, immutable device-scoped `unique_id`,
  runtime arm/disarm as `switch`/`alarm_control_panel`, behavior as
  `entity_category=CONFIG` entities or config subentries.
- **No duplicate physical devices:** `source=ring` devices are skipped; camera
  live entities belong to `go2rtc`/RTSP integrations. Use hub-level action
  entities for snapshot/Telegram instead.
- **No secrets in HA metadata:** camera `device_params`, hub tokens, broker
  topics and IPs must never appear in attributes, diagnostics or logs.
- **Services** are described in `services.yaml` and raise
  `HomeAssistantError` on hub failure. Diagnostics use redaction.
- **Java publisher:** `HubEventPublisher` posts processed events to the hub;
  keep it non-blocking and never let a hub outage stall the event pipeline.
- **If optional HA work is approved:** behavior moves must be reversible
  (`LINKS_SOURCE=hub|ha`, `RING_SOURCE=mqtt|ha`). Do not remove Flask without
  an explicit parity/retirement decision; no removal is currently committed.
- See `ha.md` for optional research, live facts, entity map and possible
  directions. It is not a commitment or backlog.
