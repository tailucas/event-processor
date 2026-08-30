FROM tailucas/base-app:latest AS builder
# dependenty manifest first (rarely changes — cached unless pom.xml is touched)
COPY java_setup.sh pom.xml rules.xml spotbugs-exclude.xml ./
# pre-download all Maven dependencies (layer cached unless pom.xml changes)
RUN mvn dependency:go-offline
# source code (changes most frequently — only invalidates compile, not download)
COPY src ./src/
RUN "${APP_DIR}/java_setup.sh"

###############################################################################

FROM tailucas/base-app:latest
# production image: uv installs main dependencies only (ignore default dependency groups)
ENV UV_NO_DEFAULT_GROUPS=1
# for system/site packages
USER root
ARG DEBIAN_FRONTEND=noninteractive
# system setup
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        html-xml-utils \
        sqlite3 \
        wget \
    && rm -rf /var/lib/apt/lists/*
# generate correct locales
ARG LANG
ARG LANGUAGE
RUN locale-gen ${LANGUAGE} \
    && locale-gen ${LANG} \
    && update-locale \
    && locale -a
# user scripts
COPY backup_db.sh .
# cron jobs
RUN rm -f ./config/cron/base_job
COPY config/cron/backup_db ./config/cron/
# apply override
RUN "${APP_DIR}/app_setup.sh"
# Python dependency files + source (grouped: uv sync builds from source, needs app/)
COPY --chown=app:app .python-version pyproject.toml uv.lock ./
COPY --chown=app:app app ./app
# switch to run user now because uv does not use the environment to infer
USER app
RUN "${APP_DIR}/python_setup.sh"
# Java fat jar (root-owned OK for read-only at runtime)
COPY --from=builder "${APP_DIR}/target/app-0.1.0.jar" ./app.jar
# fast assets: override configuration, templates, entrypoint (no RUN, cheap to invalidate)
COPY --chown=app:app config/app.conf ./config/app.conf
COPY --chown=app:app static ./static
COPY --chown=app:app templates ./templates
COPY --chown=app:app app_entrypoint.sh .
# example HTTP backend
# EXPOSE 8080
CMD ["/opt/app/entrypoint.sh"]
