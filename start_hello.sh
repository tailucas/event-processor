#!/bin/bash
set -eux

if [ -n "$RSYSLOG_SERVER" ]; then
  echo "*.*          @${RSYSLOG_SERVER}" >> /etc/rsyslog.conf
  tail /etc/rsyslog.conf
  service rsyslog restart
fi

su - app
python /app/hello.py
