#!/usr/bin/env bash
set -eux
set -o pipefail

pyvenv /opt/app/
# work around venv stupidity
set +u
. /opt/app/bin/activate
set -u
# work around wheel stupidity
pip3 install wheel
pip3 install -r "/opt/app/config/requirements.txt"
deactivate
