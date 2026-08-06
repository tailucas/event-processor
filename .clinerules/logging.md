---
paths:
  - "src/**"
  - "app/**"
  - "src/main/resources/log4j2.xml"
---

# Structured Logging Standard (event-processor)

This project is the **reference for structured logging** across the
application family. Both stacks log as structured JSON events: a static
message plus key/value fields. Interpolated log messages are prohibited in
both Java and Python.

## Java: SLF4J Fluent API + Log4j2 JSON

Always use the SLF4J 2.x fluent builder:

```java
log.atInfo().setMessage("Startup complete").addKeyValue("app_name", applicationName).log();

log.atInfo().setMessage("Input triggers output")
    .addKeyValue("input_device", inputDevice)
    .addKeyValue("output_device", outputDevice)
    .addKeyValue("triggered", true)
    .log();

log.atError().setMessage("Cannot start metrics server").setCause(e).log();

log.atWarn().setMessage("Not updating PagerDuty status, disabled with feature flag")
    .addKeyValue("feature_flag", FEATURE_FLAG_PAGER_DUTY_TICKETS)
    .log();
```

Rules:

1. `setMessage` takes a **static** string. Never interpolate:
   no `"..." + var`, no `String.format`, no `{}` placeholder overloads
   (`log.info("x {}", x)`), and never pass a bare variable as the message
   (`log.debug(beanName)` is wrong).
2. One `addKeyValue("snake_case_key", value)` per field; keys are
   `snake_case`. Values may be strings, numbers, booleans, or collections.
3. Exceptions attach with `.setCause(e)`; keep a static message describing
   what failed.
4. Conditional/expensive logging: capture the builder, e.g.
   `final LoggingEventBuilder latchLog = enabled ? log.atInfo() : log.atDebug();`
   then add fields and `.log()` once.
5. Sentry structured logs mirror this style via
   `Sentry.logger().log(level, SentryLogParameters.create(SentryAttributes.of(...)), "message")`.

Backend (`src/main/resources/log4j2.xml`):

- Console appender uses `JsonTemplateLayout` with
  `eventTemplateUri="classpath:LogstashJsonEventLayoutV1.json"` (Logstash JSON).
- Syslog appender (RFC5424/UDP) ships INFO+ when `SYSLOG_HOST` is defined.
- Root level is `${env:LOG_LEVEL}`.
- Tests use `src/test/resources/log4j2-test.xml` (pattern layout + file).
- Required deps (already in `pom.xml`): `spring-boot-starter-log4j2`,
  `log4j-layout-template-json`, `slf4j-api`.

## Python: static message + `extra`

The web app uses the shared pylib logger:

```python
log.info("Login request received", extra={"user_email": email})
log.error(
    "Request validation failed",
    extra={"url": str(request.url), "errors": exc.errors()},
)
log.warning(msg="Telegram Bot Exception while handling an update:", exc_info=context.error)
```

Rules:

1. Static message; all variables go into `extra` with `snake_case` keys.
   Never `log.info(f"... {var}")`, `%`-args, `.format()`, or concatenation.
2. Exceptions: `log.exception("Static message")` or `exc_info=...`.
3. Reused message + fields: store `log_msg`/`log_fields` variables and pass
   `extra=log_fields` at each level.
4. Reduce third-party noise with `ignore_logger(...)` (Sentry integration).
5. Never log secrets or full credential values.

## Levels

| Level | Use |
|---|---|
| DEBUG | per-event tracing, authz decisions, message contents |
| INFO | lifecycle, state transitions, device triggers |
| WARNING | recoverable faults, feature flags disabling behavior |
| ERROR | failed operations (always `.setCause`/`exc_info` where possible) |
| CRITICAL | reserved |
