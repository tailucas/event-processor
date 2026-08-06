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
  sibling apps.
- Java is the event-processing core; Python serves the web/config surface.
  Keep the boundary clean: they communicate via the same RabbitMQ/ZMQ
  transports and shared configuration conventions, not shared code.

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
