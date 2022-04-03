#!/usr/bin/env bash
set -e
set -o pipefail

. <(cat /opt/app/environment.env | sed 's/^/export /')
# pip-installed aws cli
. /opt/app/bin/activate
AKID="{\"s\": {\"opitem\": \"AWS\", \"opfield\": \"${AWS_DEFAULT_REGION}.akid\"}}"
export AWS_ACCESS_KEY_ID="$(/opt/app/bin/python /opt/app/pylib/cred_tool <<< "${AKID}")"
SAK="{\"s\": {\"opitem\": \"AWS\", \"opfield\": \"${AWS_DEFAULT_REGION}.sak\"}}"
export AWS_SECRET_ACCESS_KEY="$(/opt/app/bin/python /opt/app/pylib/cred_tool <<< "${SAK}")"
if [ -f "${TABLESPACE_PATH}" ]; then
  aws s3 cp "${TABLESPACE_PATH}" "s3://tailucas-automation/${APP_NAME}.db" --only-show-errors
else
  aws s3 cp "s3://tailucas-automation/${APP_NAME}.db" "${TABLESPACE_PATH}" --only-show-errors
fi
unset AWS_ACCESS_KEY_ID
unset AWS_SECRET_ACCESS_KEY
deactivate
