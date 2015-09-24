FROM resin/rpi-raspbian:wheezy

MAINTAINER db2inst1 <db2inst1@webafrica.org.za>
LABEL Description="snapshot_processor" Vendor="db2inst1" Version="1.0"

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    cpp \
    curl \
    g++ \
    gcc \
    libffi-dev \
    libssl-dev \
    mplayer \
    openssl \
    python-pip \
    python2.7 \
    python2.7-dev \
    rsyslog \
    ssl-cert \
    vsftpd

COPY ./config/snapshot_processor_pip /tmp/
# update pip
RUN pip install -U pip
RUN pip install -r /tmp/snapshot_processor_pip
# show outdated packages since the freeze
RUN pip list --outdated

# FTP
EXPOSE 21
RUN groupadd -r "${FTP_USER}" && useradd -r -g "${FTP_USER}" "${FTP_USER}"
RUN mkdir -p "/home/${FTP_USER}/"
RUN mkdir -p "${APP_SNAPSHOTS_ROOT_DIR}"
RUN ln -s "${APP_SNAPSHOTS_ROOT_DIR}/" "/home/${FTP_USER}/ftp"
RUN chown -R "/home/${FTP_USER}/"
RUN chown -R "${APP_SNAPSHOTS_ROOT_DIR}/"
RUN chmod a-w "/home/${FTP_USER}/ftp"
RUN echo "${FTP_USER}:${FTP_PASSWORD}" | chpasswd

# zmq
EXPOSE 5556

COPY . /app
COPY ./start_hello.sh /

# non-root users
RUN groupadd -r app && useradd -r -g app app
RUN chown -R app /app/
RUN chown app /start_hello.sh

ENTRYPOINT ["/start_hello.sh"]
