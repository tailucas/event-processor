# event_processor

Main processing coordinator for Home automation/security projects. Built originally on [ZMQ](https://zeromq.org/) for message passing and all remote-control, I've been adding support for [AWS SWF](https://aws.amazon.com/swf/) using [botoflow](https://github.com/boto/botoflow) for Pythonic abstractions for these remote actions.

A basic dashboard is exposed using a [Flask](https://flask.palletsprojects.com/en/1.1.x/) server, and exposed externally through an [ngrok](https://ngrok.com/) tunnel. Basic [Telegram bot](https://core.telegram.org/bots) integration is used for external control. Exception handling is done using [Sentry](https://sentry.io/).

## Notes for Balena Cloud

This project is structured as is for use with [Balena Cloud](https://www.balena.io/cloud/) and requires the *service variables* listed below to be set in order for the application to start properly. It is best to configure these at the level of the Balena application as opposed to the device because all device variables are local to each device.

Since this project uses additional remotes for [Git submodule](https://git-scm.com/book/en/v2/Git-Tools-Submodules) usage, a *push* to the Balena remote is insufficient to include all artifacts for the build. For this, you need to use the *balena push* command supplied by the [balena CLI](https://github.com/balena-io/balena-cli). Be sure to use the correct Balena application name when using *balena push* because the tool will not perform validation of the local context against the build deployed to the device.

```text
API_KEY_RESIN
APP_FLASK_DEBUG
APP_FLASK_HTTP_PORT
APP_FLASK_SECRET_KEY
APP_NAME
APP_ZMQ_PUBSUB_PORT
APP_ZMQ_PUSHPULL_PORT
AWS_ACCESS_KEY_ID
AWS_CONFIG_FILE
AWS_CPT_ACCESS_KEY_ID
AWS_CPT_SECRET_ACCESS_KEY
AWS_DEFAULT_REGION
AWS_DUB_ACCESS_KEY_ID
AWS_DUB_SECRET_ACCESS_KEY
AWS_SECRET_ACCESS_KEY
AWS_SHARED_CREDENTIALS_FILE
CONFIG_TABLE_GENERAL
CONFIG_TABLE_INPUT
CONFIG_TABLE_OUTPUT
EVENT_SOURCE_CSV
FRONTEND_PASSWORD
FRONTEND_USER
NGROK_AUTH_TOKEN
NGROK_CLIENT_API_PORT
NGROK_TUNNEL_NAME
NO_WLAN
OUTPUT_TYPE_BLUETOOTH
OUTPUT_TYPE_SNAPSHOT
OUTPUT_TYPE_SWITCH
OUTPUT_TYPE_TTS
REMOVE_KERNEL_MODULES
RSYSLOG_LOGENTRIES_SERVER
RSYSLOG_LOGENTRIES_TOKEN
RSYSLOG_SERVER
SENTRY_DSN
SQS_QUEUE
SSH_AUTHORIZED_KEY
SWF_APP_BLUETOOTH
SWF_APP_IOBOARD
SWF_APP_SNAPSHOT
SWF_APP_TTS
TABLESPACE_PATH
TELEGRAM_BOT_API_TOKEN
TELEGRAM_CHAT_ROOM
TELEGRAM_USERS_CSV
```
