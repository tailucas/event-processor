FROM debian:buster
ENV INITSYSTEM on
ENV container docker

LABEL Description="event_processor" Vendor="tglucas" Version="1.0"

ENV DEBIAN_FRONTEND noninteractive
ENV DEBCONF_NONINTERACTIVE_SEEN true

RUN apt clean && apt update && apt install -y --no-install-recommends \
    build-essential \
    ca-certificates \
    cron \
    curl \
    dbus \
    html-xml-utils \
    htop \
    jq \
    lsof \
    libffi-dev \
    # for rust build of Python cryptography
    libssl-dev \
    network-manager \
    openssh-server \
    patch \
    python3-certifi \
    python3-dbus \
    python3 \
    python3-dev \
    python3-pip \
    python3-setuptools \
    python3-venv \
    python3-wheel \
    rsyslog \
    strace \
    sqlite3 \
    systemd \
    tree \
    unzip \
    vim \
    wget

# python3 default
RUN update-alternatives --install /usr/bin/python python /usr/bin/python3 1

# setup
WORKDIR /opt/app
COPY requirements.txt .
COPY pylib/requirements.txt ./pylib/requirements.txt
COPY app_setup.sh .
RUN /opt/app/app_setup.sh
# ngrok
COPY ngrok_setup.sh .
RUN /opt/app/ngrok_setup.sh

COPY config ./config
COPY static ./static
COPY templates ./templates
COPY backup_db.sh .
COPY entrypoint.sh .
COPY pylib ./pylib
COPY pylib/pylib ./lib
COPY event_processor .

STOPSIGNAL 37
# ssh, http, zmq, ngrok
EXPOSE 22 5000 5556 5558 4040 8080
CMD ["/opt/app/entrypoint.sh"]
