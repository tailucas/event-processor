FROM resin/rpi-raspbian:wheezy

MAINTAINER db2inst1 <db2inst1@webafrica.org.za>
LABEL Description="snapshot_processor" Vendor="db2inst1" Version="1.0"

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    mplayer \
    rsyslog \
    python-pip \
    python2.7 \
    python2.7-dev \
    vsftpd

COPY ./config/snapshot_processor_pip /tmp/
RUN pip install -r /tmp/snapshot_processor_pip

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

ENTRYPOINT ["/start_hello.sh"]
