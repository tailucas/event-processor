FROM tailucas/base-app:20230126_2
# for system/site packages
USER root
# system setup
RUN apk --no-cache add \
    html-xml-utils \
    sqlite
# user scripts
COPY backup_db.sh .
# cron jobs
RUN rm -f ./config/cron/base_job
COPY config/cron/backup_db ./config/cron/
# override dependencies
COPY requirements.txt .
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
# remove base_app
RUN rm -f /opt/app/base_app
# add the project application
COPY event_processor .
# override entrypoint
COPY app_entrypoint.sh .
CMD ["/opt/app/entrypoint.sh"]
