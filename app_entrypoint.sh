#!/bin/bash
set -eu
set -o pipefail

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
cat << EOF >> /opt/app/supervisord.conf
[program:ngrok]
command=/opt/app/ngrok start --config /opt/app/ngrok.yml --config /opt/app/ngrok_frontend.yml frontend
autorestart=false
startretries=0
stderr_logfile=/dev/stderr
EOF

# Refresh local SQLite
/opt/app/backup_db.sh