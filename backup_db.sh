#!/usr/bin/env bash
set -e
set -o pipefail

. <(cat /etc/environment | sed 's/^/export /')
# pip-installed aws cli
. /opt/app/bin/activate

BACKUP_FILENAME_SUFFIX=""
if [ -n "${1:-}" ]; then
  BACKUP_FILENAME_SUFFIX="_${1}"
fi
BACKUP_FILENAME="${APP_NAME}${BACKUP_FILENAME_SUFFIX}.db"

AKID="{\"s\": {\"opitem\": \"AWS\", \"opfield\": \"${AWS_DEFAULT_REGION}.akid\"}}"
export AWS_ACCESS_KEY_ID="$(/opt/app/bin/python /opt/app/pylib/cred_tool <<< "${AKID}")"
SAK="{\"s\": {\"opitem\": \"AWS\", \"opfield\": \"${AWS_DEFAULT_REGION}.sak\"}}"
export AWS_SECRET_ACCESS_KEY="$(/opt/app/bin/python /opt/app/pylib/cred_tool <<< "${SAK}")"
if [ -f "${TABLESPACE_PATH}" ]; then
  # only if currently the leader and leader election is enabled
  if [ -f "/data/is_leader" ] || [ "${LEADER_ELECTION_ENABLED:-false}" == "false" ]; then
    # create backup process
    sqlite3 "${TABLESPACE_PATH}" ".backup /tmp/${APP_NAME}.db"
    aws s3 cp "/tmp/${APP_NAME}.db" "s3://tailucas-automation/${BACKUP_FILENAME}" --only-show-errors
  fi
else
  # only if the tablespace does not exist
  aws s3 cp "s3://tailucas-automation/${APP_NAME}.db" "${TABLESPACE_PATH}" --only-show-errors
fi
unset AWS_ACCESS_KEY_ID
unset AWS_SECRET_ACCESS_KEY
deactivate
