#!/bin/bash
set -eux
set -o pipefail

NGROK_URL=$(curl -s 'https://ngrok.com/download' | hxnormalize -x 2>/dev/null | hxselect '#dl-linux-arm' 2>/dev/null | egrep -o 'https.*\.zip' | tail -1)
TMP_DIR=$(mktemp -d -t ngrok-XXX)
wget -nv -P "${TMP_DIR}" "${NGROK_URL}"
unzip "${TMP_DIR}/*.zip" -d /opt/app/
