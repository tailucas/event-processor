#!/usr/bin/python
import os

from resin.auth import Auth
from resin.models.application import Application
from resin.models.device import Device

if __name__ == "__main__":
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
