#!/bin/bash
set -eux

if [ -n "$RSYSLOG_SERVER" ]; then
  echo "*.*          ${RSYSLOG_SERVER}" >> /etc/rsyslog.conf
  service rsyslog restart
fi
python /app/hello.py

