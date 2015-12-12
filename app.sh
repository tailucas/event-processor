#!/bin/bash
set -eu
set -o pipefail
python "$@" 2>&1 | logger
