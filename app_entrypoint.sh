#!/usr/bin/env bash
set -eu
set -o pipefail

# The Java event processor posts device info to this application's config API on
# heartbeats, over a pooled HTTP/1.1 connection. The server closes idle
# keep-alive connections without sending a response, and never advertises that
# idle timeout, so the client must retire pooled sockets first or a post can fail
# with (the JDK usually recovers by itself; a logged WARN means it did not):
#   java.io.IOException: HTTP/1.1 header parser received no bytes
# Keep this well below the server's keep-alive timeout
# (API_KEEP_ALIVE_SECONDS, 60s by default, in app/__main__.py).
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djdk.httpclient.keepalive.timeout=${JAVA_CLIENT_KEEPALIVE_SECONDS:-2}"

# Refresh local SQLite
/opt/app/backup_db.sh time
