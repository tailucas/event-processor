---
paths:
  - "Dockerfile"
  - "docker-compose.yml"
  - "*.sh"
  - "config/supervisord.conf"
  - "config/cron/**"
---

# base-app Container & Process Management

base-app is a **Docker base image**: a single Ubuntu container running
**supervisord** as the process manager, with a layered entrypoint/setup
convention designed so a fork can override behaviour without editing the
template's core scripts. This file documents the container lifecycle and the
**override seam** that makes the template reusable.

## 1. Multi-stage build

The `Dockerfile` has two stages:

- **`builder`** — installs SDKMAN + Java + Maven, compiles the Java app, and
  produces the fat JAR (`target/app-0.1.0-jar-with-dependencies.jar`).
- **runtime** — installs all four language toolchains, runs the setup scripts,
  and copies only the JAR out of the builder stage.

Keep the runtime image free of build toolchains: anything that only produces
an artifact belongs in `builder`. The runtime stage copies the compiled JAR
with `COPY --from=builder`, never recompiles.

## 2. Entrypoint layering (the override seam)

The container's `CMD` is `/opt/app/entrypoint.sh`, which sources two scripts
in order before `exec supervisord`:

```sh
. /opt/app/base_entrypoint.sh
. /opt/app/app_entrypoint.sh
exec env supervisord -n -c /opt/app/supervisord.conf
```

- **`base_entrypoint.sh`** is the **template's contract**: it interpolates
  config, copies `config/supervisord.conf`, appends the cron program, exports
  `cron.env`, writes AWS config, and appends the supervised program blocks for
  each enabled runtime.
- **`app_entrypoint.sh`** is the **fork's override point** — it is shipped as
  an empty stub and is where a derived app adds its own startup logic.

The same split applies to the build-time setup scripts:

- **`base_setup.sh`** — template contract (currently a no-op stub).
- **`app_setup.sh`** — fork override (currently registers crons).

**Rule:** a fork must not edit `base_entrypoint.sh`/`base_setup.sh`; it should
put its own logic in `app_entrypoint.sh`/`app_setup.sh` so upstream template
changes can be merged without conflict.

## 3. supervisord program generation via feature flags

`config/supervisord.conf` is a **sample/comment file** — it contains no active
`[program:*]` sections. The actual programs are appended at container start by
`base_entrypoint.sh`, each guarded by an environment feature flag:

| Flag | Program | Command |
|---|---|---|
| `NO_PYTHON_APP` (unset) | `app` | `uv run --frozen --no-sync app` |
| `RUN_RUST_APP=true` | `rapp` | `cargo run --release` |
| `RUN_JAVA_APP=true` | `japp` | `java -jar app.jar` |
| `RUN_GO_APP=true` | `gapp` | `go run ./internal/main.go` |
| `NO_CRON` (unset) | `cron` | `/usr/sbin/cron -f -L 4` |

Each program block sets `priority`, `directory=/opt/app/`, `user=app`,
`autorestart=unexpected`, `stopwaitsecs=30`, and routes stdout/stderr to
`/dev/stdout`/`/dev/stderr` with `stdout_events_enabled=true`.

**Rule:** a fork adds its own supervised program by appending a `[program:*]`
block in `app_entrypoint.sh` (guarded by a feature flag), never by editing
`config/supervisord.conf`. Every integration gets a feature switch so the
template stays runnable with zero external dependencies.

## 4. Run-as-user convention

- The app runs as a **no-password user `app` with UID/GID `999`** (created in
  the Dockerfile with `useradd -r -u 999 -g 999 app`).
- The Dockerfile switches to `USER app` **before** running `rust_setup.sh` and
  `python_setup.sh`, because `uv` does not infer the target user from the
  environment — toolchain installs that write to `$HOME` must run as `app`.
- The host-side `data/` directory is owned by `USER_ID:GROUP_ID` (default
  `999:999`) so the in-container `app` user can write to it; override with
  `make datadir USER_ID=... GROUP_ID=...`.
- `make-app-user.sh` mirrors the `app` user/group on the host for
  Docker-out-of-Docker workflows.

## 5. Toolchain installation

Java and Maven are installed via **SDKMAN** (not apt), with `JAVA_HOME` and
`PATH` wired to the SDKMAN candidates directory:

```dockerfile
ENV SDKMAN_DIR="${APP_DIR}/.sdkman"
RUN bash -c "source $SDKMAN_DIR/bin/sdkman-init.sh && sdk install java 26-amzn"
ENV JAVA_HOME="$SDKMAN_DIR/candidates/java/current"
```

Python is managed by `uv`; Rust via `rustup`; Go via a versioned tarball
(`go_setup.sh` pins `GO_VERSION`). Keep these installers in their dedicated
`*_setup.sh` scripts rather than inlining them in the Dockerfile.

## 6. Cron orchestration

- `app_setup.sh` concatenates every file in `config/cron/*` into a single
  crontab registered for the `app` user (`crontab -u app`).
- `base_entrypoint.sh` exports the full environment to `/opt/app/cron.env`
  (`printenv`), which cron jobs source before running (see
  `healthchecks_heartbeat.sh`, `base_job.sh`).
- The cron program itself is appended by `base_entrypoint.sh` unless
  `NO_CRON=true`.

**Rule:** add a new scheduled job by dropping a crontab file into
`config/cron/` and sourcing `cron.env` inside the script it invokes.

## 7. Graceful shutdown

- `docker-compose.yml` sets `stop_grace_period: 45s`.
- Each supervised program sets `stopwaitsecs=30` and
  `autorestart=unexpected`.
- The Python app handles signals via `SignalHandler` + `thread_nanny` and
  tears down ZMQ sockets in `die()`/`zmq_term()` (see `coding.md`).
- The Java app registers a JVM shutdown hook that flushes OTEL (`otel.close()`).

Graceful shutdown is mandatory in every runtime — signal handling and
resource teardown must be wired so a container stop does not drop in-flight
work or telemetry.

## 8. Home Assistant container and hub deployment

- HA runs as Docker container `homeassistant`
  (`ghcr.io/home-assistant/home-assistant:stable`) on `acer`, with
  **`network_mode: host`**. The config directory is the host bind mount
  `/home/tai/home-assistant/config` → `/config`.
- Custom component path:
  `/home/tai/home-assistant/config/custom_components/event_processor`.
  Install with `make ha-deploy`; restart with `docker restart homeassistant`.
  A config-entry reload is not enough to pick up changed Python module code.
- Because HA uses host networking, the hub is `http://127.0.0.1:8095` from HA.
  From another host it is `http://192.168.0.6:8095`.
- The event-processor container `event-processor-app-1` publishes
  `8095:8080` (Python API), `8085:8085` (metrics), `9400:9400` and
  `4041:4040`. It uses `host.docker.internal` for 1Password Connect,
  RabbitMQ and MQTT.
- **Data ownership is critical.** The container runs as `app` UID/GID 999.
  `/data/event-processor.db`, its `-wal`/`-shm` files and
  `/data/hub_api_token` must be owned `999:999` and writable. A DB restored
  from a UID 1000 copy causes `attempt to write a readonly database`; the
  Python nanny then shuts the app down. Fix with:
  `docker exec -u root event-processor-app-1 chown 999:999 /data/...`.
- The production DB backup is
  `/data/event-processor.db.bak-20261018204347`; keep backups readable by UID
  999.
- `deploy_hub.sh` copies the hub/Java changes to
  `acer:/home/tai/event-processor`, rebuilds the image and recreates the
  service. It loads 1Password Connect exports from `/home/tai/.bashrc`.
- See `ha.md` for the HA deployment and migration details.