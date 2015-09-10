#!/usr/bin/python
import os
import signal
import sys
import logging
import logging.handlers
from time import sleep, time

APP = os.path.basename(__file__)
log = logging.getLogger(APP)

def handler(signum, frame):
    log.info('Goodbye signal {}'.format(signum))
    exit(0)

if __name__ == "__main__":
    signal.signal(signal.SIGTERM, handler)

    log.setLevel(logging.DEBUG)
    syslog_handler = logging.handlers.SysLogHandler(address='/dev/log')
    formatter = logging.Formatter('%(name)s [%(levelname)s] %(message)s')
    syslog_handler.setFormatter(formatter)
    log.addHandler(syslog_handler)
    stream_handler = logging.StreamHandler(stream=sys.stdout)
    log.addHandler(stream_handler)

    for key,value in os.environ.items():
        log.info("{}: {}".format(key, value))

    while True:
        log.info('hello {}'.format(time()))
        sleep(1)
