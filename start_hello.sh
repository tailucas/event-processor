#!/bin/bash
set -eux

./app/gosu
if [ -n "$RSYSLOG_SERVER" ]; then
  ./app/gosu echo "*.*          ${RSYSLOG_SERVER}" >> /etc/rsyslog.conf
  ./app/gosu service rsyslog restart
fi
python /app/hello.py
