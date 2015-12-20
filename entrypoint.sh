#!/bin/bash
set -eux

# Resin API key
export RESIN_API_KEY="${RESIN_API_KEY:-$API_KEY_RESIN}"

# Run user
export APP_USER="${APP_USER:-app}"
export APP_GROUP="${APP_GROUP:-app}"

TZ_CACHE=/data/localtime
# a valid symlink
if [ -h "$TZ_CACHE" ] && [ -e "$TZ_CACHE" ]; then
  cp -a "$TZ_CACHE" /etc/localtime
else
  # set the timezone
  tzupdate
  cp -a /etc/localtime "$TZ_CACHE"
fi

# remote system logging
if [ -n "${RSYSLOG_HOSTNAME:-}" ] && ! grep -q "$RSYSLOG_HOSTNAME" /etc/hostname; then
  echo "${RSYSLOG_HOSTNAME}" > /etc/hostname
  # apply the new hostname
  /etc/init.d/hostname.sh start
  # update hosts
  echo "127.0.1.1 ${RSYSLOG_HOSTNAME}" >> /etc/hosts
fi
if [ -n "${RSYSLOG_SERVER:-}" ] && ! grep -q "$RSYSLOG_SERVER" /etc/rsyslog.conf; then
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

# configuration update
if [ ! -e /app/event_processor.conf ] || grep '%\(.*\)s$' /app/event_processor.conf; then
  export ETH0_IP="$(/sbin/ifconfig eth0 | grep 'inet addr' | awk '{ print $2 }' | cut -f2 -d ':')"
  export SUB_SRC="$(grep -v "$ETH0_IP" <<< python /app/resin --get-devices | paste -d, -s)"
  # application configuration (no tee for secrets)
  cat /app/config/event_processor.conf | python /app/config_interpol > /app/event_processor.conf
  unset ETH0_IP
  unset SUB_SRC
fi

# non-root users
id -u "${APP_USER}" || useradd -r -g "${APP_GROUP}" "${APP_USER}"
chown -R "${APP_USER}:${APP_GROUP}" /app/
# non-volatile storage
chown "${APP_USER}:${APP_GROUP}" /data/
chown "${APP_USER}:${APP_GROUP}" /data/*
# so app user can make the noise
adduser "${APP_USER}" audio

# I'm the supervisor
cat /app/config/supervisord.conf | python /app/config_interpol | tee /etc/supervisor/conf.d/supervisord.conf
/usr/bin/supervisord