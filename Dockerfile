FROM resin/raspberrypi3-debian:stretch
ENV INITSYSTEM on

MAINTAINER db2inst1 <db2inst1@webafrica.org.za>
LABEL Description="event_processor" Vendor="db2inst1" Version="1.0"

# http://unix.stackexchange.com/questions/339132/reinstall-man-pages-fix-man
RUN rm -f /etc/dpkg/dpkg.cfg.d/01_nodoc
RUN rm -f /etc/dpkg/dpkg.cfg.d/docker
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
    python3-venv \
    rsyslog \
    ssl-cert \
    strace \
    unzip \
    vim \
    wavemon \
    wget

COPY ./config/requirements.txt /tmp/
RUN pip3 install -r /tmp/requirements.txt

COPY . /app

# ngrok
RUN /app/ngrok_setup.sh

# Resin systemd
COPY ./config/systemd.launch.service /etc/systemd/system/launch.service.d/app_override.conf

# no ipv6
RUN echo '\n\
net.ipv6.conf.all.disable_ipv6 = 1' >> /etc/sysctl.conf

# AWS IoT root CA
# https://github.com/aws/aws-iot-device-sdk-python
RUN wget -nv -O /app/iot_ca.pem https://www.symantec.com/content/en/us/enterprise/verisign/roots/VeriSign-Class%203-Public-Primary-Certification-Authority-G5.pem

# ssh, http, zmq, ngrok
EXPOSE 22 5000 5556 5558 4040 8080
CMD ["/app/entrypoint.sh"]