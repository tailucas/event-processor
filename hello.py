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

import time
time.sleep(3600)
