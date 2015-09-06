#!/usr/bin/python
import os

print "hello python!"

import subprocess
df = subprocess.Popen(["df", "-h"], stdout=subprocess.PIPE)
print df.communicate()[0]

r = subprocess.Popen(["cat", "/etc/os-release"], stdout=subprocess.PIPE)
print r.communicate()[0]

for key, value in os.environ.items():
    print '{}={}'.format(key, value)

r = subprocess.Popen(["cat", "/etc/rsyslog.conf"], stdout=subprocess.PIPE)
print r.communicate()[0]

r = subprocess.Popen(["tail", "/var/log/syslog"], stdout=subprocess.PIPE)
print r.communicate()[0]

import time
time.sleep(3600)
