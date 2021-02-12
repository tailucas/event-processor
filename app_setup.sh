#!/usr/bin/env bash
set -e
set -o pipefail

# system updates

# Rust for cryptography wheel
curl https://sh.rustup.rs -sSf | sh -s -- -y
# Add rustc to PATH
source $HOME/.cargo/env

# work around pip stupidity
python -m pip install --upgrade pip
# work around setuptools stupidity
python -m pip install --upgrade setuptools
# work around wheel stupidity
python -m pip install --upgrade wheel

# virtual-env updates

python -m venv --system-site-packages /opt/app/
. /opt/app/bin/activate
# work around timeouts to www.piwheels.org
export PIP_DEFAULT_TIMEOUT=60

# work around pip stupidity
python -m pip install --upgrade pip
# work around setuptools stupidity
python -m pip install --upgrade setuptools
# work around wheel stupidity
python -m pip install --upgrade wheel

python -m pip install -r "/opt/app/requirements.txt"

deactivate
