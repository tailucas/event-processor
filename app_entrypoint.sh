#!/usr/bin/env bash
set -eu
set -o pipefail

# Refresh local SQLite
/opt/app/backup_db.sh time
