#!/usr/bin/env bash
set -e
set -o pipefail

python -m venv --system-site-packages /opt/app/
. /opt/app/bin/activate
# work around wheel stupidity
pip3 install wheel
pip3 install -r "/opt/app/config/requirements.txt"
deactivate
