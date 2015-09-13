#!/bin/bash
set -eux

# remove unnecessary kernel drivers
rmmod w1_gpio||true

if [ -n "${RSYSLOG_SERVER:-}" ]; then
  echo "*.*          @${RSYSLOG_SERVER}" >> /etc/rsyslog.conf
  tail /etc/rsyslog.conf
  service rsyslog restart
fi

su -p app -c 'python /app/hello.py'
