#!/bin/bash
set -eux

# remove unnecessary kernel drivers
KMOD=$(lsmod | grep w1_gpio)
if [ -n "${KMOD:-}" ]; then
  # unload this to prevent these kernel messages,
  # apparently when GPIO is not connected.
  # w1_master_driver w1_bus_master1: Family 0 for 00.ef9b00000000.48 is not registered.
  rmmod w1_gpio
fi
lsmod

if [ -n "${RSYSLOG_SERVER:-}" ]; then
  echo "*.*          @${RSYSLOG_SERVER}" >> /etc/rsyslog.conf
  tail /etc/rsyslog.conf
  service rsyslog restart
fi


sudo -u app python /app/hello.py
