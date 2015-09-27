#!/bin/bash
set -eux

# remote system logging
if [ -n "${RSYSLOG_SERVER:-}" ]; then
  echo "*.*          @${RSYSLOG_SERVER}" | tee -a /etc/rsyslog.conf
fi

# remove unnecessary kernel drivers
rmmod w1_gpio||true

# groups
groupadd -r "${APP_GROUP}"

useradd -r -g "${APP_GROUP}" "${FTP_USER}"
FTP_HOME="/home/${FTP_USER}"
mkdir -p "${FTP_HOME}/"
export FTP_ROOT="${FTP_HOME}/ftp"
export STORAGE_ROOT="/storage/ftp"
STORAGE_UPLOADS="${STORAGE_ROOT}/uploads"
mkdir -p "${STORAGE_UPLOADS}"
ln -s "$STORAGE_ROOT" "$FTP_ROOT"
chown -R "${FTP_USER}:${APP_GROUP}" "${FTP_HOME}/"
chown -R "${FTP_USER}:${APP_GROUP}" "${STORAGE_ROOT}/"
chmod a-w "${FTP_ROOT}"

cat /app/config/cleanup_snapshots | sed 's/__STORAGE__/'"${STORAGE_UPLOADS//\//\/}\/"'/g' > /etc/cron.d/cleanup_snapshots

echo "${FTP_USER}:${FTP_PASSWORD}" | chpasswd

cat /etc/vsftpd.conf | python /app/config_interpol /app/config/vsftpd.conf | sort | tee /etc/vsftpd.conf.new
mv /etc/vsftpd.conf /etc/vsftpd.conf.backup
mv /etc/vsftpd.conf.new /etc/vsftpd.conf
# secure_chroot_dir
mkdir -p /var/run/vsftpd/empty

# application configuration (no tee for secrets)
cat /app/config/snapshot_processor.conf | python /app/config_interpol > /app/snapshot_processor.conf

echo "$GOOGLE_CLIENT_SECRETS" > /app/client_secrets.json

# non-root users
useradd -r -g "${APP_GROUP}" "${APP_USER}"
chown -R "${APP_USER}:${APP_GROUP}" /app/

# I'm the supervisor
cat /app/config/supervisord.conf | python /app/config_interpol | tee /etc/supervisor/conf.d/supervisord.conf
/usr/bin/supervisord