#!/bin/bash
set -eux

echo "rsyslog server is ${RSYSLOG_SERVER}"
if [ -n "$RSYSLOG_SERVER" ]; then
  gosu echo "*.*          ${RSYSLOG_SERVER}" >> /etc/rsyslog.conf
  gosu service rsyslog restart
fi
python /app/hello.py
