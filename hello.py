#!/usr/bin/python

print "hello python!"

import subprocess
df = subprocess.Popen(["df", "-h"], stdout=subprocess.PIPE)
print df.communicate()[0]

r = subprocess.Popen(["cat", "/etc/*-release"], stdout=subprocess.PIPE)
print r.communicate()[0]

import time
time.sleep(3600)
