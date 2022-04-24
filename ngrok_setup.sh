#!/bin/bash
set -eux
set -o pipefail

NGROK_URL=$(curl -s 'https://ngrok.com/download' | hxnormalize -x 2>/dev/null | grep '#linux-dl-link' | egrep -o 'https://.*linux-amd64.tgz' | tail -1)
TMP_DIR=$(mktemp -d -t ngrok-XXX)
wget -nv -P "${TMP_DIR}" "${NGROK_URL}"
tar -xvzf ${TMP_DIR}/*.tgz --directory /opt/app/
