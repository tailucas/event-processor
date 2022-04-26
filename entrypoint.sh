#!/bin/bash
set -eu
set -o pipefail

# host heartbeat
if [ -n "${HC_PING_URL:-}" ]; then
  echo "Installing heartbeat to ${HC_PING_URL}"
  cp /opt/app/config/healthchecks_heartbeat /etc/cron.d/healthchecks_heartbeat
fi

# ngrok
/opt/app/ngrok authtoken --config /opt/app/ngrok.yml $(/opt/app/bin/python /opt/app/pylib/cred_tool <<< '{"s": {"opitem": "ngrok", "opfield": ".password"}}')
FRONTEND_USER="$(/opt/app/bin/python /opt/app/pylib/cred_tool <<< '{"s": {"opitem": "Frontend", "opfield": ".username"}}')"
FRONTEND_PASSWORD="$(/opt/app/bin/python /opt/app/pylib/cred_tool <<< '{"s": {"opitem": "Frontend", "opfield": ".password"}}')"
cat /opt/app/config/ngrok_frontend.yml \
  | sed 's@APP_FLASK_HTTP_PORT@'"$APP_FLASK_HTTP_PORT"'@' \
  | sed 's@FRONTEND_USER@'"$FRONTEND_USER"'@' \
  | sed 's@FRONTEND_PASSWORD@'"$FRONTEND_PASSWORD"'@' \
  | sed 's@NGROK_CLIENT_API_PORT@'"$NGROK_CLIENT_API_PORT"'@' \
  | sed 's@NGROK_TUNNEL_NAME@'"$NGROK_TUNNEL_NAME"'@' \
  > /opt/app/ngrok_frontend.yml
unset FRONTEND_USER
unset FRONTEND_PASSWORD
# check and opportunistically upgrade configuration
/opt/app/ngrok config check --config /opt/app/ngrok.yml || ./opt/app/ngrok config upgrade --config /opt/app/ngrok.yml
/opt/app/ngrok config check --config /opt/app/ngrok_frontend.yml || ./opt/app/ngrok config upgrade --config /opt/app/ngrok_frontend.yml

# Create /etc/docker.env
if [ ! -e /etc/docker.env ]; then
  # https://github.com/balena-io-library/base-images/blob/b4fc5c21dd1e28c21e5661f65809c90ed7605fe6/examples/INITSYSTEM/systemd/systemd/entry.sh
  for var in $(compgen -e); do
    printf '%q=%q\n' "$var" "${!var}"
  done > /etc/docker.env
fi

set -x

# Run user
export APP_USER="${APP_USER:-app}"
export APP_GROUP="${APP_GROUP:-app}"

# groups
groupadd -f -r "${APP_GROUP}"
# non-root users
id -u "${APP_USER}" || useradd -r -g "${APP_GROUP}" "${APP_USER}"

# sudoers for systemctl (ngrok)
mkdir -p /etc/sudoers.d
cp /opt/app/config/sudoers /etc/sudoers.d/app
chmod 0440 /etc/sudoers.d/app

TZ_CACHE=/data/localtime
# a valid symlink
if [ -h "$TZ_CACHE" ] && [ -e "$TZ_CACHE" ]; then
  cp -a "$TZ_CACHE" /etc/localtime
fi
# set the timezone
(tzupdate && cp -a /etc/localtime "$TZ_CACHE") || [ -e "$TZ_CACHE" ]

# rsyslog
if [ -n "${RSYSLOG_SERVER:-}" ]; then
  cat << EOF > /etc/rsyslog.d/custom.conf
\$PreserveFQDN on
\$ActionQueueFileName queue
\$ActionQueueMaxDiskSpace 1g
\$ActionQueueSaveOnShutdown on
\$ActionQueueType LinkedList
\$ActionResumeRetryCount -1
*.* @${RSYSLOG_SERVER}${RSYSLOG_TEMPLATE:-}
EOF
fi
# logentries
if [ -n "${RSYSLOG_LOGENTRIES:-}" ]; then
  set +x
  RSYSLOG_LOGENTRIES_TOKEN="$(/opt/app/bin/python /opt/app/pylib/cred_tool <<< '{"s": {"opitem": "Logentries", "opfield": "${APP_NAME}.token"}}')"
  if [ -n "${RSYSLOG_LOGENTRIES_TOKEN:-}" ] && ! grep -q "$RSYSLOG_LOGENTRIES_TOKEN" /etc/rsyslog.d/logentries.conf; then
    echo "\$template LogentriesFormat,\"${RSYSLOG_LOGENTRIES_TOKEN} %HOSTNAME% %syslogtag%%msg%\n\"" >> /etc/rsyslog.d/logentries.conf
    RSYSLOG_TEMPLATE=";LogentriesFormat"
  fi
  unset RSYSLOG_LOGENTRIES_TOKEN
  echo "*.*          @@${RSYSLOG_LOGENTRIES_SERVER}${RSYSLOG_TEMPLATE:-}" >> /etc/rsyslog.d/logentries.conf
  set -x
fi

# application configuration (no tee for secrets)
cat /opt/app/config/app.conf | /opt/app/pylib/config_interpol > "/opt/app/${APP_NAME}.conf"
cat /opt/app/config/backup_db | sed "s~__APP_USER__~${APP_USER}~g" > /etc/cron.d/backup_db

# Load app environment, overriding HOME and USER
# https://www.freedesktop.org/software/systemd/man/systemd.exec.html
cat /etc/docker.env | egrep -v "^HOME|^USER" > /opt/app/environment.env
echo "HOME=/data/" >> /opt/app/environment.env
echo "USER=${APP_USER}" >> /opt/app/environment.env

# Refresh local SQLite
if [ ! -f "${TABLESPACE_PATH}" ]; then
  /opt/app/backup_db.sh
  chown "${APP_USER}:${APP_GROUP}" "${TABLESPACE_PATH}"
fi

# so app user can make the noise
adduser "${APP_USER}" audio
chown "${APP_USER}:${APP_GROUP}" /opt/app/*
# non-volatile storage
chown -R "${APP_USER}:${APP_GROUP}" /data/
# logging
chown "${APP_USER}" /var/log/
# home
mkdir -p "/home/${APP_USER}/.aws/"
chown -R "${APP_USER}:${APP_GROUP}" "/home/${APP_USER}/"
# AWS configuration (no tee for secrets)
cat /opt/app/config/aws-config | /opt/app/pylib/config_interpol > "/home/${APP_USER}/.aws/config"
# patch botoflow to work-around
# AttributeError: 'Endpoint' object has no attribute 'timeout'
PY_BASE_WORKER="$(find /opt/app/ -name base_worker.py)"
patch -f -u "$PY_BASE_WORKER" -i /opt/app/config/base_worker.patch || true

# Bash history
echo "export HISTFILE=/data/.bash_history" >> /etc/bash.bashrc

# systemd configuration
for systemdsvc in app ngrok; do
  if [ ! -e "/etc/systemd/system/${systemdsvc}.service" ]; then
    cat "/opt/app/config/systemd.${systemdsvc}.service" | /opt/app/pylib/config_interpol | tee "/etc/systemd/system/${systemdsvc}.service"
    chmod 664 "/etc/systemd/system/${systemdsvc}.service"
  fi
done
systemctl enable app
# disable these unless leader
systemctl disable ngrok

# replace this entrypoint with systemd init scope
exec env DBUS_SYSTEM_BUS_ADDRESS=unix:path=/run/dbus/system_bus_socket SYSTEMD_LOG_LEVEL=info /lib/systemd/systemd quiet systemd.show_status=0
