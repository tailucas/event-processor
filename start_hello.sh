#!/bin/bash
set -eux

gosu
if [ -n "$RSYSLOG_SERVER" ]; then
  gosu echo "*.*          ${RSYSLOG_SERVER}" >> /etc/rsyslog.conf
  gosu service rsyslog restart
fi
python /app/hello.py
