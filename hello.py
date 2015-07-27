#!/usr/bin/python

print "hello python!"

import subprocess
df = subprocess.Popen(["df", "-h"], stdout=subprocess.PIPE)
print df.communicate()[0]