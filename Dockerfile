FROM resin/rpi-raspbian:wheezy

MAINTAINER db2inst1 <db2inst1@webafrica.org.za>
LABEL Description="snapshot_processor" Vendor="db2inst1" Version="1.0"

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    cron \
    cpp \
    curl \
    g++ \
    gcc \
    libffi-dev \
    libssl-dev \
    mplayer \
    openssl \
    python-gammu \
    python-pip \
    python2.7 \
    python2.7-dev \
    rsyslog \
    ssl-cert \
    supervisor \
    vsftpd

COPY ./config/snapshot_processor_pip /tmp/
# update pip
RUN pip install -U pip
RUN pip install -r /tmp/snapshot_processor_pip
# show outdated packages since the freeze
RUN pip list --outdated

# FTP
EXPOSE 21

# zmq
EXPOSE 5556

COPY . /app
COPY ./entrypoint.sh /

ENTRYPOINT ["/entrypoint.sh"]
