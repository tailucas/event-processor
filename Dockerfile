FROM resin/rpi-raspbian:wheezy-2015-07-23

MAINTAINER db2inst1 <db2inst1@webafrica.org.za>
LABEL Description="snapshot_processor" Vendor="db2inst1" Version="1.0"

RUN apt-get update && apt-get install -y \
    curl \
    mplayer \
    rsyslog \
    python-pip \
    python2.7 \
    python2.7-dev \
    vsftpd
RUN echo $(dpkg -l)

COPY ./config/snapshot_processor_pip /tmp/
RUN pip install -r /tmp/snapshot_processor_pip
RUN pip freeze

EXPOSE 21 5556
RUN mkdir -p /storage/ftp

COPY . /app
COPY ./start_hello.sh /

# non-root users
RUN groupadd -r ftpuser && useradd -r -g ftpuser ftpuser
RUN groupadd -r app && useradd -r -g app app
RUN chown app /start_hello.sh
# system configuration
RUN cat /etc/vsftpd.conf | python /app/config_interpol

RUN lsmod
# unload this to prevent these kernel messages,
# apparently when GPIO is not connected.
# w1_master_driver w1_bus_master1: Family 0 for 00.ef9b00000000.48 is not registered.
RUN rmmod w1_gpio
RUN lsmod

ENTRYPOINT ["/start_hello.sh"]
