#!/bin/bash
set -eux

# remote system logging
if [ -n "${RSYSLOG_HOSTNAME:-}" ]; then
  echo "${RSYSLOG_HOSTNAME}" > /etc/hostname
  # apply the new hostname
  /etc/init.d/hostname.sh start
  # update hosts
  echo "127.0.1.1 ${RSYSLOG_HOSTNAME}" >> /etc/hosts
fi
if [ -n "${RSYSLOG_SERVER:-}" ]; then
  echo "*.*          @${RSYSLOG_SERVER}" | tee -a /etc/rsyslog.conf
fi

# root user access, try a cert
if [ -n "$SSH_AUTHORIZED_KEY" ]; then
  echo "$SSH_AUTHORIZED_KEY" > /root/.ssh/authorized_keys
  chmod 600 /root/.ssh/authorized_keys
else
  echo 'root:resin' | chpasswd
  sed -i 's/PermitRootLogin without-password/PermitRootLogin yes/' /etc/ssh/sshd_config
  # SSH login fix. Otherwise user is kicked off after login
  sed 's@session\s*required\s*pam_loginuid.so@session optional pam_loginuid.so@g' -i /etc/pam.d/sshd
fi

# remove unnecessary kernel drivers
rmmod w1_gpio||true

# groups
groupadd -f -r "${APP_GROUP}"

useradd -r -g "${APP_GROUP}" "${FTP_USER}"
FTP_HOME="/home/${FTP_USER}"
mkdir -p "${FTP_HOME}/"
export FTP_ROOT="${FTP_HOME}/ftp"
export STORAGE_ROOT="/data/ftp"
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

# tts samples
cp -rv /app/tts_samples/ /data/

# client details
echo "$GOOGLE_CLIENT_SECRETS" > /app/client_secrets.json
# we may already have a valid auth token
if [ -n "${GOOGLE_OAUTH_TOKEN:-}" ]; then
  echo "$GOOGLE_OAUTH_TOKEN" > /data/snapshot_processor_creds
fi

# non-root users
useradd -r -g "${APP_GROUP}" "${APP_USER}"
chown -R "${APP_USER}:${APP_GROUP}" /app/
# non-volatile storage
chown "${APP_USER}:${APP_GROUP}" /data/
chown "${APP_USER}:${APP_GROUP}" /data/*
# so app user can make the noise
adduser "${APP_USER}" audio

# I'm the supervisor
cat /app/config/supervisord.conf | python /app/config_interpol | tee /etc/supervisor/conf.d/supervisord.conf
/usr/bin/supervisord