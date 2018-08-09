#!/usr/bin/env bash
set -eux
set -o pipefail

pyvenv /opt/app/
. /opt/app/bin/activate
# work around wheel stupidity
pip3 install wheel
pip3 install -r "/opt/app/config/requirements.txt"
deactivate
