FROM resin/raspberrypi2-debian:latest

MAINTAINER db2inst1 <db2inst1@webafrica.org.za>
LABEL Description="event_processor" Vendor="db2inst1" Version="1.0"

COPY ./pipstrap.py /tmp/

RUN apt-get clean && apt-get update && apt-get install -y --no-install-recommends \
    alsa-utils \
    ca-certificates \
    cron \
    cpp \
    curl \
    dbus \
    g++ \
    gcc \
    jq \
    less \
    libffi-dev \
    libssl-dev \
    man-db \
    manpages \
    net-tools \
    openssh-server \
    openssl \
    psmisc \
    python-dbus \
    python-pip \
    python2.7 \
    python2.7-dev \
    rsyslog \
    ssl-cert \
    supervisor \
    unzip \
    vim \
    wget \
    # pip 8
    && python /tmp/pipstrap.py

COPY ./config/pip_freeze /tmp/
RUN pip install -r /tmp/pip_freeze
# show outdated packages since the freeze
RUN pip list --outdated

# ssh, http, zmq
EXPOSE 22 5000 5556 5558 4040 8080

# sshd configuration
RUN mkdir /var/run/sshd
RUN mkdir /root/.ssh/


COPY . /app
# unzip helpers
RUN unzip /app/*.zip -d /app/
COPY ./entrypoint.sh /

ENTRYPOINT ["/entrypoint.sh"]
