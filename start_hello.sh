#!/bin/bash
set -eux

# remove unnecessary kernel drivers
rmmod w1_gpio||true

if [ -n "${RSYSLOG_SERVER:-}" ]; then
  echo "*.*          @${RSYSLOG_SERVER}" >> /etc/rsyslog.conf
  tail /etc/rsyslog.conf
  service rsyslog restart
fi

export FTP_ROOT=/storage/ftp/
cat /etc/vsftpd.conf | python /app/config_interpol /app/vsftpd.conf > /etc/vsftpd.conf
tail /etc/vsftpd.conf
service vsftpd restart

su -p app -c 'python /app/hello.py'
