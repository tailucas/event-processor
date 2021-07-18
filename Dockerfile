FROM balenalib/raspberrypi3-debian:buster-run
ENV INITSYSTEM on
ENV container docker

LABEL Description="event_processor" Vendor="tglucas" Version="1.0"

ENV DEBIAN_FRONTEND noninteractive
ENV DEBCONF_NONINTERACTIVE_SEEN true

RUN apt-get clean && apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    ca-certificates \
    cron \
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
    systemd \
    tree \
    unzip \
    vim \
    wget \
    && pip3 install \
        tzupdate \
    && rm -rf /var/lib/apt/lists/*

# python3 default
RUN update-alternatives --install /usr/bin/python python /usr/bin/python3 1

COPY . /opt/app

# setup
RUN /opt/app/app_setup.sh

# ngrok
RUN /opt/app/ngrok_setup.sh

# systemd masks for containers
# https://github.com/balena-io-library/base-images/blob/master/examples/INITSYSTEM/systemd/systemd.v230/Dockerfile
RUN systemctl mask \
    dev-hugepages.mount \
    sys-fs-fuse-connections.mount \
    sys-kernel-config.mount \
    display-manager.service \
    getty@.service \
    systemd-logind.service \
    systemd-remount-fs.service \
    getty.target \
    graphical.target \
    kmod-static-nodes.service \
    NetworkManager.service

# no ipv6
RUN echo '\n\
net.ipv6.conf.all.disable_ipv6 = 1' >> /etc/sysctl.conf

STOPSIGNAL 37
# ssh, http, zmq, ngrok
EXPOSE 22 5000 5556 5558 4040 8080
CMD ["/opt/app/entrypoint.sh"]
