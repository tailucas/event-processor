FROM resin/rpi-raspbian:wheezy

MAINTAINER db2inst1 <db2inst1@webafrica.org.za>
LABEL Description="snapshot_processor" Vendor="db2inst1" Version="1.0"

RUN apt-get update && apt-get install -y --no-install-recommends \
    alsa-utils \
    ca-certificates \
    cron \
    cpp \
    curl \
    g++ \
    gcc \
    less \
    libffi-dev \
    libssl-dev \
    mplayer \
    openssh-server \
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

# SSH
EXPOSE 22

# zmq
EXPOSE 5556

# sshd configuration
RUN mkdir /var/run/sshd
RUN echo 'root:resin' | chpasswd
RUN sed -i 's/PermitRootLogin without-password/PermitRootLogin yes/' /etc/ssh/sshd_config
# SSH login fix. Otherwise user is kicked off after login
RUN sed 's@session\s*required\s*pam_loginuid.so@session optional pam_loginuid.so@g' -i /etc/pam.d/sshd

COPY . /app
COPY ./entrypoint.sh /

ENTRYPOINT ["/entrypoint.sh"]
