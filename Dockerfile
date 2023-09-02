FROM tailucas/base-app:20230831
# for system/site packages
USER root
# generate correct locales
ARG LANG
ENV LANG=$LANG
ARG LANGUAGE
ENV LANGUAGE=$LANGUAGE
ARG LC_ALL
ENV LC_ALL=$LC_ALL
ARG ENCODING
ENV ENCODING=$ENCODING
RUN sed -i -e "s/# ${LANG} ${ENCODING}/${LANG} ${ENCODING}/" /etc/locale.gen && \
    dpkg-reconfigure --frontend=noninteractive locales && \
    update-locale LANG=${LANG} && locale
# system setup
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        html-xml-utils \
        sqlite3
# user scripts
COPY backup_db.sh .
# cron jobs
RUN rm -f ./config/cron/base_job
COPY config/cron/backup_db ./config/cron/
# apply override
RUN /opt/app/app_setup.sh
# ngrok
COPY ngrok_setup.sh .
RUN /opt/app/ngrok_setup.sh
# switch to user
USER app
# override configuration
COPY config/app.conf ./config/app.conf
COPY config/ngrok_frontend.yml ./config/ngrok_frontend.yml
COPY static ./static
COPY templates ./templates
COPY poetry.lock pyproject.toml ./
RUN /opt/app/python_setup.sh
# add the project application
COPY app/__main__.py ./app/
# override entrypoint
COPY app_entrypoint.sh .
CMD ["/opt/app/entrypoint.sh"]
