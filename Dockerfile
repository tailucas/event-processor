FROM resin/rpi-raspbian:wheezy-2015-07-23

MAINTAINER db2inst1 <db2inst1@webafrica.org.za>
LABEL Description="snapshot_processor" Vendor="db2inst1" Version="1.0"

RUN apt-get update && apt-get install -y \
    mplayer \
    rsyslog \
    python-pip \
    python2.7 \
    python2.7-dev \
    vsftpd
RUN echo $(dpkg -l)

COPY ./config/snapshot_processor_pip /tmp/
RUN pip install -r /tmp/snapshot_processor_pip
RUN pip freeze

EXPOSE 21 5556
RUN mkdir -p /storage/ftp

COPY . /app

# non-root users
RUN groupadd -r ftpuser && useradd -r -g ftpuser ftpuser
RUN groupadd -r app && useradd -r -g app app

# system configuration
RUN cat /etc/vsftpd.conf | python /app/config_interpol
RUN if [ -n "$RSYSLOG_SERVER" ]; then echo "*.*          ${RSYSLOG_SERVER}" >> /etc/rsyslog.conf; fi
RUN service rsyslog restart
RUN cat /etc/rsyslog.conf

# switch to non-root user
USER app
RUN whoami
RUN logger Hello
# run python script when container lands on device
CMD ["python", "/app/hello.py"]
