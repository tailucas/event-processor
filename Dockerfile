FROM resin/raspberrypi3-debian:latest
ENV INITSYSTEM on

MAINTAINER db2inst1 <db2inst1@webafrica.org.za>
LABEL Description="event_processor" Vendor="db2inst1" Version="1.0"

COPY . /app
# unzip helpers
RUN unzip /app/*.zip -d /app/
RUN apt-get clean && apt-get update && apt-get install -y --no-install-recommends \
    alsa-utils \
    ca-certificates \
    cron \
    cpp \
    curl \
    dbus \
    g++ \
    gcc \
    git \
    htop \
    less \
    lsof \
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
    strace \
    unzip \
    vim \
    wavemon \
    wget \
    # pip 8
    && python /app/pipstrap.py

RUN pip install -r /app/config/requirements.txt

# ssh, http, zmq, ngrok
EXPOSE 22 5000 5556 5558 4040 8080
CMD ["./app/entrypoint.sh"]