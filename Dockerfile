FROM balenalib/raspberrypi3-debian:stretch-run
ENV INITSYSTEM on
ENV container docker

MAINTAINER db2inst1 <db2inst1@webafrica.org.za>
LABEL Description="event_processor" Vendor="db2inst1" Version="1.0"

# http://unix.stackexchange.com/questions/339132/reinstall-man-pages-fix-man
RUN rm -f /etc/dpkg/dpkg.cfg.d/01_nodoc /etc/dpkg/dpkg.cfg.d/docker
RUN apt-get clean && apt-get update && apt-get install -y --no-install-recommends \
    alsa-utils \
    apt-utils \
    ca-certificates \
    cron \
    cpp \
    curl \
    dbus \
    g++ \
    gcc \
    git \
    html-xml-utils \
    htop \
    ifupdown \
    less \
    lsof \
    libffi-dev \
    libssl-dev \
    libzmq3-dev \
    man-db \
    manpages \
    net-tools \
    openssh-server \
    openssl \
    psmisc \
    python3-dbus \
    python3 \
    python3-dev \
    python3-pip \
    python3-setuptools \
    python3-venv \
    rsyslog \
    ssl-cert \
    strace \
    systemd \
    tree \
    unzip \
    vim \
    wavemon \
    wget \
    && pip3 install \
        tzupdate

# python3 default
RUN update-alternatives --install /usr/bin/python python /usr/bin/python3 1

COPY . /opt/app

# setup
RUN /opt/app/app_setup.sh

# ngrok
RUN /opt/app/ngrok_setup.sh

# systemd masks for containers
# https://github.com/balena-io-library/base-images/blob/b4fc5c21dd1e28c21e5661f65809c90ed7605fe6/examples/INITSYSTEM/systemd/systemd/Dockerfile#L11-L22
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
    kmod-static-nodes.service

# no ipv6
RUN echo '\n\
net.ipv6.conf.all.disable_ipv6 = 1' >> /etc/sysctl.conf

# AWS IoT root CA
# https://github.com/aws/aws-iot-device-sdk-python
# https://docs.aws.amazon.com/iot/latest/developerguide/server-authentication.html
RUN wget -nv -O /opt/app/iot_ca.pem https://www.amazontrust.com/repository/AmazonRootCA1.pem

STOPSIGNAL 37
# ssh, http, zmq, ngrok
EXPOSE 22 5000 5556 5558 4040 8080
CMD ["/opt/app/entrypoint.sh"]