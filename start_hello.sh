#!/bin/bash
set -eux

# remove unnecessary kernel drivers
rmmod w1_gpio||true

if [ -n "${RSYSLOG_SERVER:-}" ]; then
  echo "*.*          @${RSYSLOG_SERVER}" >> /etc/rsyslog.conf
  tail /etc/rsyslog.conf
  service rsyslog restart
fi

groupadd -r "${FTP_USER}" && useradd -r -g "${FTP_USER}" "${FTP_USER}"
mkdir -p "/home/${FTP_USER}/"
mkdir -p "${APP_SNAPSHOTS_ROOT_DIR}"
ln -s "${APP_SNAPSHOTS_ROOT_DIR}" "/home/${FTP_USER}/ftp"
chown -R "${FTP_USER}" "/home/${FTP_USER}/"
chown -R "${FTP_USER}" "${APP_SNAPSHOTS_ROOT_DIR}"
#chmod a-w "/home/${FTP_USER}/ftp"
echo "${FTP_USER}:${FTP_PASSWORD}" | chpasswd

cat /etc/vsftpd.conf | python /app/config_interpol /app/config/vsftpd.conf | sort | tee /etc/vsftpd.conf.new
mv /etc/vsftpd.conf /etc/vsftpd.conf.backup
mv /etc/vsftpd.conf.new /etc/vsftpd.conf
tail /etc/vsftpd.conf
service vsftpd restart

# non-root users
groupadd -r app && useradd -r -g app app
chown -R app /app/
chown app /start_hello.sh

su -p app -c 'python /app/hello.py'
