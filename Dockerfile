FROM tailucas/base-app:20221228
# for system/site packages
USER root
# override dependencies
COPY requirements.txt .
# apply override
RUN /opt/app/app_setup.sh
# cron jobs
ADD config/backup_db /etc/cron.d/backup_db
RUN crontab -u app /etc/cron.d/backup_db
RUN chmod 0600 /etc/cron.d/backup_db
# switch to user
USER app
# override configuration
COPY config/app.conf ./config/app.conf
COPY config/backup_db ./config/backup_db
COPY config/ngrok_frontend.yml ./config/ngrok_frontend.yml
COPY static ./static
COPY templates ./templates
COPY backup_db.sh .
COPY app_entrypoint.sh .
# ngrok
COPY ngrok_setup.sh .
RUN /opt/app/ngrok_setup.sh
# remove base_app
RUN rm -f /opt/app/base_app
# add the project application
COPY event_processor .
# override entrypoint
COPY app_entrypoint.sh .
CMD ["/opt/app/entrypoint.sh"]
