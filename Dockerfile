FROM resin/rpi-raspbian:wheezy-2015-07-23

# Install Python.
RUN apt-get update && apt-get install -y python

# copy current directory into /app
COPY . /app

# run python script when container lands on device
CMD ["python", "/app/hello.py"]
