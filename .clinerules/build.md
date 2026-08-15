---
paths:
  - "Makefile"
  - "pyproject.toml"
  - "uv.lock"
  - "pom.xml"
  - "rules.xml"
  - "Cargo.toml"
  - "rapp/Cargo.toml"
  - "rlib/Cargo.toml"
  - "go.mod"
  - "go.sum"
  - "*_setup.sh"
---

# base-app Build System & Multi-language Toolchains

base-app builds four language components (Python, Java, Rust, Go) from a
single `Makefile`. The Makefile is a **build graph**, not a list of commands:
artifacts are modeled as real file targets so repeated invocations skip work
that is already up to date. This file documents the conventions a fork must
preserve when adding or removing a runtime.

## 1. Makefile conventions

- **Self-documenting.** `make` with no arguments (or `make help`) lists every
  target with a one-line description extracted from the `##` comment on the
  same line. Add a `##` comment to every new target.
- **Incremental builds via real file targets.** Generated artifacts are file
  targets that rebuild only when missing or when their inputs change:
  - `.env` depends on `base.env` and `docker-compose.yml`, and is
    sanity-checked with a minimum line count.
  - `.venv` depends on `pyproject.toml` and `uv.lock`.
  - `target/app-0.1.0-jar-with-dependencies.jar` depends on `pom.xml` and all
    `*.java` under `src/` (`JAVA_SOURCES`).
  - `go.mod`/`go.sum` model the Go module bootstrap.
- **Failure safety.** `.DELETE_ON_ERROR` deletes the partial output of a
  failed recipe, so a stale artifact can never look up-to-date.
- **Ordered prerequisites.** `make run` resolves `data/` → `build` → `.env`
  in order before starting the container. Prerequisite order is only
  guaranteed for serial builds — **do not use `make -j`**.
- **Inlined preconditions.** Tool checks (`docker`, `uv`, `java`/`javac`/`mvn`,
  `go`) and environment checks (a running 1Password Connect container) fail
  fast with clear messages before any work is done.
- **Host vs. container awareness.** `make dev`, `dev-build`, and `dev-up`
  manage the VS Code dev container and are guarded by `make check`, which
  refuses to run *inside* the container. All other targets work identically on
  the host or in the dev container via Docker-outside-of-Docker.
- **Parameterized ownership.** `data/` is owned by `USER_ID:GROUP_ID`
  (default `999:999`); override per invocation, e.g.
  `make datadir USER_ID=1000 GROUP_ID=1000`.

## 2. Python (`pyproject.toml`, `uv`)

- Dependencies are managed with **uv**; `pyproject.toml` + `uv.lock` are the
  source of truth. The only runtime dependency is the `tailucas-pylib[...]`
  extras — never vendor copies of shared-library code.
- **Dependency groups** separate runtime from dev (`[dependency-groups] dev`:
  `ruff`, `mypy`, `pytest`). The production image sets `UV_NO_DEFAULT_GROUPS=1`
  so `uv sync` installs main dependencies only.
- **Entry-point scripts** (`[project.scripts]`) expose the pylib CLI tools:
  `cred_tool`, `config_interpol`, `yaml_interpol`. These are invoked via
  `uv run --frozen --no-sync <tool>` inside the container.
- Lint with `make lint` (`ruff check app/` + `mypy app/`) before considering
  work done.

## 3. Java (`pom.xml`, Maven)

- Plain Maven jar (no framework); the entry point is `tailucas.app.App`,
  packaged as a fat jar via the **shade plugin** (classifier
  `jar-with-dependencies`). Shade is required, not assembly, because it merges
  log4j2 plugin caches and OTEL SPI services that a plain
  `jar-with-dependencies` would silently clobber (see `observability.md`).
- **Version properties.** Every dependency/plugin version is a `pom.xml`
  property (e.g. `log4j.version`, `opentelemetry.version`), never a literal.
- **Upgrade workflow** uses `versions-maven-plugin` with `rules.xml`
  (ignores pre-release versions). Check with
  `mvn versions:display-dependency-updates versions:display-plugin-updates`;
  apply with `mvn versions:update-properties`.
- **Dependency analysis** (`maven-dependency-plugin` `analyze-only` with
  `failOnWarning=true`) runs on `verify`; runtime-wired OTEL artifacts are
  whitelisted in `ignoredUnusedDeclaredDependencies`.
- `maven.compiler.release` and the Dockerfile's SDKMAN Java version must stay
  in sync (currently `26`).

## 4. Rust (`Cargo.toml`, `rapp/`, `rlib/`)

- A **Cargo workspace** with two members: `rlib` (shared library) and `rapp`
  (example binary that depends on `rlib` via a path dependency).
- Rust is an optional demo runtime behind `RUN_RUST_APP=true`; the supervised
  program runs `cargo run --release` (see `container.md`).
- For operational logging, use a structured facade (`tracing` or `log`), not
  `println!` (see `logging.md`).

## 5. Go (`internal/`, `go.mod`)

- A single `internal/main.go` hello-world stub, bootstrapped via
  `make golang` (`go mod init` + `go mod tidy`).
- Go is an optional demo runtime behind `RUN_GO_APP=true`; the supervised
  program runs `go run ./internal/main.go` (see `container.md`).
- Use `log/slog` with structured attributes for operational logging, not
  `fmt.Printf` (see `logging.md`).

## 6. Setup scripts

Each toolchain has a dedicated `*_setup.sh` run during the Docker build
(`java_setup.sh`, `python_setup.sh`, `rust_setup.sh`, `go_setup.sh`). Keep
toolchain installs in these scripts rather than inlining them in the
Dockerfile; `java_setup.sh` quietens Maven output under `GITHUB_ACTIONS` for
CI builds.