FROM resin/rpi-raspbian:wheezy-20160106

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
    vim \
    wget

RUN rm -rf /tmp/*
COPY ./config/pip_freeze /tmp/
# update pip
RUN pip install -U pip
RUN pip install --upgrade setuptools
RUN pip install -r /tmp/pip_freeze
# show outdated packages since the freeze
RUN pip list --outdated

# ssh, http, zmq
EXPOSE 22 5000 5556 5558

# sshd configuration
RUN mkdir /var/run/sshd
RUN mkdir /root/.ssh/

COPY . /app
COPY ./entrypoint.sh /

# awslogs
RUN wget https://s3.amazonaws.com/aws-cloudwatch/downloads/latest/awslogs-agent-setup.py -O /app/awslogs-agent-setup.py
RUN python /app/awslogs-agent-setup.py -n -r "eu-west-1" -c /app/config/awslogs-config
# remove the service and nanny (supervisor does this)
RUN update-rc.d awslogs remove
RUN rm -f /etc/cron.d/awslogs

ENTRYPOINT ["/entrypoint.sh"]
