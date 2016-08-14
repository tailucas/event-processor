#!/bin/bash
set -eu

# Resin API key
export RESIN_API_KEY="${RESIN_API_KEY:-$API_KEY_RESIN}"
# root user access, prefer key
if [ -n "$SSH_AUTHORIZED_KEY" ]; then
  echo "$SSH_AUTHORIZED_KEY" > /root/.ssh/authorized_keys
  chmod 600 /root/.ssh/authorized_keys
elif [ -n "$ROOT_PASSWORD" ]; then
  echo "root:${ROOT_PASSWORD}" | chpasswd
  sed -i 's/PermitRootLogin without-password/PermitRootLogin yes/' /etc/ssh/sshd_config
  # SSH login fix. Otherwise user is kicked off after login
  sed 's@session\s*required\s*pam_loginuid.so@session optional pam_loginuid.so@g' -i /etc/pam.d/sshd
fi

# ngrok
if [ -n "${NGROK_AUTH_TOKEN:-}" ]; then
  ./app/ngrok authtoken  --config /app/ngrok.yml "${NGROK_AUTH_TOKEN}"
fi
cat /app/config/ngrok_frontend.yml \
  | sed 's@APP_FLASK_HTTP_PORT@'"$APP_FLASK_HTTP_PORT"'@' \
  | sed 's@FRONTEND_USER@'"$FRONTEND_USER"'@' \
  | sed 's@FRONTEND_PASSWORD@'"$FRONTEND_PASSWORD"'@' \
  | sed 's@NGROK_CLIENT_API_PORT@'"$NGROK_CLIENT_API_PORT"'@' \
  | sed 's@NGROK_TUNNEL_NAME@'"$NGROK_TUNNEL_NAME"'@' \
  > /app/ngrok_frontend.yml


set -x


# Run user
export APP_USER="${APP_USER:-app}"
export APP_GROUP="${APP_GROUP:-app}"

# groups
groupadd -f -r "${APP_GROUP}"
# non-root users
id -u "${APP_USER}" || useradd -r -g "${APP_GROUP}" "${APP_USER}"

TZ_CACHE=/data/localtime
# a valid symlink
if [ -h "$TZ_CACHE" ] && [ -e "$TZ_CACHE" ]; then
  cp -a "$TZ_CACHE" /etc/localtime
else
  # set the timezone
  tzupdate
  cp -a /etc/localtime "$TZ_CACHE"
fi

# invoke resin tool to write resin-sdk settings file
python /app/resin

# remote system logging
HN_CACHE=/data/hostname
if [ -e "$HN_CACHE" ]; then
  DEVICE_NAME="$(cat "$HN_CACHE")"
  # reject if there is a space
  space_pattern=" |'"
  if [[ $DEVICE_NAME =~ $space_pattern ]]; then
    unset DEVICE_NAME
  else
    export DEVICE_NAME
  fi
fi
if [ -z "${DEVICE_NAME:-}" ]; then
  export DEVICE_NAME="$(python /app/resin --get-device-name)"
  echo "$DEVICE_NAME" > "$HN_CACHE"
fi
echo "$DEVICE_NAME" > /etc/hostname
# apply the new hostname
/etc/init.d/hostname.sh start
# update hosts
echo "127.0.1.1 ${DEVICE_NAME}" >> /etc/hosts

cp /app/config/rsyslog.conf /etc/rsyslog.conf
if [ -n "${RSYSLOG_SERVER:-}" ]; then
  set +x
  if [ -n "${RSYSLOG_TOKEN:-}" ] && ! grep -q "$RSYSLOG_TOKEN" /etc/rsyslog.d/custom.conf; then
    echo "\$template LogentriesFormat,\"${RSYSLOG_TOKEN} %HOSTNAME% %syslogtag%%msg%\n\"" >> /etc/rsyslog.d/custom.conf
    RSYSLOG_TEMPLATE=";LogentriesFormat"
  fi
  echo "*.*          @@${RSYSLOG_SERVER}${RSYSLOG_TEMPLATE:-}" >> /etc/rsyslog.d/custom.conf
  set -x
fi

# log archival (no tee for secrets)
if [ -d /var/awslogs/etc/ ]; then
  cat /var/awslogs/etc/aws.conf | python /app/config_interpol /app/config/aws.conf > /var/awslogs/etc/aws.conf.new
  mv /var/awslogs/etc/aws.conf /var/awslogs/etc/aws.conf.backup
  mv /var/awslogs/etc/aws.conf.new /var/awslogs/etc/aws.conf
fi

# configuration update
for iface in eth0 wlan0; do
  export ETH0_IP="$(/sbin/ifconfig ${iface} | grep 'inet addr' | awk '{ print $2 }' | cut -f2 -d ':')"
  if [ -n "$ETH0_IP" ]; then
    break
  fi
done
SUB_CACHE=/data/sub_src
if [ -e "$SUB_CACHE" ]; then
  export SUB_SRC="$(cat "$SUB_CACHE")"
else
  export SUB_SRC="$(python /app/resin --get-devices | grep -v "$ETH0_IP" | paste -d, -s)"
  echo "$SUB_SRC" > "$SUB_CACHE"
fi
# application configuration (no tee for secrets)
cat /app/config/app.conf | python /app/config_interpol > "/app/${APP_NAME}.conf"
unset ETH0_IP
unset SUB_SRC

# remove unnecessary kernel drivers
rmmod w1_gpio||true

# so app user can make the noise
adduser "${APP_USER}" audio

# give the app user access to all its things
chown -R "${APP_USER}:${APP_GROUP}" /app/
# non-volatile storage
chown -R "${APP_USER}:${APP_GROUP}" /data/
# logging
chown "${APP_USER}" /var/log/
# pidfile
chown "${APP_USER}" /var/run/

# Used by resin-sdk Settings
export USER="${APP_USER}"
export HOME=/data/

# I'm the supervisor
cat /app/config/supervisord.conf | python /app/config_interpol | tee /etc/supervisor/conf.d/supervisord.conf

trap 'kill -TERM $PID; wait $PID; sleep 5' TERM INT HUP
/usr/bin/supervisord -c /etc/supervisor/conf.d/supervisord.conf &
PID=$!
wait $PID
wait $PID
sleep 5
EXIT_STATUS=$?