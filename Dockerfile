FROM resin/rpi-raspbian:wheezy-2015-07-23

RUN apt-get update && apt-get install -y \
    mplayer \
    python-pip \
    python-rpi.gpio \
    python2.7 \
    python2.7-dev
RUN dpkg -l

COPY ./config/snapshot_processor_pip /tmp/
RUN pip install -r /tmp/snapshot_processor_pip
RUN pip freeze

# copy current directory into /app
COPY . /app

# run python script when container lands on device
CMD ["python", "/app/hello.py"]
