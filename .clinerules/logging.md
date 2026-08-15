---
paths:
  - "src/**"
  - "app/**"
  - "src/main/resources/log4j2.xml"
---

# Structured Logging Standard (event-processor)

This project is the **reference for structured logging** across the
application family. Both stacks log as structured JSON events: a static
message plus key/value fields. Interpolation (string concatenation,
`%`/`{}` placeholders, f-strings) should be avoided and used only for a
descriptive scalar with no query value of its own (e.g. a count embedded
for readability). Never interpolate secrets or untrusted data into a
message. One logical event produces a single structured record with all
context in its fields — **one event = one line**.

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

1. `setMessage` takes a **static** string. Prefer `addKeyValue` fields over
   interpolation; interpolation is acceptable only for a descriptive scalar
   (a count or an identifier already present elsewhere in the record).
   Never interpolate secrets or untrusted input. Never pass a bare variable
   as the message (`log.debug(beanName)` is wrong).
2. One `addKeyValue("snake_case_key", value)` per field; keys are
   `snake_case`. Values may be strings, numbers, booleans, or collections.
3. Exceptions attach with `.setCause(e)`; keep a static message describing
   what failed.
4. Conditional/expensive logging: capture the builder, e.g.
   `final LoggingEventBuilder latchLog = enabled ? log.atInfo() : log.atDebug();`
   then add fields and `.log()` once. For expensive field assembly, guard
   with `log.isEnabledForLevel(Level.DEBUG)`.
5. Sentry structured logs mirror this style via
   `Sentry.logger().log(level, SentryLogParameters.create(SentryAttributes.of(...)), "message")`.

Backend (`src/main/resources/log4j2.xml`):

- **Every** appender renders the same JSON document via `JsonTemplateLayout`
  with `eventTemplateUri="classpath:LogstashJsonEventLayoutV1.json"` (Logstash
  JSON). Never leave an appender without an explicit layout: Log4j2 falls back
  to its defaults (for Syslog that is a plain RFC5424 message-only payload),
  which silently breaks the JSON-everywhere contract.
- Syslog appender (UDP) ships INFO+ JSON datagrams to
  `${env:SYSLOG_HOST:-localhost}`; when `SYSLOG_HOST` is undefined the default
  is a silent no-op (UDP to localhost), not a startup configuration error.
  (`format="RFC5424"` is retained but inert: it only applies when no layout is
  set.)
- Root level is `${env:LOG_LEVEL:-INFO}`. All `${env:...}` lookups must carry
  a `:-default`: an unset `SYSLOG_HOST`/`LOG_LEVEL` otherwise causes
  `ConfigurationException` noise at startup or a silent fallback to ERROR.
- Fluent `addKeyValue` fields render under the top-level `"mdc"` object in the
  JSON output (e.g. `"mdc":{"app_name":"..."}`); query them as `mdc.<key>`.
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
   Interpolation is acceptable only for a descriptive scalar (a count or an
   identifier already present elsewhere in the record). Data that belongs in
   `extra` should not be inserted via f-strings, `%`-args, or `.format()`.
2. Exceptions: `log.exception("Static message")` or `exc_info=...`.
3. Reused message + fields: store `log_msg`/`log_fields` variables and pass
   `extra=log_fields` at each level.
4. Reduce third-party noise with `ignore_logger(...)` (Sentry integration).
5. Never log secrets or full credential values.

## Levels

Choose the level by the *consequence* of the event, not by how interesting it
is. Default to the lowest level that still tells the story.

| Level | Use |
|---|---|
| DEBUG | The default for routine, per-event detail: internal state, field values, message contents, authz decisions. Safe to drop in production. |
| INFO | An action of consequence: lifecycle, state transitions, device triggers — something an operator would want to see in normal operation. |
| WARNING | A non-error variation of normal logic or an ambiguous situation: recoverable faults, retries, fallbacks, feature flags disabling behavior. Execution continues. |
| ERROR | A condition where normal execution cannot continue — e.g. returning after catching an exception, or abandoning a unit of work. Always `.setCause`/`exc_info` where possible. |
| CRITICAL | The process is about to exit or is in an unrecoverable app-level state. Reserved for fatal failures. |

> `TRACE` (below DEBUG) exists in Log4j2 and some facades but not in Python
> stdlib or Go `log/slog`, so it is not portable across runtimes and should
> not be relied on for cross-language code.

### Exception handling

- **Log once, at the boundary.** Do not log-and-rethrow the same exception at
  every layer. Log where the error is handled (or where execution stops), and
  let the trace carry the rest of the context.
- **Non-recoverable errors must be captured in the trace.** For every ERROR
  where execution cannot continue, record the exception on the active span —
  `record_exception` in Python, `span.recordException(...)` + ERROR status in
  Java (or Sentry `setThrowable(e)` + `setStatus(SpanStatus.INTERNAL_ERROR)`)
  — so the failure is queryable in the trace, not only in the log. See
  `observability.md` §1 "Errors are span data".
- **Recoverable problems are WARNING, not ERROR.** A retry that succeeds is a
  WARNING (or DEBUG if routine); escalate to ERROR only when the work is
  abandoned.
