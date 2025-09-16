#!/usr/bin/env bash
set -eu
set -o pipefail

me="$(basename "$0")"
cd "$(dirname "$0")"

log () {
  echo "${me} $1"
}

# source environment
. <(sed 's/^/export /' /opt/app/cron.env)
# generate AWS configuration
log "Generating AWS config (${AWS_CONFIG_FILE:-default}) and credentials (${AWS_SHARED_CREDENTIALS_FILE:-default})..."
uv run aws_configure

BACKUP_FILENAME_SUFFIX=""
if [ -n "${1:-}" ]; then
  if [ "$1" == "date" ]; then
    BACKUP_FILENAME_SUFFIX="_$(date +%y%m%d)"
  elif [ "$1" == "time" ]; then
    BACKUP_FILENAME_SUFFIX="_$(date +%y%m%d%H%M%S)"
  else
    BACKUP_FILENAME_SUFFIX="_$1"
  fi
fi
BACKUP_FILENAME="${APP_NAME}${BACKUP_FILENAME_SUFFIX}.db"
S3_RESTORE_PATH="s3://${BACKUP_S3_BUCKET}/${APP_NAME}.db"
S3_BACKUP_PATH="s3://${BACKUP_S3_BUCKET}/${BACKUP_FILENAME}"
TABLESPACE="${TABLESPACE_PATH}/${APP_NAME}.db"
if [ -s "${TABLESPACE}" ]; then
  log "creating backup of tablespace ${TABLESPACE}..."
  sqlite3 "${TABLESPACE}" ".backup /tmp/${APP_NAME}.db"
  log "uploading backup to ${S3_BACKUP_PATH}..."
  uv run aws s3 cp "/tmp/${APP_NAME}.db" "${S3_BACKUP_PATH}" --only-show-errors
else
  log "restoring ${S3_RESTORE_PATH} to ${TABLESPACE}..."
  uv run aws s3 cp "${S3_RESTORE_PATH}" "${TABLESPACE}" --only-show-errors
fi

DB_HEARTBEAT=$(sqlite3 "${TABLESPACE}" 'select dt from heartbeat') || true
if [ -n "${DB_HEARTBEAT:-}" ]; then
  log "database heartbeat is ${DB_HEARTBEAT} UTC."
fi
