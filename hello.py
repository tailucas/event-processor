#!/usr/bin/python
import os
import signal
import sys
import logging
import logging.handlers

from resin.auth import Auth
from resin.models.application import Application
from resin.models.device import Device

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

    for k,v in os.environ.items():
        print '{}: {}'.format(k,v)

    resin_auth = Auth()
    resin_auth.login_with_token(os.environ.get('AUTH_KEY_RESIN'))
    device_id = os.environ.get('RESIN_DEVICE_UUID')
    device = Device()
    device_app_name = device.get_application_name(device_id)
    device_name = device.get_name(device_id)
    device_ip = device.get_local_ip_address(device_id)
    print 'Device {} ({}) is running app {}'.format(device_name, device_ip, device_app_name)
    application = Application()
    api_key = application.get_api_key(device_app_name)
    print 'My API key is {}'.format(api_key)


    log.info('HOME is {}'.format(os.environ.get('HOME')))

    while True:
        log.info('hello {}'.format(time()))
        sleep(60*5)
