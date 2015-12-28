FROM resin/rpi-raspbian:wheezy

MAINTAINER db2inst1 <db2inst1@webafrica.org.za>
LABEL Description="event_processor" Vendor="db2inst1" Version="1.0"

RUN apt-get update && apt-get install -y --no-install-recommends \
    alsa-utils \
    ca-certificates \
    cron \
    cpp \
    curl \
    dbus \
    g++ \
    gcc \
    iptables \
    less \
    libffi-dev \
    libssl-dev \
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
    vim

COPY ./config/pip_freeze /tmp/
# update pip
RUN pip install -U pip
RUN pip install --upgrade setuptools
RUN pip install -r /tmp/pip_freeze
# show outdated packages since the freeze
RUN pip list --outdated

# SSH
EXPOSE 22

# HTTP
EXPOSE 5000

# zmq
EXPOSE 5556
EXPOSE 5558

# sshd configuration
RUN mkdir /var/run/sshd
RUN mkdir /root/.ssh/

COPY . /app
COPY ./entrypoint.sh /

ENTRYPOINT ["/entrypoint.sh"]
