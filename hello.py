#!/usr/bin/python
import os
import sys
import logging
import logging.handlers
from time import sleep

sleep(5)

APP = os.path.basename(__file__)
log = logging.getLogger(APP)

log.setLevel(logging.DEBUG)
syslog_handler = logging.handlers.SysLogHandler(address='/dev/log')
formatter = logging.Formatter('%(name)s [%(levelname)s] %(message)s')
syslog_handler.setFormatter(formatter)
log.addHandler(syslog_handler)
stream_handler = logging.StreamHandler(stream=sys.stdout)
log.addHandler(stream_handler)

log.info("Hello App!")

import subprocess
#df = subprocess.Popen(["df", "-h"], stdout=subprocess.PIPE)
#print df.communicate()[0]

r = subprocess.Popen(["cat", "/etc/os-release"], stdout=subprocess.PIPE)
print r.communicate()[0]

for key, value in os.environ.items():
    print '{}={}'.format(key, value)

r = subprocess.Popen(["cat", "/etc/rsyslog.conf"], stdout=subprocess.PIPE)
print r.communicate()[0]

r = subprocess.Popen(["tail", "/var/log/syslog"], stdout=subprocess.PIPE)
print r.communicate()[0]

sleep(3600)
