# Deployment — building and shipping `event-processor` with GitHub Actions

**Status: repo changes applied 2026-11-27, NOT committed and NOT yet deployable.**
The workflow files and the compose/dot-env fixes exist in the working tree only.
The live stack on `acer` was deliberately left untouched (see §7 — there is an
ordering dependency that must be closed before the first deploy).

Reference implementation: `tailucas/simple-app` (`.clinerules/github-actions.md`).
Runner facts and pitfalls: home-lab `.clinerules/github-runner.md` and
`docs/ci-deploy.md`. This file covers only what is different for **this** app.

## 1. What this application is

The home-automation **event hub**. It is the most composite of the apps:

* a **Python asyncio service** (`app/__main__.py`) that bridges MQTT, RabbitMQ and
  ZeroMQ, watches Ring devices, sends Telegram notifications, and serves a
  **FastAPI** config/API surface;
* a **Spring Boot Java application** (`src/main/java`, `pom.xml`) running in the
  same container as a separate supervisord program (`RUN_JAVA_APP=true`);
* a **cron** job (`config/cron/backup_db`) plus `backup_db.sh` that copies the
  SQLite database to S3.

Both apps run from one image, so "is it up?" has two answers — see §6.

## 2. Runtime contract

| Requirement | Detail |
|---|---|
| **1Password Connect** | Runtime dependency, resolved **at import**: `app/__main__.py` ends with `creds = Creds(); creds.validate_creds(); flask_app.secret_key = creds.get_creds("Frontend/Flask/secret_key")`. Live this arrives as `/run/secrets/op_connect_token` + `op_vault` from compose `secrets:`; `.env` has **no `OP_*`**. That is the one thing incompatible with an unattended runner. |
| **Brokers** | MQTT (`RABBITMQ_SERVER_ADDRESS`, `MQTT_SERVER_ADDRESS`) and RabbitMQ must be reachable; the app retries but logs errors continuously if they are not. |
| **Ngrok** | `NGROK_ENABLED` + `NGROK_CLIENT_API_PORT`; a placeholder port (4040) is published even when disabled. |
| **File descriptors** | `ulimits.nofile` 65536 in compose; without it the app hits EMFILE under load. |
| **Locale** | `LANG`/`LANGUAGE`/`LC_ALL` in `.env`; image generates `en_ZA.UTF-8` from the build args. |
| **`./data`** | See §3 — it holds the primary SQLite database. |

The build needs **no** secret: `mvn package` runs against mocked tests, and
`uv sync --no-dev` resolves from PyPI.

## 3. `./data/` — what it is, and the rules

`/home/tai/event-processor/data` (bind-mounted at `/data`, **24 MB** on 2026-11-27):

| Path | What it is |
|---|---|
| `event-processor.db` | The application's **SQLite database** — config tables, device state, event history. The only irreplaceable thing in this directory. |
| `event-processor.db.bak-<ts>` | Copies made by the cron backup job. |
| `event-processor.db-shm`, `event-processor.db-wal` | SQLite **WAL** sidecars. |

Rules that follow from that:

* **Never copy, tar or `docker cp` a live `-wal`/`-shm` set** — you get a torn
  database. Stop the stack, or use `sqlite3 event-processor.db ".backup"`.
* **`data/` must never enter a build context.** It is gitignored, and
  `.dockerignore` now excludes it; without that every CI build uploads the
  database to a runner.
* **`data/` is not "disposable state"** — a deploy that recreates the container
  must leave it alone. The deploy job never touches it (bind mount, not a volume).
* Ownership must stay uid 999 (`app`); a root-owned `data/` breaks the app on
  restart, and the failure looks like "database is read-only".
* The repository root also carries a stray `event-processor.db` from local runs.
  It is gitignored and `.dockerignore` excludes `*.db`, so it cannot leak into an
  image — but do not delete it expecting it to matter to the deployed stack.

## 4. Current deployment (verified 2026-11-27, read-only)

| Fact | Value |
|---|---|
| Host / dir | `acer`, `/home/tai/event-processor` |
| Container | `event-processor-app-1`, image `tailucas/event-processor`, up with `restarts=0` |
| Ports | 4041→4040 (ngrok), 8095→8080 (**FastAPI**), 8085→8085 (**Java**), 9400→9400 (metrics) |
| Image ref | `tailucas/event-processor:latest` (`lab_image_event_processor`) |
| Catalog | `ansible/vars/container_catalog.yml` → `event-processor` |
| Secrets live | `/run/secrets/op_connect_token` (642 B), `/run/secrets/op_vault` — no `OP_*` in `.env` |
| Repo | `tailucas/event-processor`, branch **`master`** |

## 5. What is specific to this project

Everything in the generic checklist applies; these are the deltas.

1. **Branch is `master`, not `main`.** Both workflows trigger on `master`. A
   copy of the reference workflows would never fire.
2. **Two apps, one container.** `RUN_JAVA_APP=true` makes the base entrypoint add
   the `japp` supervisord program. A Python-only health check can pass while the
   Java app is dead, and vice versa.
3. **`OP_CONNECT_HOST` default was wrong.** The compose file injected
   `OP_CONNECT_HOST=${OP_CONNECT_HOST:-http://host.docker.internal:8080}` — port
   **8080**, but the 1Password Connect *API* is on **8082** (8080 is this app's
   own FastAPI port). The injected default is now removed; `.env` must carry
   `OP_CONNECT_HOST=http://host.docker.internal:8082`.
4. **The Maven-`PATH` workaround is already present.** `Dockerfile` line 3 sets
   `ENV PATH=".../maven/current/bin"`, so the `mvn: command not found` failure
   that hit `simple-app` cannot happen here. Do not remove it.
5. **`.python-version` is already pinned to `3.14`** and copied into the image.
6. **`ruff` and `mypy` are both clean** on `app/` (verified 2026-11-27), so CI
   gates on both. The Java half is covered by `mvn package` inside the image
   build (its tests are mock-based; `OnePasswordTest` mocks the Connect client).
7. **Build output is large** (`target/`, the fat jar) — `.dockerignore` excludes
   `target/`, which otherwise doubles the context and invalidates cache.
8. **`POST /snapshot` is the one verb that makes hardware act.** It publishes the
   Ring bridge's on-demand snapshot trigger (`mq.md` §3), so every camera it names
   wakes up, and the resulting image flows through the normal output links (an SMS
   can follow). Treat it as an operational action, never as a probe. `GET /devices`
   is the read-only check that the Java half and the bridge are talking.

## 6. Verifying a deploy

This app is the easy case: it has a real readiness endpoint, and its own log
lines do reach stdout.

```bash
# the precise readiness signal: the FastAPI app reports its own startup flag
curl -fsS http://127.0.0.1:8095/api/ping        # -> "OK"
curl -fsS http://127.0.0.1:8095/api/running     # -> true

# the Java half, and the shared startup line
curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8085/
docker logs <cid> 2>&1 | grep 'Startup complete'
docker logs <cid> 2>&1 | grep -c 'exited'       # must be 0
```

`/api/running` returns `request.app.state.startup_complete`, which is only set
after the Python app's thread nanny starts — so it is a genuine readiness flag,
not a liveness ping. Note the port mapping: **container 8080 → host 8095**.
Host 8085 is the Java app (Spring), which answers a 404 JSON body on `/`.

Do **not** use `ss -ltn` as evidence: every published port is accepted by
`docker-proxy` whether or not anything is behind it.

Do **not** probe with `POST http://127.0.0.1:8085/snapshot` either: it wakes every
camera and sends an SMS through the existing links. To exercise the snapshot path
deliberately, name one camera (`?device_key=…` from `GET /devices`) and expect `202`
with that device in `requested`, plus a `Ring event`/`image` line within a few
seconds — or a `skipped` reason explaining why not.

## 7. Before the first deploy — ordering matters

The repo compose no longer declares `secrets:`, but **the host was deliberately
not touched**, so today:

* `/home/tai/event-processor/docker-compose.yml` still has `secrets:` and still
  works with the operator's shell;
* `/home/tai/event-processor/.env` still has **no `OP_*`**.

Therefore, in this order, before deploying:

1. Add to the host `.env` (**not** to `base.env`, which is committed):
   `OP_CONNECT_HOST=http://host.docker.internal:8082`, `OP_CONNECT_TOKEN`,
   `OP_VAULT` — the values are already inside the running container at
   `/run/secrets/op_connect_token` and `/run/secrets/op_vault`, so
   `docker exec <cid> cat /run/secrets/op_connect_token` copies them without
   ever printing them. `chmod 600 .env`.
2. Remove the `secrets:` block from the **host** compose (mirror the repo).
3. Prove it with the shell env unset:
   `env -u OP_CONNECT_TOKEN -u OP_VAULT docker compose up -d --force-recreate`
   then confirm `/api/running` is `true`.
4. Only then grant the repo to the `acer-deploy` runner group and let
   `deploy.yml` run.

Skipping step 1 and updating the host compose anyway gives the worst outcome: the
app starts and then dies on `AssertionError: No credential client available`,
because the Python app resolves the Flask secret at import.

## 8. Related documents

| Document | Why |
|---|---|
| `tailucas/simple-app` → `.clinerules/github-actions.md` | reference implementation and the environment/1Password rules |
| `tailucas/net-tool` → `.clinerules/deploy.md` | the catalogue-deployed precedent, including the GHCR→host-name retag |
| home-lab → `docs/ci-deploy.md` | adoption checklist, ownership boundary, acceptance list |
| home-lab → `.clinerules/github-runner.md` | runner names, groups, labels, and the silent-failure pitfalls |
| home-lab → `.clinerules/observability.md` | this app's `/metrics` on 9400 is scraped by Alloy on `acer` |

