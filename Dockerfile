FROM tailucas/base-app:latest
# for system/site packages
USER root
# generate correct locales
ARG LANG
ARG LANGUAGE
RUN locale-gen ${LANGUAGE} \
    && locale-gen ${LANG} \
    && update-locale \
    && locale -a
# system setup
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        html-xml-utils \
        sqlite3 \
        wget
# user scripts
COPY backup_db.sh .
# cron jobs
RUN rm -f ./config/cron/base_job
COPY config/cron/backup_db ./config/cron/
# apply override
RUN /opt/app/app_setup.sh
# override application
COPY ./target/app-*.jar ./app.jar
# add the project application
COPY app/__main__.py ./app/
# override configuration
COPY config/app.conf ./config/app.conf
COPY static ./static
COPY templates ./templates
COPY uv.lock pyproject.toml ./
RUN chown app:app uv.lock
# switch to run user
USER app
RUN /opt/app/python_setup.sh
# override entrypoint
COPY app_entrypoint.sh .
CMD ["/opt/app/entrypoint.sh"]
