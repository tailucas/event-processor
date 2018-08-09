#!/usr/bin/env bash
set -eux
set -o pipefail

# User application
pushd /opt/
if [ ! -d app ]; then
  pyvenv app
fi
. app/bin/activate
# work around wheel stupidity
pip3 install wheel
pip3 install -r "/opt/app/config/requirements.txt"
deactivate
popd
