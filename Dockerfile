FROM tailucas/base-app:latest AS builder
# prepare source
COPY src ./src/
COPY java_setup.sh pom.xml rules.xml ./
RUN "${APP_DIR}/java_setup.sh"

###############################################################################

FROM tailucas/base-app:latest
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
# add the project application
COPY app/__main__.py ./app/
# override configuration
COPY config/app.conf ./config/app.conf
COPY static ./static
COPY templates ./templates
# Python
COPY app ./app
COPY pyproject.toml uv.lock ./
RUN chown app:app uv.lock
# Java
COPY --from=builder "${APP_DIR}/target/app-0.1.0.jar" ./app.jar
# switch to run user now because uv does not use the environment to infer
USER app
RUN "${APP_DIR}/rust_setup.sh"
RUN "${APP_DIR}/python_setup.sh"
# example HTTP backend
# EXPOSE 8080
# override entrypoint
COPY app_entrypoint.sh .
CMD ["/opt/app/entrypoint.sh"]
