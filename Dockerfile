FROM resin/rpi-raspbian:wheezy-2015-07-23

MAINTAINER db2inst1 <db2inst1@webafrica.org.za>
LABEL Description="snapshot_processor" Vendor="db2inst1" Version="1.0"

RUN apt-get update && apt-get install -y \
    mplayer \
    python-pip \
    python-rpi.gpio \
    python2.7 \
    python2.7-dev \
    vsftpd
RUN dpkg -l

COPY ./config/snapshot_processor_pip /tmp/
RUN pip install -r /tmp/snapshot_processor_pip
RUN pip freeze

EXPOSE 21 5556
RUN mkdir /storage/ftp

COPY . /app

# ftp user
RUN groupadd -r ftpuser && useradd -r -g ftpuser ftpuser


# switch to non-root user
RUN groupadd -r app && useradd -r -g app app
USER app

RUN bash -c 'cat /etc/vsftpd.conf | ./config_interpol'

# run python script when container lands on device
CMD ["python", "/app/hello.py"]
