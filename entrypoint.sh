#!/bin/bash
set -eu
set -o pipefail

# Resin API key
export RESIN_API_KEY="${RESIN_API_KEY:-$API_KEY_RESIN}"
# root user access, prefer key
mkdir -p /root/.ssh/
if [ -n "$SSH_AUTHORIZED_KEY" ]; then
  echo "$SSH_AUTHORIZED_KEY" > /root/.ssh/authorized_keys
  chmod 600 /root/.ssh/authorized_keys
elif [ -n "$ROOT_PASSWORD" ]; then
  echo "root:${ROOT_PASSWORD}" | chpasswd
  sed -i 's/PermitRootLogin without-password/PermitRootLogin yes/' /etc/ssh/sshd_config
  # SSH login fix. Otherwise user is kicked off after login
  sed 's@session\s*required\s*pam_loginuid.so@session optional pam_loginuid.so@g' -i /etc/pam.d/sshd
fi
# reload sshd
service ssh reload

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

# aws code commit
if [ -n "${AWS_REPO_SSH_KEY_ID:-}" ]; then
  # ssh
  echo "$AWS_REPO_SSH_PRIVATE_KEY" | base64 -d > /root/.ssh/codecommit_rsa
  chmod 600 /root/.ssh/codecommit_rsa
  cat << EOF >> /root/.ssh/config
StrictHostKeyChecking=no
Host git-codecommit.*.amazonaws.com
  User $AWS_REPO_SSH_KEY_ID
  IdentityFile /root/.ssh/codecommit_rsa
EOF
  chmod 600 /root/.ssh/config
fi

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
fi
# set the timezone
(tzupdate && cp -a /etc/localtime "$TZ_CACHE") || [ -e "$TZ_CACHE" ]

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
# refresh the device name and bail unless cached
export DEVICE_NAME="$(python /app/resin --get-device-name)" || [ -n "${DEVICE_NAME:-}" ]
echo "$DEVICE_NAME" > "$HN_CACHE"
echo "$DEVICE_NAME" > /etc/hostname
hostnamectl set-hostname "$DEVICE_NAME"
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
# bounce rsyslog for the new data
service rsyslog restart

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
# get the latest sources
# FIXME
python /app/resin --get-devices
export SUB_SRC="$(python /app/resin --get-devices | grep -v "$ETH0_IP" | paste -d, -s)"
# test cache
export SUB_CACHE=/data/sub_src
if [[ -z "${SUB_SRC// }" ]]; then
  export SUB_SRC="$(cat "$SUB_CACHE")"
else
  echo "$SUB_SRC" > "$SUB_CACHE"
fi
unset SUB_CACHE
# bail unless cached
[[ -n "${SUB_SRC// }" ]]
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
# pidfile access
chown "${APP_USER}" /run/
if [ -e "/var/run/${APP_NAME}.pid" ]; then
  chown "${APP_USER}" "/var/run/${APP_NAME}.pid"
fi

# Bash history
echo "export HISTFILE=/data/.bash_history" >> /etc/bash.bashrc

# systemd configuration
for systemdsvc in app ngrok; do
  if [ ! -e "/etc/systemd/system/${systemdsvc}.service" ]; then
    cat "/app/config/systemd.${systemdsvc}.service" | python /app/config_interpol | tee "/etc/systemd/system/${systemdsvc}.service"
    chmod 664 "/etc/systemd/system/${systemdsvc}.service"
    systemctl daemon-reload
    systemctl enable "${systemdsvc}"
  fi
done
for systemdsvc in app ngrok; do
  systemctl start "${systemdsvc}"&
done