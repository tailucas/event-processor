#!/bin/bash
set -eux

# remove unnecessary kernel drivers
rmmod w1_gpio
lsmod

if [ -n "${RSYSLOG_SERVER:-}" ]; then
  echo "*.*          @${RSYSLOG_SERVER}" >> /etc/rsyslog.conf
  tail /etc/rsyslog.conf
  service rsyslog restart
fi

sudo -u app python /app/hello.py
