#!/bin/bash
set -eu

wget https://s3.amazonaws.com/aws-cloudwatch/downloads/latest/awslogs-agent-setup.py -O /opt/app/awslogs-agent-setup.py
python /opt/app/awslogs-agent-setup.py -n -r "eu-west-1" -c /opt/app/config/awslogs-config
# remove the service and nanny (supervisor does this)
update-rc.d awslogs remove
rm -f /etc/cron.d/awslogs
