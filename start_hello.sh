#!/bin/bash
set -eux

# remove unnecessary kernel drivers
rmmod w1_gpio||true

if [ -n "${RSYSLOG_SERVER:-}" ]; then
  echo "*.*          @${RSYSLOG_SERVER}" >> /etc/rsyslog.conf
  tail /etc/rsyslog.conf
  service rsyslog restart
fi

# groups
groupadd -r "${APP_GROUP}"

useradd -r -g "${APP_GROUP}" "${FTP_USER}"
mkdir -p "/home/${FTP_USER}/"
mkdir -p "${APP_SNAPSHOTS_ROOT_DIR}"
ln -s "${APP_SNAPSHOTS_ROOT_DIR}" "/home/${FTP_USER}/ftp"
mkdir -p "/home/${FTP_USER}/ftp/files"
chown -R "${FTP_USER}:${APP_GROUP}" "/home/${FTP_USER}/"
chown -R "${FTP_USER}:${APP_GROUP}" "${APP_SNAPSHOTS_ROOT_DIR}"
chmod a-w "/home/${FTP_USER}/ftp"

echo "${FTP_USER}:${FTP_PASSWORD}" | chpasswd

cat /etc/vsftpd.conf | python /app/config_interpol /app/config/vsftpd.conf | sort | tee /etc/vsftpd.conf.new
mv /etc/vsftpd.conf /etc/vsftpd.conf.backup
mv /etc/vsftpd.conf.new /etc/vsftpd.conf
tail /etc/vsftpd.conf
service vsftpd restart

# non-root users
useradd -r -g "${APP_GROUP}" "${APP_USER}"
chown -R "${APP_USER}:${APP_GROUP}" /app/
chown "${APP_USER}:${APP_GROUP}" /start_hello.sh

su -p "${APP_USER}" -c 'python /app/hello.py'
