#!/bin/bash
set -eu
set -o pipefail

# Resin API key (prefer override from application/device environment)
export RESIN_API_KEY="${API_KEY_RESIN:-$RESIN_API_KEY}"
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
# https://bugs.launchpad.net/ubuntu/+source/openssh/+bug/45234
mkdir -p /run/sshd
# reload sshd
service ssh reload

# ngrok
if [ -n "${NGROK_AUTH_TOKEN:-}" ]; then
  ./opt/app/ngrok authtoken  --config /opt/app/ngrok.yml "${NGROK_AUTH_TOKEN}"
fi
cat /opt/app/config/ngrok_frontend.yml \
  | sed 's@APP_FLASK_HTTP_PORT@'"$APP_FLASK_HTTP_PORT"'@' \
  | sed 's@FRONTEND_USER@'"$FRONTEND_USER"'@' \
  | sed 's@FRONTEND_PASSWORD@'"$FRONTEND_PASSWORD"'@' \
  | sed 's@NGROK_CLIENT_API_PORT@'"$NGROK_CLIENT_API_PORT"'@' \
  | sed 's@NGROK_TUNNEL_NAME@'"$NGROK_TUNNEL_NAME"'@' \
  > /opt/app/ngrok_frontend.yml

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

TZ_CACHE=/data/localtime
# a valid symlink
if [ -h "$TZ_CACHE" ] && [ -e "$TZ_CACHE" ]; then
  cp -a "$TZ_CACHE" /etc/localtime
fi
# set the timezone
(tzupdate && cp -a /etc/localtime "$TZ_CACHE") || [ -e "$TZ_CACHE" ]

# reset hostname (in a way that works)
# https://forums.resin.io/t/read-only-file-system-when-calling-setstatichostname-via-dbus/1578/10
curl -X PATCH --header "Content-Type:application/json" \
  --data '{"network": {"hostname": "'${RESIN_DEVICE_NAME_AT_INIT}'"}}' \
  "$RESIN_SUPERVISOR_ADDRESS/v1/device/host-config?apikey=$RESIN_SUPERVISOR_API_KEY"
echo "$RESIN_DEVICE_NAME_AT_INIT" > /etc/hostname
echo "127.0.1.1 ${RESIN_DEVICE_NAME_AT_INIT}" >> /etc/hosts

cp /opt/app/config/rsyslog.conf /etc/rsyslog.conf
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
  cat /var/awslogs/etc/aws.conf | /opt/app/config_interpol /opt/app/config/aws.conf > /var/awslogs/etc/aws.conf.new
  mv /var/awslogs/etc/aws.conf /var/awslogs/etc/aws.conf.backup
  mv /var/awslogs/etc/aws.conf.new /var/awslogs/etc/aws.conf
fi

# configuration update
for iface in wlan0 eth0; do
  export ETH0_IP="$(/sbin/ifconfig ${iface} | grep 'inet' | awk '{ print $2 }' | cut -f2 -d ':')"
  if [ -n "$ETH0_IP" ]; then
    break
  fi
done
# application configuration (no tee for secrets)
cat /opt/app/config/app.conf | /opt/app/config_interpol > "/opt/app/${APP_NAME}.conf"
unset ETH0_IP

# remove unnecessary kernel drivers
rmmod w1_gpio||true
if [ -n "${NO_WLAN:-}" ]; then
  rmmod brcmfmac brcmutil||true
fi

# Load app environment, overriding HOME and USER
# https://www.freedesktop.org/software/systemd/man/systemd.exec.html
cat /etc/docker.env | egrep -v "^HOME|^USER" > /opt/app/environment.env
echo "HOME=/data/" >> /opt/app/environment.env
echo "USER=${APP_USER}" >> /opt/app/environment.env

# so app user can make the noise
adduser "${APP_USER}" audio

# give the app user access to all its things
chown -R "${APP_USER}:${APP_GROUP}" /opt/app/
# non-volatile storage
chown -R "${APP_USER}:${APP_GROUP}" /data/
# logging
chown "${APP_USER}" /var/log/

# Bash history
echo "export HISTFILE=/data/.bash_history" >> /etc/bash.bashrc

# DEBUG
ps auxf

# To interact with systemd
export DBUS_SYSTEM_BUS_ADDRESS=unix:path=/run/dbus/system_bus_socket

# systemd configuration
for systemdsvc in app ngrok; do
  if [ ! -e "/etc/systemd/system/${systemdsvc}.service" ]; then
    cat "/opt/app/config/systemd.${systemdsvc}.service" | /opt/app/config_interpol | tee "/etc/systemd/system/${systemdsvc}.service"
    chmod 664 "/etc/systemd/system/${systemdsvc}.service"
    #systemctl daemon-reload
    systemctl enable "${systemdsvc}"
  fi
done

sleep infinity &
# replace the entrypoint with systemd init scope
exec env /lib/systemd/systemd quiet systemd.show_status=0