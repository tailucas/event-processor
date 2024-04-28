#!/usr/bin/env python
import logging.handlers

import asyncio
import builtins
import boto3
import os
import re
import requests
import schedule
import simplejson as json
import threading
import time
import uvicorn
import zmq
import zmq.asyncio

from collections import OrderedDict, deque
from concurrent import futures
from botocore.exceptions import EndpointConnectionError as bcece
from datetime import datetime, timedelta
from dateutil import tz
from flask_sqlalchemy import SQLAlchemy
from io import BytesIO
from pathlib import Path
from pylru import lrucache
from pytz import timezone
from requests.exceptions import ConnectionError
from schedule import ScheduleValueError
from sentry_sdk import capture_exception
from sentry_sdk.integrations.flask import FlaskIntegration
from sentry_sdk.integrations.logging import ignore_logger
from simplejson.scanner import JSONDecodeError
from telegram import ForceReply, Update, InputMediaPhoto, MessageEntity
from telegram.constants import MediaGroupLimit
from telegram.ext import Application as TelegramApp, \
    Updater as TelegramUpdater, \
    CommandHandler as TelegramCommandHandler, \
    ContextTypes as TelegramContextTypes, \
    MessageHandler as TelegramMessageHandler, \
    filters
from telegram.error import TelegramError, NetworkError, RetryAfter, TimedOut
from threading import Thread
from urllib.parse import urlparse

from fastapi import FastAPI, Depends, status, HTTPException
from fastapi.responses import RedirectResponse
from fastapi.middleware.wsgi import WSGIMiddleware

from pydantic import BaseModel, Field

from flask import Flask, g, flash, request, render_template, url_for, redirect
from flask.logging import default_handler
from flask_compress import Compress
from werkzeug.serving import make_server

from zmq.asyncio import Poller
from zmq.error import ZMQError, ContextTerminated, Again

import paho.mqtt.client as mqtt
from paho.mqtt.client import MQTT_ERR_SUCCESS, MQTT_ERR_NO_CONN

import os.path

# setup builtins used by pylib init
builtins.SENTRY_EXTRAS = [FlaskIntegration(transaction_style="url")]
builtins.SENTRY_ENVIRONMENT = 'python'
AWS_REGION = os.environ['AWS_DEFAULT_REGION']
influx_creds_section = 'local'
from . import APP_NAME
class CredsConfig:
    sentry_dsn: f'opitem:"Sentry" opfield:{APP_NAME}.dsn' = None # type: ignore
    cronitor_token: f'opitem:"cronitor" opfield:.password' = None # type: ignore
    flask_secret_key: f'opitem:"Frontend" opfield:Flask.secret_key' = None # type: ignore
    flask_basic_auth_username: f'opitem:"Frontend" opfield:.username' = None # type: ignore
    flask_basic_auth_password: f'opitem:"Frontend" opfield:.password' = None # type: ignore
    mdash_api_key: f'opitem:"mdash" opfield:.password' = None # type: ignore
    telegram_bot_api_token: f'opitem:"Telegram" opfield:{APP_NAME}.token' = None # type: ignore
    aws_akid: f'opitem:"AWS" opfield:{AWS_REGION}.akid' = None # type: ignore
    aws_sak: f'opitem:"AWS" opfield:{AWS_REGION}.sak' = None # type: ignore
    influxdb_org: f'opitem:"InfluxDB" opfield:{influx_creds_section}.org' = None # type: ignore
    influxdb_token: f'opitem:"InfluxDB" opfield:{influx_creds_section}.token' = None # type: ignore
    influxdb_url: f'opitem:"InfluxDB" opfield:{influx_creds_section}.url' = None # type: ignore
    grafana_url: f'opitem:"Grafana" opfield:dashboard.URL' = None # type: ignore


# instantiate class
builtins.creds_config = CredsConfig()

from tailucas_pylib import app_config, \
    creds, \
    device_name, \
    device_name_base, \
    log, \
    log_handler

from tailucas_pylib.datetime import is_list, \
    make_timestamp, \
    make_unix_timestamp, \
    ISO_DATE_FORMAT
from tailucas_pylib.aws.metrics import post_count_metric
from tailucas_pylib.process import SignalHandler, exec_cmd_log
from tailucas_pylib.rabbit import MQConnection, ZMQListener
from tailucas_pylib import threads
from tailucas_pylib.threads import thread_nanny, die, bye
from tailucas_pylib.app import AppThread, ZmqRelay
from tailucas_pylib.zmq import zmq_term, Closable, zmq_socket, try_close
from tailucas_pylib.handler import exception_handler

from influxdb_client import InfluxDBClient, Point, WritePrecision
from influxdb_client.client.write_api import ASYNCHRONOUS


# Reduce Sentry noise
ignore_logger('telegram.ext.Updater')
ignore_logger('telegram.ext._updater')
ignore_logger('asyncio')

db_tablespace = app_config.get('sqlite', 'tablespace_path')

from sqlalchemy.ext.asyncio import AsyncEngine, create_async_engine, AsyncSession
from sqlalchemy.orm import declarative_base, sessionmaker, Session

dburl: str = f'sqlite+aiosqlite:///{db_tablespace}'
engine: AsyncEngine = create_async_engine(dburl)
async_session: AsyncSession = sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)
Base = declarative_base()


async def get_db():
    async with async_session() as session:
        yield session


from sqlalchemy import Column, Integer, String, JSON, DateTime, Boolean, Text

from sqlalchemy import update, ForeignKey, UniqueConstraint, Result, delete
from sqlalchemy.future import select
from sqlalchemy.orm import relationship, Mapped, Query
from sqlalchemy import or_

user_tz = timezone(app_config.get('app', 'user_tz'))
flask_app = Flask(APP_NAME)
flask_app.config["SQLALCHEMY_DATABASE_URI"] = f'sqlite:///{db_tablespace}'
flask_app.config["SQLALCHEMY_TRACK_MODIFICATIONS"] = False
db = SQLAlchemy(app=flask_app, model_class=Base)
# set up flask application
flask_app.logger.removeHandler(default_handler)
flask_app.logger.addHandler(log_handler)
flask_app.secret_key = creds.flask_secret_key
flask_app.jinja_env.add_extension('jinja2.ext.loopcontrols')
flask_app.jinja_env.filters.update({
    'is_list': is_list,
})
# enable compression
Compress().init_app(flask_app)
flask_ctx = flask_app.app_context()
flask_ctx.push()

api_app = FastAPI()
api_app.mount("/admin", WSGIMiddleware(flask_app))


OUTPUT_TYPE_BLUETOOTH = 'bluetooth'
OUTPUT_TYPE_SWITCH = 'ioboard'
OUTPUT_TYPE_IMAGE = 'image'
OUTPUT_TYPE_SNAPSHOT = 'snapshot'
OUTPUT_TYPE_TTS = 'tts'

URL_WORKER_APP = 'inproc://app-worker'
URL_WORKER_APP_BRIDGE = 'inproc://app-bridge'
URL_WORKER_HEARTBEAT_NANNY = 'inproc://heartbeat-nanny'
URL_WORKER_EVENT_LOG = 'inproc://event-log'
URL_WORKER_TELEGRAM_BOT = 'inproc://telegram-bot'
URL_WORKER_MQTT_PUBLISH = 'inproc://mqtt-publish'
URL_WORKER_AUTO_SCHEDULER = 'inproc://auto-scheduler'

ngrok_tunnel_url = None
ngrok_tunnel_url_with_bauth = None

startup_complete = False


from typing import List, Optional
from typing_extensions import Annotated


class Device(BaseModel):
    active: Optional[bool] = None
    device_id: Optional[str] = None
    device_key: str
    device_label: Optional[str] = None
    device_params: Optional[str] = None
    device_type: str
    event_detail: Optional[str] = None
    group_name: Optional[str] = None
    image: Optional[bytes] = None
    input_label: Optional[str] = None
    input_location: Optional[str] = None
    is_input: Optional[bool] = None
    is_output: Optional[bool] = None
    last_metered_minute: Optional[float] = None
    last_minute_metered: Optional[int] = None
    last_sample_value: Optional[int] = None
    location: Optional[str] = None
    name: Optional[str] = None
    normal_value: Optional[int] = None
    pulse_discards: Optional[int] = None
    register_reading: Optional[int] = None
    sample_value: Optional[int] = None
    storage_path: Optional[str] = None
    storage_url: Optional[str] = None
    timestamp: Optional[int] = None
    type_: Optional[Annotated[str, Field(alias='type')]] = None
    uptime: Optional[int] = None

    def __str__(self):
        str_rep = ''
        for name, value in vars(self).items():
            if len(str_rep) > 0:
                str_rep += ','
            if not isinstance(value, bytes):
                str_rep += f'{name}={value}'
            else:
                str_rep += f'{name}={len(value)} bytes'
        return str_rep


class EventLog(Base):
    __tablename__ = 'event_log'
    id = Column(Integer, primary_key=True)
    input_device = Column(String(100), index=True)
    output_device = Column(String(100), index=True)
    timestamp = Column(DateTime, index=True)

    def __init__(self, input_device, output_device, timestamp):
        self.input_device = input_device
        self.output_device = output_device
        self.timestamp = timestamp


class InputConfig(Base):
    __tablename__ = 'input_config'
    id = Column(Integer, primary_key = True, autoincrement=True)
    device_key = Column(String(50), unique=True, index=True, nullable=False)
    device_type = Column(String(100), nullable=False)
    device_label = Column(String(100))
    customized = Column(Boolean)
    auto_schedule = Column(Boolean)
    auto_schedule_enable = Column(String(5))
    auto_schedule_disable = Column(String(5))
    device_enabled = Column(Boolean)
    trigger_latch_duration = Column(Integer)
    multi_trigger_rate = Column(Integer)
    multi_trigger_interval = Column(Integer)
    group_name = Column(String(100), index=True)
    info_notify = Column(Boolean)
    links_il = relationship('InputLink', backref='input_config', cascade='all, delete-orphan', lazy='dynamic')
    links_ol = relationship('OutputLink', backref='input_config', cascade='all, delete-orphan', lazy='dynamic')
    links_mc = relationship('MeterConfig', backref='input_config', cascade='all, delete-orphan', lazy='dynamic')

    def __init__(self, device_key, device_type, device_label, customized, auto_schedule, auto_schedule_enable, auto_schedule_disable, device_enabled, trigger_latch_duration, multi_trigger_rate, multi_trigger_interval, group_name, info_notify):
        self.device_key = device_key
        self.device_type = device_type
        self.device_label = device_label
        self.customized = customized
        self.auto_schedule = auto_schedule
        self.auto_schedule_enable = auto_schedule_enable
        self.auto_schedule_disable = auto_schedule_disable
        self.device_enabled = device_enabled
        self.trigger_latch_duration = trigger_latch_duration
        self.multi_trigger_rate = multi_trigger_rate
        self.multi_trigger_interval = multi_trigger_interval
        self.group_name = group_name
        self.info_notify = info_notify

    def as_dict(self):
        return {c.name: getattr(self, c.name) for c in self.__table__.columns}

    def __str__(self):
        if self.device_label:
            return self.device_label
        return self.device_key


class MeterConfig(Base):
    __tablename__ = 'meter_config'
    id = Column(Integer, primary_key = True, autoincrement=True)
    input_device_id = Column(Integer, ForeignKey('input_config.id'), index=True, nullable=False)
    meter_value = Column(Integer, default=0, nullable=False)
    register_value = Column(Integer, default=0, nullable=False)
    meter_reading = Column(String, default='0', nullable=False)
    meter_iot_topic = Column(String(100), nullable=False)
    meter_low_limit = Column(Integer)
    meter_high_limit = Column(Integer)
    meter_reset_value = Column(Integer)
    meter_reset_additive = Column(Boolean)
    meter_reading_unit = Column(String(10))
    meter_reading_unit_factor = Column(Integer)
    meter_reading_unit_precision = Column(Integer)

    def __init__(self, input_device_id, meter_iot_topic, meter_low_limit, meter_high_limit, meter_reset_value, meter_reset_additive, meter_reading_unit, meter_reading_unit_factor, meter_reading_unit_precision):
        self.input_device_id = input_device_id
        self.meter_iot_topic = meter_iot_topic
        self.meter_low_limit = meter_low_limit
        self.meter_high_limit = meter_high_limit
        self.meter_reset_value = meter_reset_value
        self.meter_reset_additive = meter_reset_additive
        self.meter_reading_unit = meter_reading_unit
        self.meter_reading_unit_factor = meter_reading_unit_factor
        self.meter_reading_unit_precision = meter_reading_unit_precision

    def as_dict(self):
        return {c.name: getattr(self, c.name) for c in self.__table__.columns}


class OutputConfig(Base):
    __tablename__  = 'output_config'
    id = Column(Integer, primary_key=True, autoincrement=True)
    device_key = Column(String(50), unique=True, index=True, nullable=False)
    device_type = Column(String(100), nullable=False)
    device_label = Column(String(100))
    device_params = Column(Text)
    links = relationship('OutputLink', backref='output_config', cascade='all, delete-orphan', lazy='dynamic')

    def __init__(self, device_key, device_type, device_label, device_params):
        self.device_key = device_key
        self.device_type = device_type
        self.device_label = device_label
        self.device_params = device_params

    def as_dict(self):
        return {c.name: getattr(self, c.name) for c in self.__table__.columns}

    def __str__(self):
        if self.device_label:
            return self.device_label
        return self.device_key


class InputLink(Base):
    __tablename__ = 'input_link'
    id = Column(Integer, primary_key=True, autoincrement=True)
    input_device_id = Column(Integer, ForeignKey('input_config.id'), index=True, nullable=False)
    linked_device_id = Column(Integer, nullable=False)
    UniqueConstraint('input_device_id', 'linked_device_id', name='unique_link')

    def __init__(self, input_device_id, linked_device_id):
        self.input_device_id = input_device_id
        self.linked_device_id = linked_device_id

    def as_dict(self):
        return {c.name: getattr(self, c.name) for c in self.__table__.columns}


class OutputLink(Base):
    __tablename__ = 'output_link'
    id = Column(Integer, primary_key=True, autoincrement=True)
    input_device_id = Column(Integer, ForeignKey('input_config.id'), index=True, nullable=False)
    output_device_id = Column(Integer, ForeignKey('output_config.id'), nullable=False)
    UniqueConstraint('input_device_id', 'output_device_id', name='unique_link')

    def __init__(self, input_device_id, output_device_id):
        self.input_device_id = input_device_id
        self.output_device_id = output_device_id

    def as_dict(self):
        return {c.name: getattr(self, c.name) for c in self.__table__.columns}


class InputConfigWrapper(object):

    def __init__(self):
        self._input_config = None
        self._device_label = None
        self._device_type = None
        self._device_enabled = False

    @property
    def id(self):
        if isinstance(self._input_config, InputConfig):
            return self._input_config.id
        return None

    @property
    def device_label(self):
        if isinstance(self._input_config, InputConfig):
            return self._input_config.device_label
        return self._device_label

    @property
    def device_type(self):
        if isinstance(self._input_config, InputConfig):
            return self._input_config.device_type
        return self._device_type

    @property
    def device_enabled(self):
        if isinstance(self._input_config, InputConfig):
            return self._input_config.device_enabled
        return self._device_enabled

    @device_enabled.setter
    def device_enabled(self, enabled):
        # sync the facade
        self._device_enabled = enabled
        if isinstance(self._input_config, InputConfig):
            self._input_config.device_enabled = enabled
        else:
            for input_config in self._input_config:
                input_config.device_enabled = enabled

    @property
    def input_config(self):
        return self._input_config

    @input_config.setter
    def input_config(self, input_config):
        if input_config.group_name:
            self._device_label = input_config.group_name
            self._device_type = 'group'
            # enable group if any input is enable
            if input_config.device_enabled is not None:
                self._device_enabled |= input_config.device_enabled
            if self._input_config is None:
                self._input_config = list()
            self._input_config.append(input_config)
        else:
            self._input_config = input_config


class InputConfigCollection(object):

    def __init__(self):
        self._input_configs = dict()

    @property
    def input_config(self):
        return self._input_configs

    @input_config.setter
    def input_config(self, input_config):
        input_device_key = input_config.device_key
        if input_config.group_name:
            input_device_key = input_config.group_name
        ic_wrapper = None
        if input_device_key not in self._input_configs.keys():
            ic_wrapper = InputConfigWrapper()
            self._input_configs[input_device_key] = ic_wrapper
        else:
            ic_wrapper = self._input_configs[input_device_key]
        ic_wrapper.input_config = input_config


def update_meter_config(input_device_key, meter_config, register_value, meter_value=None):
    if (register_value < 0):
        log.debug('Resetting negative {} meter register value {} to 0.'.format(input_device_key, register_value))
        register_value = 0
    meter_reading_unit = ' ' + meter_config.meter_reading_unit
    if meter_config.meter_reading_unit_factor is None:
        meter_config.meter_reading_unit_factor = 1
    if meter_config.meter_reading_unit_precision is None:
        meter_config.meter_reading_unit_precision = 0
    number_format_string = "{:." + str(meter_config.meter_reading_unit_precision) + "f}"
    # create normalized values
    normalized_register_value = int(register_value) / float(meter_config.meter_reading_unit_factor)
    # update DB
    if meter_value:
        meter_config.meter_value = meter_value
    meter_config.register_value = register_value
    meter_config.meter_reading = number_format_string.format(normalized_register_value) + meter_reading_unit
    db.session.add(meter_config)
    db.session.commit()
    return normalized_register_value


@api_app.get("/", response_class=RedirectResponse)
async def api_root():
    return "/admin/"


@api_app.get("/api/ping")
async def api_ping():
    return "OK"


@api_app.get("/api/running")
async def api_running():
    return startup_complete


class DeviceInfo(BaseModel):
    device_key: str
    device_label: str | None = None
    device_type: str | None = None
    group_name: str | None = None
    location: str | None = None
    is_input: bool
    is_output: bool


@api_app.post("/api/device_info")
async def api_device_info(di: DeviceInfo):
    with flask_app.app_context():
        if di.is_input:
            ic = InputConfig.query.filter_by(device_key=di.device_key).first()
            if ic is None:
                log.info(f'Adding new input configuration for {di.device_key} ({di.device_label})')
                db.session.add(InputConfig(
                    device_key=di.device_key,
                    device_label=di.device_label,
                    device_type=di.device_type,
                    group_name=di.group_name,
                    customized=None,
                    auto_schedule=None,
                    auto_schedule_enable=None,
                    auto_schedule_disable=None,
                    device_enabled=None,
                    trigger_latch_duration=None,
                    multi_trigger_rate=None,
                    multi_trigger_interval=None,
                    info_notify=None))
                db.session.commit()
        if di.is_output:
            oc = OutputConfig.query.filter_by(device_key=di.device_key).first()
            if oc is None:
                log.info(f'Adding new output configuration for {di.device_key} ({di.device_label})')
                db.session.add(OutputConfig(
                    device_key=di.device_key,
                    device_label=di.device_label,
                    device_type=di.device_type,
                    device_params=None))
                db.session.commit()
    with exception_handler(connect_url=URL_WORKER_APP, and_raise=False, shutdown_on_error=True) as zmq_socket:
        di_model = di.model_dump()
        if di.is_input:
            zmq_socket.send_pyobj({'device_info_input': di_model})
        if di.is_output:
            zmq_socket.send_pyobj({'device_info_output': di_model})
    return di


@flask_app.route('/debug-sentry')
def trigger_error():
    1 / 0


@flask_app.route('/logging')
def debug():
    log.setLevel(request.args.get('level'))
    return 'OK'


@flask_app.errorhandler(500)
def internal_server_error(e):
    log.error(f'{e!s}')
    last_event_id = capture_exception(error=e)
    log.info(f'Sentry captured event ID is {last_event_id}.')
    return render_template('error.html',
                           sentry_event_id=last_event_id,
                           sentry_dsn=creds.sentry_dsn
                           ), 500


@flask_app.route('/', methods=['GET', 'POST'])
def index():
    input_configs = InputConfig.query.order_by(InputConfig.device_key).all()
    inputs = InputConfigCollection()
    for input_config in input_configs:
        inputs.input_config = input_config
    if request.method == 'POST':
        if 'panic_button' in request.form:
            log.info('Panic button pressed.')
            with exception_handler(connect_url=URL_WORKER_APP, and_raise=False) as zmq_socket:
                active_devices = [
                    {
                        'device_key': 'App Panic Button',
                        'device_label': 'Panic Button',
                        'type': 'Panic Button'
                    }
                ]
                zmq_socket.send_pyobj({
                    device_name: {
                        'active_devices': active_devices,
                        'outputs_triggered': active_devices,
                    }
                })
        elif 'meter_reset' in request.form:
            device_key = request.form['meter_reset']
            input_cfg = InputConfig.query.filter_by(device_label=device_key).first()
            meter_cfg = MeterConfig.query.filter_by(input_device_id=input_cfg.id).first()
            reset_value = 0
            if meter_cfg.meter_reset_value:
                reset_value = meter_cfg.meter_reset_value
            # override with the prompt value if specified
            if 'prompt_val' in request.form:
                try:
                    reset_value = int(request.form['prompt_val'])
                except ValueError:
                    # oh well
                    pass
            # pylint: disable=unused-variable
            if meter_cfg.meter_reset_additive:
                iot_message = {'adjust_register': reset_value}
                meter_register = meter_cfg.register_value
                meter_register += reset_value
                # override the reset value
                reset_value = meter_register
            else:
                iot_message = {'set_register': reset_value}
            # update the in memory model
            update_meter_config(input_device_key=device_key, meter_config=meter_cfg, register_value=reset_value)
            # send IoT message
            with exception_handler(connect_url=URL_WORKER_MQTT_PUBLISH, socket_type=zmq.PUSH, and_raise=False) as zmq_socket:
                zmq_socket.send_pyobj((
                    app_config.get('mqtt', 'meter_reset_topic'),
                    json.dumps(iot_message)
                ))
        elif 'device_key' in request.form:
            device_key = request.form['device_key']
            input_cfg = inputs.input_config[device_key]
            input_enabled = input_cfg.device_enabled
            # null or false => disabled
            if not input_enabled:
                # therefore, toggle to enabled
                input_enabled = True
            else:
                input_enabled = False
            input_cfg.device_enabled = input_enabled
            # dereference and unwrap
            input_cfgs = list()
            unwrapped_inputs = input_cfg.input_config
            if isinstance(unwrapped_inputs, InputConfig):
                input_cfgs.append(unwrapped_inputs)
            elif isinstance(unwrapped_inputs, list):
                # collect grouped inputs
                input_cfgs.extend(unwrapped_inputs)
            # toggle all real inputs
            for input_cfg in input_cfgs:
                state = 'enabled'
                if not input_cfg.device_enabled:
                    state = 'disabled'
                log.info(f'{input_cfg.device_key} (group {input_cfg.group_name}) is now {state}.')
                db.session.add(input_cfg)
            db.session.commit()
            for input_cfg in input_cfgs:
                invalidate_remote_config(device_key=input_cfg.device_key)
        else:
            log.error('No action associated with this request: {}'.format(request.form))
    meters = dict()
    meter_configs = MeterConfig.query.all()
    for meter_config in meter_configs:
        meters[meter_config.input_device_id] = meter_config
    render_timestamp = make_timestamp(timestamp=None, as_tz=user_tz, make_string=True)
    return render_template('index.html',
                           inputs=inputs.input_config,
                           meters=meters,
                           server_context=device_name,
                           render_timestamp=render_timestamp,
                           healthchecks_badges=app_config.get('app', 'healthchecks_badges').split(','))


@flask_app.route('/metrics', methods=['GET', 'POST'])
def show_metrics():
    return redirect(creds.grafana_url, code=302)


@flask_app.route('/event_log', methods=['GET', 'POST'])
def event_log():
    events = {}
    if request.method == 'GET':
        events = EventLog.query.order_by(EventLog.timestamp.desc()).limit(100).all()
    return render_template('event_log.html',
                           events=events)


@flask_app.route('/config', methods=['GET', 'POST'])
def show_config():
    saved_device_id = None
    if request.method == 'POST':
        saved_device_id = int(request.form['device_id'])
        input = InputConfig.query.filter_by(id=saved_device_id).first()
        # sync up the device model for page load
        input.auto_schedule = bool(request.form.get('auto_schedule'))
        input.auto_schedule_enable = request.form['auto_schedule_enable']
        input.auto_schedule_disable = request.form['auto_schedule_disable']
        db.session.add(input)
        db.session.commit()
        # invalidate remote cache
        invalidate_remote_config(device_key=input.device_key)
        # open IPC
        with exception_handler(connect_url=URL_WORKER_AUTO_SCHEDULER, and_raise=False) as zmq_socket:
            zmq_socket.send_pyobj((input.device_key, str(input), input.auto_schedule, input.auto_schedule_enable, input.auto_schedule_disable))
    inputs = InputConfig.query.order_by(InputConfig.device_key).all()
    return render_template('config.html',
                           inputs=inputs,
                           saved_device_id=saved_device_id)


@api_app.get("/api/input_configs")
async def api_input_config(device_key: str, adb: AsyncSession = Depends(get_db)):
    log.info(f'Async get input config for {device_key}')
    result = await adb.execute(select(InputConfig).where(InputConfig.device_key==device_key))
    config = result.scalars().one_or_none()
    if config:
        return config.as_dict()
    else:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"No input configuration found for {device_key}")


@api_app.get("/api/input_config")
async def api_input_config(device_key: str | None = None) -> list[dict]:
    with flask_app.app_context():
        configs = []
        if device_key:
            db_config = InputConfig.query.filter_by(device_key=device_key).first()
            if db_config:
                configs.append(db_config.as_dict())
        else:
            db_configs = InputConfig.query.all()
            for db_config in db_configs:
                configs.append(db_config.as_dict())
        if len(configs) == 0:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"No input configuration found for {device_key}")
        return configs


@api_app.get("/api/meter_config")
async def api_meter_config(device_key: str) -> list[dict]:
    with flask_app.app_context():
        configs = []
        db_input_config = InputConfig.query.filter_by(device_label=device_key).first()
        if db_input_config:
            db_meter_config = MeterConfig.query.filter_by(input_device_id=db_input_config.id).first()
            if db_meter_config:
                configs.append(db_meter_config.as_dict())
        if len(configs) == 0:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"No meter configuration found for {device_key}")
        return configs


@flask_app.route('/input_config', methods=['GET', 'POST'])
def input_config():
    saved_device_id = None
    if request.method == 'POST':
        saved_device_id = int(request.form['device_id'])
        input_cfg = InputConfig.query.filter_by(id=saved_device_id).first()
        meter_cfg = None
        if input_cfg.device_type == 'meter':
            meter_cfg = MeterConfig.query.filter_by(input_device_id=input_cfg.id).first()
        customized = False
        if 'group_name' in request.form:
            group_name = request.form['group_name'].strip()
            if len(group_name) > 0:
                input_cfg.group_name = group_name
                customized = True
            else:
                input_cfg.group_name = None
        if input_cfg.device_key in request.form.getlist('info_notify'):
            input_cfg.info_notify = True
            customized = True
        else:
            input_cfg.info_notify = None
        if len(request.form['trigger_latch_duration']) > 0:
            input_cfg.trigger_latch_duration = int(request.form['trigger_latch_duration'])
            customized = True
        else:
            input_cfg.trigger_latch_duration = None
        if len(request.form['multi_trigger_rate']) > 0:
            input_cfg.multi_trigger_rate = int(request.form['multi_trigger_rate'])
            customized = True
        else:
            input_cfg.multi_trigger_rate = None
        if len(request.form['multi_trigger_interval']) > 0:
            input_cfg.multi_trigger_interval = int(request.form['multi_trigger_interval'])
            customized = True
        else:
            input_cfg.multi_trigger_interval = None
        if meter_cfg:
            if request.form.get('meter_low_limit', None) and len(request.form['meter_low_limit']) > 0:
                meter_cfg.meter_low_limit = int(request.form['meter_low_limit'])
                customized = True
            else:
                meter_cfg.meter_low_limit = None
            if request.form.get('meter_high_limit', None) and len(request.form['meter_high_limit']) > 0:
                meter_cfg.meter_high_limit = int(request.form['meter_high_limit'])
                customized = True
            else:
                meter_cfg.meter_high_limit = None
            if request.form.get('meter_reset_value', None) and len(request.form['meter_reset_value']) > 0:
                meter_cfg.meter_reset_value = int(request.form['meter_reset_value'])
                customized = True
            else:
                meter_cfg.meter_reset_value = None
            if input_cfg.device_key in request.form.getlist('meter_reset_additive'):
                meter_cfg.meter_reset_additive = True
                customized = True
            else:
                meter_cfg.meter_reset_additive = None
            if request.form.get('meter_iot_topic', None) and len(request.form['meter_iot_topic'].strip()) > 0:
                meter_cfg.meter_iot_topic = request.form['meter_iot_topic'].strip()
                customized = True
            else:
                meter_cfg.meter_iot_topic = None
            if request.form.get('meter_reading_unit', None) and len(request.form['meter_reading_unit'].strip()) > 0:
                meter_cfg.meter_reading_unit = request.form['meter_reading_unit'].strip()
                customized = True
            else:
                meter_cfg.meter_reading_unit = None
            if request.form.get('meter_reading_unit_factor', None) and \
                    len(request.form['meter_reading_unit_factor']) > 0:
                meter_reading_unit_factor = int(request.form['meter_reading_unit_factor'])
                if 1 <= meter_reading_unit_factor <= 1000000000 and (meter_reading_unit_factor % 10 == 0):
                    meter_cfg.meter_reading_unit_factor = meter_reading_unit_factor
                    customized = True
            else:
                meter_cfg.meter_reading_unit_factor = None
            if request.form.get('meter_reading_unit_precision', None) and len(
                    request.form['meter_reading_unit_precision']) > 0:
                meter_reading_unit_precision = int(request.form['meter_reading_unit_precision'])
                if 1 <= meter_reading_unit_precision <= 9:
                    meter_cfg.meter_reading_unit_precision = meter_reading_unit_precision
                    customized = True
            else:
                meter_cfg.meter_reading_unit_precision = None
        input_cfg.customized = customized
        db.session.add(input_cfg)
        if meter_cfg:
            db.session.add(meter_cfg)
        db.session.commit()
        # invalidate remote cache
        invalidate_remote_config(device_key=input_cfg.device_key)
    inputs = InputConfig.query.order_by(InputConfig.device_key).all()
    meters = dict()
    meter_configs = MeterConfig.query.all()
    for meter_config in meter_configs:
        meters[meter_config.input_device_id] = meter_config
    return render_template('input_config.html',
                           inputs=inputs,
                           meters=meters,
                           saved_device_id=saved_device_id)


@flask_app.route('/input_link', methods=['GET', 'POST'])
def input_link():
    saved_device_id = None
    if request.method == 'POST':
        saved_device_id = int(request.form['device_id'])
        # remove existing links for this device
        links = InputLink.query.filter_by(input_device_id=saved_device_id).all()
        for link in links:
            db.session.delete(link)
        db.session.commit()
        # set new links
        for linked_id in request.form.getlist('linked_device_id'):
            db.session.add(InputLink(input_device_id=saved_device_id, linked_device_id=linked_id))
        # save the changes
        db.session.commit()
        # invalidate remote cache
        db_input_config = InputConfig.query.filter_by(id=saved_device_id).first()
        invalidate_remote_config(device_key=db_input_config.device_key)
    input_links = InputConfig.query.add_entity(InputLink).join(InputLink, InputConfig.id==InputLink.input_device_id, isouter=True).order_by(InputConfig.device_key).all()
    inputs = OrderedDict()
    links = dict()
    for input, link in input_links:
        if input.id not in inputs:
            inputs[input.id] = input
        if link:
            if link.input_device_id not in links:
                links[link.input_device_id] = list()
            links[link.input_device_id].append(link.linked_device_id)
    return render_template('input_link.html',
                           inputs=inputs.values(),
                           links=links,
                           saved_device_id=saved_device_id)


@api_app.get("/api/output_link")
async def api_output_link(device_key: str) -> list[dict]:
    with flask_app.app_context():
        configs = []
        db_input_config = InputConfig.query.filter_by(device_key=device_key).first()
        if db_input_config:
            db_output_links = OutputLink.query.filter_by(input_device_id=db_input_config.id).all()
            for db_output_link in db_output_links:
                db_output_config = OutputConfig.query.filter_by(id=db_output_link.output_device_id).first()
                configs.append(db_output_config.as_dict())
        if len(configs) == 0:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"No output link configuration found for {device_key}")
        return configs


@flask_app.route('/output_link', methods=['GET', 'POST'])
def output_link():
    saved_device_id = None
    if request.method == 'POST':
        saved_device_id = int(request.form['device_id'])
        # remove existing links for this device
        links = OutputLink.query.filter_by(input_device_id=saved_device_id).all()
        for link in links:
            db.session.delete(link)
        db.session.commit()
        # set new links
        for output_device_id in request.form.getlist('linked_device_id'):
            db.session.add(OutputLink(input_device_id=saved_device_id, output_device_id=output_device_id))
        # save the changes
        db.session.commit()
        # invalidate remote cache
        db_output_config = OutputConfig.query.filter_by(id=saved_device_id).first()
        invalidate_remote_config(device_key=db_output_config.device_key)
    output_links = InputConfig.query.add_entity(OutputLink).join(OutputLink, InputConfig.id==OutputLink.input_device_id, isouter=True).order_by(InputConfig.device_key).all()
    inputs = OrderedDict()
    outputs = OutputConfig.query.order_by(OutputConfig.device_key).all()
    links = dict()
    for input, link in output_links:
        if input.id not in inputs:
            inputs[input.id] = input
        if link:
            if link.input_device_id not in links:
                links[link.input_device_id] = list()
            links[link.input_device_id].append(link.output_device_id)
    return render_template('output_link.html',
                           inputs=inputs.values(),
                           outputs=outputs,
                           links=links,
                           saved_device_id=saved_device_id)


@api_app.get("/api/output_config")
async def api_output_config(device_key: str | None = None) -> list[dict]:
    with flask_app.app_context():
        configs = []
        if device_key:
            db_config = OutputConfig.query.filter_by(device_key=device_key).first()
            if db_config:
                configs.append(db_config.as_dict())
        else:
            db_configs = OutputConfig.query.all()
            for db_config in db_configs:
                configs.append(db_config.as_dict())
        if len(configs) == 0:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"No output configuration found for {device_key}")
        return configs


@flask_app.route('/output_config', methods=['GET', 'POST'])
def output_config():
    saved_device_id = None
    if request.method == 'POST':
        saved_device_id = int(request.form['device_id'])
        output_config = OutputConfig.query.filter_by(id=saved_device_id).first()
        device_params = request.form['device_params'].strip()
        if len(device_params) > 0:
            output_config.device_params = device_params
        else:
            output_config.device_params = None
        db.session.add(output_config)
        db.session.commit()
        # invalidate remote cache
        invalidate_remote_config(device_key=output_config.device_key)
    outputs = OutputConfig.query.order_by(OutputConfig.device_key).all()
    return render_template('output_config.html',
                           outputs=outputs,
                           saved_device_id=saved_device_id)


async def telegram_bot_echo(update: Update, context: TelegramContextTypes.DEFAULT_TYPE) -> None:
    global ngrok_tunnel_url
    try:
        authorized_users = app_config.get('telegram', 'authorized_users').split(',')
        if str(update.effective_user.id) not in authorized_users:
            log.warning('Unauthorized message {}'.format(str(update)))
            return

        log.info('Telegram Bot {} got message {} (chat ID: {}).'.format(context.bot.username,
                                                                        update.effective_message.text,
                                                                        update.effective_message.chat_id))

        group_info = await context.bot.get_chat(chat_id=app_config.getint('telegram', 'chat_room_id'))
        bot_response = f'I am in the [{group_info.title}]({group_info.invite_link}) group.'
        if ngrok_tunnel_url:
            dashboard_link = f'[Dashboard]({ngrok_tunnel_url})'
            bot_response += "\n" + dashboard_link
        await update.message.reply_markdown(text=bot_response)
    except NetworkError:
        log.warning('bot handler', exc_info=True)
    except Exception:
        log.exception('bot handler')
        capture_exception()


async def telegram_bot_cmd(update: Update, context: TelegramContextTypes.DEFAULT_TYPE) -> None:
    global ngrok_tunnel_url_with_bauth
    try:
        authorized_users = app_config.get('telegram', 'authorized_users').split(',')
        if str(update.effective_user.id) not in authorized_users:
            log.warning('Unauthorized message {}'.format(str(update)))
            return

        log.info('Telegram Bot {} got command {} with args {} (chat ID: {}).'.format(context.bot.username,
                                                                                     update.effective_message.text,
                                                                                     str(context.args),
                                                                                     update.effective_message.chat_id))
        if ngrok_tunnel_url_with_bauth:
            await update.message.reply_markdown(text=f'[Dashboard]({ngrok_tunnel_url_with_bauth})')
        # status update
        if update.effective_message.text.startswith('/'):
            with exception_handler(connect_url=URL_WORKER_APP, and_raise=False) as zmq_socket:
                zmq_socket.send_pyobj({
                    'bot': {
                        'command': update.effective_message.text
                    }
                })
    except NetworkError:
        log.warning('bot handler', exc_info=True)
    except Exception:
        log.exception('bot handler')
        capture_exception()


async def telegram_error_handler(update: Update, context: TelegramContextTypes.DEFAULT_TYPE) -> None:
    # do not capture because there's nothing to handle
    log.warning(msg="Telegram Bot Exception while handling an update:", exc_info=context.error)


def invalidate_remote_config(device_key):
    api_server = app_config.get('app', 'event_processor_address')
    api_method = "invalidate_config"
    try:
        response = requests.post(
            url=f'{api_server}/{api_method}',
            params={"device_key": device_key}
        )
        log.info(f'{response.status_code} response from {api_method} API call to {api_server} to invalidate configuration for {device_key}: {response!s}')
    except ConnectionError as e:
        log.warn(f'Unable to call {api_method} API at {api_server} to invalidate configuration for {device_key}: {e!s}')


class EventProcessor(MQConnection):

    def __init__(self, mqtt_subscriber, mq_server_address, mq_exchange_name):
        MQConnection.__init__(
            self,
            mq_server_address=mq_server_address,
            mq_exchange_name=mq_exchange_name,
            # direct routing
            mq_exchange_type='direct',
            # no control message should live longer than 90s
            mq_arguments={'x-message-ttl': 90*1000})

        self.inputs = {}
        self.outputs = {}

        self._input_trigger_history = {}
        self._input_active_history = {}

        self._input_origin = {}
        self._output_origin = {}

        self._inputs_by_origin = {}
        self._outputs_by_origin = {}

        self._output_type_handlers = None

        self._max_message_validity_seconds = None

        self._device_event_lru = lrucache(100)

        # TODO
        #self.event_log = zmq_socket(zmq.PUSH)

        self.bot = zmq_socket(zmq.PUSH)

        self._mqtt_subscriber = mqtt_subscriber

        self._metric_last_posted_meter_value = 0
        self._metric_meter_value_accumulator = 0
        self._metric_last_posted_register_value = 0

        self.influxdb = None
        self.influxdb_rw = None
        self.influxdb_ro = None
        self.influxdb_bucket = app_config.get('influxdb', 'bucket')

        self._outputs_enabled = True

    def _update_devices(self, event_origin, device_info):
        devices_updated = 0
        for input_outputs, device_origin, origin_devices, io in [
            (self.inputs, self._input_origin, self._inputs_by_origin, 'inputs'),
            (self.outputs, self._output_origin, self._outputs_by_origin, 'outputs')
        ]:
            # next if only input or output
            if io not in device_info:
                continue
            for device in device_info[io]:
                devices_updated += self._update_device(
                    input_outputs=input_outputs,
                    device_origin=device_origin,
                    origin_devices=origin_devices,
                    event_origin=event_origin,
                    device=device
                )
        return devices_updated

    def _update_device(self, input_outputs, device_origin, origin_devices, event_origin, device):
        # device_key must always be present
        try:
            device_key = device['device_key']
        except KeyError:
            log.error(f'No device key in {device}')
            return 0
        # set the device label if that hasn't already been done
        if 'device_label' not in device:
            device['device_label'] = device_key
        # associate the device with this event origin
        if event_origin not in origin_devices:
            origin_devices[event_origin] = set()
        if device_key not in origin_devices[event_origin]:
            origin_devices[event_origin].add(device_key)
        # has this device been seen?
        if device_key not in device_origin:
            device_origin[device_key] = event_origin
        elif device_origin[device_key] != event_origin:
            log.warn(f'{device_key} already known from {device_origin[device_key]} but also sent by {event_origin}.')
        if device_key not in input_outputs:
            input_outputs[device_key] = device
            return 1
        return 0

    def _influxdb_write(self, bucket, active_device_key, field_name, field_value):
        measurement_name = '_'.join(active_device_key.split()).lower()
        try:
            self.influxdb_rw.write(
                bucket=bucket,
                record=Point(measurement_name).tag("application", APP_NAME).tag("device", device_name_base).field(field_name, field_value))
        except Exception:
            log.warning(f'Unable to post measurement {measurement_name} to InfluxDB.', exc_info=True)

    # noinspection PyBroadException
    def run(self):
        # bot
        self.bot.connect(URL_WORKER_TELEGRAM_BOT)
        # influx DB
        self.influxdb = InfluxDBClient(
            url=creds.influxdb_url,
            token=creds.influxdb_token,
            org=creds.influxdb_org)
        self.influxdb_rw = self.influxdb.write_api(write_options=ASYNCHRONOUS)
        self.influxdb_ro = self.influxdb.query_api()
        # https://flask-sqlalchemy.palletsprojects.com/en/3.0.x/quickstart/#create-the-tables
        flask_app.app_context().push()
        # load DB config
        input_configs = InputConfig.query.all()
        # load auto-scheduler
        with exception_handler(connect_url=URL_WORKER_AUTO_SCHEDULER, and_raise=False) as zmq_socket:
            for input_config in input_configs:
                if input_config.auto_schedule:
                    zmq_socket.send_pyobj((
                        input_config.device_key,
                        str(input_config),
                        input_config.auto_schedule,
                        input_config.auto_schedule_enable,
                        input_config.auto_schedule_disable))
        # informational notifications
        # TODO: move to UI configuration
        self.notify_not_before_time = make_timestamp(timestamp=app_config.get('info_notify', 'not_before_time'))
        self.notify_not_after_time = make_timestamp(timestamp=app_config.get('info_notify', 'not_after_time'))
        # message validity
        self._max_message_validity_seconds = int(app_config.get('app', 'max_message_validity_seconds'))
        # output type handlers
        self._output_type_handlers = dict()
        for output_type_handler in [OUTPUT_TYPE_BLUETOOTH, OUTPUT_TYPE_SWITCH, OUTPUT_TYPE_SNAPSHOT, OUTPUT_TYPE_TTS]:
            output_types = app_config.get('output_types', output_type_handler).lower().split(',')
            for output_type in output_types:
                self._output_type_handlers[output_type] = output_type_handler
        # set up the special input for the panic button
        self._update_device(
            input_outputs=self.inputs,
            device_origin=self._input_origin,
            origin_devices=self._inputs_by_origin,
            event_origin=device_name,
            device={
                'device_key': 'App Panic Button',
                'device_label': 'Panic Button',
                'type': 'Panic Button'
            })
        # set up the special input for the dash button
        self._update_device(
            input_outputs=self.inputs,
            device_origin=self._input_origin,
            origin_devices=self._inputs_by_origin,
            event_origin=device_name,
            device={
                'device_key': 'App Dash Button',
                'device_label': 'Dash Button',
                'type': 'Dash Button'
            })
        # set up special output for SMS (text notifications)
        self._update_device(
            input_outputs=self.outputs,
            device_origin=self._output_origin,
            origin_devices=self._outputs_by_origin,
            event_origin=device_name,
            device={
                'device_key': 'SMS',
                'device_label': 'SMS',
                'type': 'SMS'
            })
        with exception_handler(connect_url=URL_WORKER_APP, socket_type=zmq.PULL, and_raise=False, shutdown_on_error=True) as app_socket:
            self._setup_channel()
            while not threads.shutting_down:
                event = app_socket.recv_pyobj()
                log.debug(event)
                if isinstance(event, dict):
                    for event_origin, event_data in list(event.items()):
                        if not isinstance(event_data, dict):
                            log.warning('Ignoring non-dict event format from {}: {} ({})'.format(
                                event_origin, event_data.__class__, event_data))
                            continue
                        if 'timestamp' in event_data:
                            str_timestamp = event_data['timestamp']
                            log.debug('{} timestamp is {}'.format(event_origin, str_timestamp))
                            timestamp = make_timestamp(str_timestamp)
                        else:
                            timestamp = make_timestamp()
                            log_msg = "Message from {} does not include a 'timestamp' so it can't be filtered if it " \
                                      "is stale. Using {}.".format(event_origin, timestamp.strftime(ISO_DATE_FORMAT))
                            if 'active_devices' in event_data or 'outputs_triggered' in event_data:
                                log.warning(log_msg)
                            else:
                                log.debug(log_msg)
                        if 'device_info_input' == event_origin:
                            self._update_device(
                                input_outputs=self.inputs,
                                device_origin=self._input_origin,
                                origin_devices=self._inputs_by_origin,
                                event_origin=device_name,
                                device=event_data)
                        elif 'device_info_output' == event_origin:
                            self._update_device(
                                input_outputs=self.outputs,
                                device_origin=self._output_origin,
                                origin_devices=self._outputs_by_origin,
                                event_origin=device_name,
                                device=event_data)
                        elif 'register_mqtt_origin' == event_origin:
                            # connect to event origins
                            for mqtt_topic, device_id in list(event_data.items()):
                                subscriber = MqttEventSourceSubscriber(mqtt_topic=mqtt_topic, device_id=device_id)
                                # keep the heartbeats up to date
                                self._mqtt_subscriber.add_source_subscriber(topic=mqtt_topic, subscriber=subscriber)
                            continue
                        elif 'auto-scheduler' == event_origin:
                            device_key = event_data['device_key']
                            device_label = event_data['device_label']
                            device_enable = event_data['device_state']
                            log.info(f'Updating device {device_label}; enable: {device_enable}')
                            input_config = InputConfig.query.filter_by(device_key=device_key).first()
                            input_config.device_enabled = device_enable
                            db.session.add(input_config)
                            db.session.commit()
                            invalidate_remote_config(device_key=device_key)
                            # skip further processing because of enable/disable
                            continue
                        elif 'bot' == event_origin:
                            log.debug(f'Got bot command: {event_data!s}')
                            bot_command = event_data['command'].split()
                            bot_command_base = bot_command[0]
                            bot_command_args = None
                            if len(bot_command) > 0:
                                bot_command_args = bot_command[1:]
                            bot_reply = None
                            input_enable = None
                            if bot_command_base.startswith('/report'):
                                device_type = 'Dash Button'
                                log.info(f'Synthesizing {device_type} event...')
                                # splice in a new event
                                event_origin = device_name
                                active_devices = [
                                    {
                                        'device_key': 'App Dash Button',
                                        'device_label': 'Dash Button',
                                        'type': device_type
                                    }
                                ]
                                event_data.update({
                                    'active_devices': active_devices,
                                    'outputs_triggered': active_devices,
                                })
                            elif bot_command_base.startswith('/outputson'):
                                self._outputs_enabled = True
                                bot_reply = f'Outputs enabled.'
                            elif bot_command_base.startswith('/outputsoff'):
                                self._outputs_enabled = False
                                bot_reply = f'Outputs on log-only until the next application restart.'
                            elif bot_command_base.startswith('/inputon'):
                                input_enable = True
                            elif bot_command_base.startswith('/inputoff'):
                                input_enable = False
                            if input_enable is not None:
                                state = 'enable'
                                if not input_enable:
                                    state = 'disable'
                                input_configs = list()
                                if bot_command_args:
                                    for bot_command_arg in bot_command_args:
                                        # https://stackoverflow.com/questions/3325467/sqlalchemy-equivalent-to-sql-like-statement
                                        sql_search = f'%{bot_command_arg}%'
                                        input_config = InputConfig.query.filter(
                                            or_(
                                                InputConfig.device_key.like(sql_search),
                                                InputConfig.device_label.like(sql_search),
                                                InputConfig.group_name.like(sql_search)
                                            )).order_by(InputConfig.device_key).all()
                                        log.info(f'{state.title()} {len(input_config)} devices matching "{bot_command_arg}".')
                                        if len(input_config) > 0:
                                            input_configs.extend(input_config)
                                else:
                                    # wildcard action is constrained to devices where auto-scheduling is enabled
                                    input_config = InputConfig.query.filter(InputConfig.auto_schedule.isnot(None)).order_by(InputConfig.device_key).all()
                                    log.info(f'{state.title()} {len(input_config)} devices with auto-schedule not null.')
                                    if len(input_config) > 0:
                                        log.info(f'{state.title()} {len(input_config)} devices...')
                                        input_configs.extend(input_config)
                                # process all collected inputs
                                inputs_updated = []
                                if len(input_configs) > 0:
                                    for ic in input_configs:
                                        if ic.device_enabled != input_enable:
                                            inputs_updated.append(ic.device_key)
                                            log.info(f'{state.title()} {ic.device_key} (group {ic.group_name})')
                                            ic.device_enabled = input_enable
                                            # update the database
                                            db.session.add(ic)
                                            if ic.auto_schedule is not None:
                                                # update the auto-scheduler task
                                                with exception_handler(connect_url=URL_WORKER_AUTO_SCHEDULER, and_raise=False) as zmq_socket:
                                                    if input_enable:
                                                        # restore auto-schedule actions
                                                        zmq_socket.send_pyobj((ic.device_key, str(ic), ic.auto_schedule, ic.auto_schedule_enable, ic.auto_schedule_disable))
                                                    else:
                                                        # disable runtime auto-scheduling actions
                                                        zmq_socket.send_pyobj((ic.device_key, str(ic), None, None, None))
                                    if len(inputs_updated) > 0:
                                        db.session.commit()
                                        for device_key in inputs_updated:
                                            invalidate_remote_config(device_key=device_key)
                                    bot_reply = f'{len(inputs_updated)} devices changed to {state}.'
                                else:
                                    log.warning(f'No devices matched to {state}.')
                            if bot_reply:
                                log.info(bot_reply)
                                self.bot.send_pyobj({'message': bot_reply})
                            # stop processing
                            if not bot_command_base.startswith('/report'):
                                # no further processing needed after enable/disable
                                continue
                        if 'stale_heartbeat' in event_data:
                            stale_heartbeat = event_data['stale_heartbeat']
                            device_status = "unknown"
                            if 'device_status' in event_data:
                                device_status = event_data['device_status']
                            log.warning(f'{event_origin} status {device_status}: {stale_heartbeat}')
                        if 'max_heartbeat_age' in event_data:
                            heartbeat_age = int(event_data['max_heartbeat_age'])
                            post_count_metric(
                                metric_name='Heartbeat Age',
                                count=heartbeat_age,
                                unit='Seconds')
                        if 'device_info' in event_data:
                            devices_updated = self._update_devices(
                                event_origin=event_origin,
                                device_info=event_data['device_info'])
                            if devices_updated > 0:
                                log.info('{} advertised {} new devices.'.format(event_origin, devices_updated))
                        active_devices = None
                        if 'active_devices' in event_data:
                            active_devices = event_data['active_devices']
                        elif 'outputs_triggered' in event_data:
                            active_devices = event_data['outputs_triggered']
                        if active_devices:
                            for active_device in active_devices:
                                # patch in top-level data, if any
                                if 'storage_url' in event_data:
                                    active_device['storage_url'] = event_data['storage_url']
                                active_device_key = active_device['device_key']
                                # input known?
                                input_config = InputConfig.query.filter_by(device_key=active_device_key).first()
                                if not input_config:
                                    log.warning(f'Input device {active_device_key} is not configured, ignoring.')
                                    continue
                                # message stale?
                                message_age = make_timestamp() - timestamp
                                if message_age > timedelta(seconds=self._max_message_validity_seconds):
                                    log.warning('Skipping further processing of {} from {} due to message age '
                                                '{} exceeding {} seconds.'.format(
                                        active_device_key,
                                        event_origin,
                                        message_age.seconds,
                                        self._max_message_validity_seconds))
                                    continue
                                # always process a fresh meter update
                                meter_config = None
                                if 'type' in active_device and 'meter' in active_device['type'].lower():
                                    meter_value = int(active_device['sample_values']['meter'])
                                    register_value = int(active_device['sample_values']['register'])
                                    meter_config = MeterConfig.query.filter_by(input_device_id=input_config.id).first()
                                    normalized_register_value = update_meter_config(
                                        input_device_key=active_device_key,
                                        meter_config=meter_config,
                                        register_value=register_value,
                                        meter_value=meter_value)
                                    # post metric every n-minutes
                                    now = time.time()
                                    # accumulated meter value
                                    if now - self._metric_last_posted_meter_value > 5*60:
                                        self._influxdb_write(
                                            bucket=self.influxdb_bucket,
                                            active_device_key=active_device_key,
                                            field_name='metered',
                                            field_value=int(self._metric_meter_value_accumulator) / float(meter_config.meter_reading_unit_factor))
                                        self._metric_last_posted_meter_value = now
                                        self._metric_meter_value_accumulator = 0
                                    else:
                                        self._metric_meter_value_accumulator += meter_value
                                    # register values
                                    if now - self._metric_last_posted_register_value > 5*60:
                                        self._influxdb_write(
                                            bucket=self.influxdb_bucket,
                                            active_device_key=active_device_key,
                                            field_name='register',
                                            field_value=normalized_register_value)
                                        self._metric_last_posted_register_value = now
                                # input enabled?
                                if not input_config.device_enabled:
                                    continue
                                # only consider a meter active if the value is out of bounds
                                if 'type' in active_device and 'meter' in active_device['type'].lower():
                                    out_of_range = False
                                    if meter_config.meter_low_limit and register_value < meter_config.meter_low_limit:
                                        out_of_range = True
                                    if meter_config.meter_high_limit and register_value > meter_config.meter_high_limit:
                                        out_of_range = True
                                    if not out_of_range:
                                        continue
                                # multi-trigger
                                if input_config.multi_trigger_interval and input_config.multi_trigger_rate:
                                    # if not in the trigger history, treat as never activated
                                    if active_device_key not in self._input_trigger_history:
                                        self._input_trigger_history[active_device_key] = time.time()
                                        continue
                                    input_last_triggered = self._input_trigger_history[active_device_key]
                                    # the device must have been considered active within the trigger window
                                    last_triggered = time.time() - input_last_triggered
                                    if last_triggered > input_config.multi_trigger_interval:
                                        # update the history and continue
                                        self._input_trigger_history[active_device_key] = time.time()
                                        log.debug(f'Not activating {active_device_key} because it was triggered more than {input_config.multi_trigger_interval} seconds ago. ({last_triggered})')
                                        continue
                                # get the event detail for debouncing
                                event_detail = None
                                if 'event_detail' in active_device:
                                    event_detail = active_device['event_detail']
                                # debounce
                                if active_device_key in self._input_active_history:
                                    # debounce this input
                                    if input_config.trigger_latch_duration:
                                        trigger_latch_duration = input_config.trigger_latch_duration
                                    else:
                                        # FIXME
                                        trigger_latch_duration = 10
                                    input_last_active, last_event_detail = self._input_active_history[active_device_key]
                                    if last_event_detail == event_detail:
                                        last_activated = time.time() - input_last_active
                                        if last_activated < trigger_latch_duration:
                                            # device is still considered active
                                            log.debug(f'Not activating {active_device_key} ({last_event_detail}) because it was triggered less than {trigger_latch_duration} seconds ago. ({last_activated})')
                                            continue
                                self._input_active_history[active_device_key] = (time.time(), event_detail)
                                # active devices are presently assumed to be inputs
                                self._update_device(
                                    input_outputs=self.inputs,
                                    device_origin=self._input_origin,
                                    origin_devices=self._inputs_by_origin,
                                    event_origin=event_origin,
                                    device=active_device)
                                # informational event?
                                # TODO: move to _process_device_event
                                if input_config.info_notify:
                                    # make a future date that is relative (after) now time.
                                    now = datetime.now().replace(tzinfo=tz.tzlocal())
                                    not_before = now.replace(
                                        hour=self.notify_not_before_time.hour,
                                        minute=self.notify_not_before_time.minute,
                                        second=0,
                                        microsecond=0)
                                    not_after = now.replace(
                                        hour=self.notify_not_after_time.hour,
                                        minute=self.notify_not_after_time.minute,
                                        second=0,
                                        microsecond=0)
                                    defer_until = None
                                    if now < not_before:
                                        defer_until = not_before
                                    if now >= not_after:
                                        # the not-before time may not be on the same day
                                        defer_until = not_before + timedelta(days=1)
                                    if defer_until:
                                        # defer the notification
                                        log.info("Deferring notification for '{}' until {}".format(
                                            active_device_key,
                                            defer_until))
                                        continue
                                self._process_device_event(
                                    input_config=input_config,
                                    event_origin=event_origin,
                                    timestamp=timestamp,
                                    active_device_key=active_device_key,
                                    active_device=active_device)
        try_close(self.bot)

    def _process_device_event(self, input_config, event_origin, timestamp, active_device_key, active_device):
        output_links = OutputLink.query.filter_by(input_device_id=input_config.id).all()
        oc_ids = list()
        for output_link in output_links:
            oc_ids.append(output_link.output_device_id)
        output_configs = OutputConfig.query.filter(OutputConfig.id.in_(oc_ids)).order_by(OutputConfig.device_key).all()
        log.info(f'Input {input_config.device_key} is linked to {len(output_configs)} outputs.')
        for output_config in output_configs:
            output_device_key = output_config.device_key
            if output_device_key not in self.outputs:
                log.warning(f'{output_device_key} is not in the set of valid outputs: {self.outputs.keys()}')
                continue
            # device labels
            if input_config.device_label:
                input_device_label = input_config.device_label
            else:
                input_device_label = active_device_key
            if output_config.device_label:
                output_device_label = output_config.device_label
            else:
                output_device_label = output_device_key
            if not self._outputs_enabled:
                log.warning(f'Supressing trigger of {output_device_label} because all outputs are disabled.')
                continue
            # put the event information into the LRU
            self._device_event_lru[active_device_key] = timestamp
            # build up the device activation history
            activation_history = []
            for device_key, activation_time in list(self._device_event_lru.items()):
                # filter out the device being activated
                if device_key == active_device_key:
                    continue
                # TODO: make this configurable
                if (timestamp - activation_time).seconds > 5 * 60:
                    continue
                # list of tuples
                activation_history.append((
                    input_device_label,
                    (timestamp - activation_time).seconds
                ))
            log.info(f'Input {input_device_label} triggers {output_device_label}...')
            # add basic output config
            output_device_activation = {
                output_config.device_key: {
                'device_label': output_config.device_label,
                'device_params': output_config.device_params,
                'type': output_config.device_type
            }}
            # add information about the output device
            output_device_activation.update(self.outputs[output_device_key])
            activation_command = {
                'trigger_output': output_device_activation,
                'input_context': active_device,
                'trigger_history': activation_history,
            }
            if input_config.trigger_latch_duration:
                activation_command['trigger_duration'] = input_config.trigger_latch_duration
            # get the output type
            output_device_type = output_config.device_type.lower()
            # let the bot know first
            if output_device_type == 'sms':
                payload = {'timestamp': timestamp}
                payload.update(activation_command)
                self.bot.send_pyobj(payload)
                continue
            # strip out image data that is no longer needed
            if 'image' in active_device:
                del active_device['image']
            log.debug('Activation command for {} ({}) ({} mapped in {}) is {}'.format(
                output_device_key,
                output_device_label,
                output_device_type,
                self._output_type_handlers,
                activation_command))
            # dispatch the event
            if output_device_type in self._output_type_handlers:
                output_type = self._output_type_handlers[output_device_type]
                log.debug(f'Active device {active_device}: {output_type}.')
                event_payload = None
                if output_type == OUTPUT_TYPE_SNAPSHOT:
                    camera_config = output_config.device_params
                    event_payload = (output_config.device_key, output_device_label, camera_config)
                elif output_type in OUTPUT_TYPE_SWITCH:
                    # use default trigger duration
                    event_payload=(output_config.device_key, None)
                if event_payload:
                    try:
                        self._basic_publish(
                            routing_key=f'event.control.{output_type}',
                            event_payload=event_payload)
                    except Exception:
                        log.warning(self.__class__.__name__, exc_info=True)
            else:
                log.warning(f'{event_origin} {active_device_key} => {output_config.device_key} but nowhere to route the event.')
            # add an entry into the event log
            db.session.add(EventLog(
                input_device=input_device_label,
                output_device=output_device_label,
                timestamp=timestamp))
            db.session.commit()


class TBot(AppThread, Closable):

    def __init__(self, chat_id, sns_fallback=False):
        AppThread.__init__(self, name=self.__class__.__name__)
        Closable.__init__(self, connect_url=URL_WORKER_TELEGRAM_BOT, is_async=True)
        self.chat_id = chat_id
        self.sns_fallback = sns_fallback

    def build_message(timestamp, event_data):
        device_label = event_data['input_context']['device_label']
        event_detail = ""
        if 'event_detail' in event_data['input_context']:
            event_detail = ' {}'.format(event_data['input_context']['event_detail'])
        event_url = None
        if 'storage_url' in event_data['input_context']:
            event_url = event_data['input_context']['storage_url']
        # include a timestamp in this SMS message
        message = '{}{} ({}:{})'.format(
            device_label,
            event_detail,
            timestamp.hour,
            str(timestamp.minute).zfill(2))
        # add in some trigger history for context
        if 'trigger_history' in event_data:
            history_length = len(event_data['trigger_history'])
            log.warning(f'Ignoring included trigger history of {history_length} items.')
        return {'device_label': device_label, 'message': message, 'url': event_url, 'image': None}

    def build_device_message(timestamp, input_device):
        device_label = input_device.device_label
        if device_label is None:
            device_label = input_device.device_key
        event_detail = ''
        if input_device.event_detail:
            event_detail = input_device.event_detail
        # include a timestamp in this SMS message
        message = '{}{} ({}:{})'.format(
            device_label,
            event_detail,
            timestamp.hour,
            str(timestamp.minute).zfill(2))
        image_data = None
        if input_device.image:
            image_data = BytesIO(input_device.image)
        return {'device_label': device_label, 'message': message, 'url': input_device.storage_url, 'image': image_data}

    def include_image(message):
            if not app_config.getboolean('telegram', 'image_send_only_with_people'):
                return True
            if 'person' in message:
                return True
            return False

    async def tbot_run(t_app: TelegramApp, zmq_socket, chat_id, sns_fallback):
        global ngrok_tunnel_url_with_bauth
        poller = Poller()
        poller.register(zmq_socket, zmq.POLLIN)
        pending_by_label = OrderedDict()
        log.info(f'Waiting for events to forward to Telegram bot on chat ID {chat_id}...')
        min_send_interval = app_config.getint('telegram', 'min_send_interval')
        last_sent = 0
        while not threads.shutting_down:
            now = round(time.time())
            event = None
            events = await poller.poll(timeout=1000)
            if zmq_socket in dict(events):
                try:
                    event = await zmq_socket.recv_pyobj()
                except ZMQError as e:
                    log.exception(f'Cannot get message from ZMQ channel.')
            if event is None and not pending_by_label:
                continue
            try:
                if event:
                    if not isinstance(event, dict):
                        log.warn(f'Non dictionary type {type(event)} received, ignoring.')
                        continue
                    input_device = None
                    output_device = None
                    try:
                        if 'active_input' in event.keys():
                            input_device = Device(**event['active_input'])
                            log.debug(f'Input device for message: {input_device!s}')
                        if 'output_triggered' in event.keys():
                            output_device = Device(**event['output_triggered'])
                            log.debug(f'Output device for message: {output_device!s}')
                    except Exception as e:
                        log.warn('Bot message unpack problem {e!s}', exc_info=True)
                        continue
                    timestamp = None
                    if 'timestamp' in event:
                        timestamp = make_timestamp(timestamp=event['timestamp'], as_tz=user_tz)
                    else:
                        log.warn('No timestamp included in event message; using "now"')
                        timestamp = make_timestamp(as_tz=user_tz)
                    log.debug(f'{input_device!s};{output_device!s};{timestamp!s}')
                    # build the message
                    message = None
                    if input_device:
                        log.debug(f'Building message based on input device: {input_device!s}')
                        try:
                            message = TBot.build_device_message(timestamp=timestamp, input_device=input_device)
                        except Exception as e:
                            log.warn(f'Bot message build error {e!s}', exc_info=True)
                            continue
                    else:
                        input_context = None
                        image_data = None
                        if 'message' in event:
                            message = {'device_label': None, 'message': event['message'], 'url': None, 'image': None}
                            if 'add_tunnel_url' in event and ngrok_tunnel_url_with_bauth:
                                message = '[{}]({})'.format(message, ngrok_tunnel_url_with_bauth)
                        else:
                            message = TBot.build_message(timestamp=timestamp, event_data=event)
                            if 'input_context' in event:
                                input_context = event['input_context']
                                if 'image' in input_context:
                                    image_data = BytesIO(input_context['image'])
                                    message['image'] = image_data
                    # always queue the message
                    device_label = message['device_label']
                    message_timestamp = make_unix_timestamp(timestamp=timestamp)
                    message['timestamp'] = message_timestamp
                    try:
                        pending = pending_by_label[device_label]
                    except KeyError:
                        pending = deque()
                        pending_by_label[device_label] = pending
                    log.info(f'Preparing message to send about {device_label} ({message_timestamp}) with {len(pending)} already queued...')
                    pending.append(message)
                # rate-limit the send
                # https://core.telegram.org/bots/faq#my-bot-is-hitting-limits-how-do-i-avoid-this
                time_since_sent = now - last_sent
                if time_since_sent < min_send_interval:
                    log.warn(f'Rate limiting message queue (of {len(pending_by_label)} devices). Time since last send is {time_since_sent}s, min interval is {min_send_interval}s.')
                    continue
                # dequeue the message
                # since we favour brevity over temporal precision, access the event
                # dictionary by rough order of entry and take the latest event for a
                # label, except for images in which all images with features detected
                # should be batched and sent.
                pending = pending_by_label.popitem(last=False)[1]
                message = pending.popleft()
                image_batch = []
                # other messages to dedupe
                while len(pending) > 0:
                    device_label = message['device_label']
                    message_timestamp = message['timestamp']
                    log.info(f'Processing {len(pending)} more events for {device_label} ({message_timestamp})...')
                    # keep all image data as configured
                    if message['image']:
                        if not TBot.include_image(message=message['message']):
                            log.warn(f'Discarding event from {device_label} with image of no persons.')
                            message = pending.popleft()
                            continue
                        elif len(image_batch) < MediaGroupLimit.MAX_MEDIA_LENGTH:
                            image_batch.append(InputMediaPhoto(
                                media=message['image'],
                                caption=message['message'],
                                caption_entities=[
                                    MessageEntity(
                                        type=MessageEntity.TEXT_LINK,
                                        offset=0,
                                        length=len(device_label),
                                        url=message['url']
                                    )
                                ]))
                            # pop images in insertion order since all will be taken
                            message = pending.popleft()
                            continue
                        else:
                            # enough is enough, re-enqueue the remainder
                            log.info(f'Re-enqueing {len(pending)} remaining events for {device_label} because image batch to send is now {len(image_batch)} items.')
                            pending_by_label[device_label] = pending
                            break
                    else:
                        oldest_message_timestamp = message['timestamp']
                        old_count = len(pending)
                        # take the latest message, tossing the current and the remainders
                        message = pending.pop()
                        message_timestamp = message['timestamp']
                        log.info(f'Removing {old_count} redundant events for {device_label} (new/old: {message_timestamp}/{oldest_message_timestamp})')
                        pending.clear()
                # send the message
                device_label = message['device_label']
                notification_message = message['message']
                notification_url = message['url']
                image_data = message['image']
                message_timestamp = message['timestamp']
                if len(image_batch) > 0:
                    log.info(f'Sending image group to {chat_id!s} containing {len(image_batch)} images.')
                    await t_app.bot.send_media_group(chat_id=chat_id, media=image_batch, read_timeout=300, write_timeout=300, connect_timeout=300, pool_timeout=300)
                elif image_data:
                    log.info(f'Sending image about {device_label} ({message_timestamp}) to {chat_id!s} with caption "{notification_message}"')
                    await t_app.bot.send_photo(chat_id=chat_id,
                                        photo=image_data,
                                        caption=notification_message,
                                        caption_entities=[
                                            MessageEntity(
                                                type=MessageEntity.TEXT_LINK,
                                                offset=0,
                                                length=len(device_label),
                                                url=notification_url
                                            )
                                        ])
                else:
                    if device_label:
                        log.info(f'Sending message about {device_label} ({message_timestamp}) to {chat_id!s} with caption "{notification_message}"')
                    else:
                        log.info(f'Sending message to {chat_id!s} with caption "{notification_message}"')
                    await t_app.bot.send_message(chat_id=chat_id,
                                            text=notification_message,
                                            parse_mode='Markdown')
                # update send time
                last_sent = now
            except Exception:
                capture_exception()
                log.exception(f'General issue with bot message processing.')


    def run(self):
        log.info('Creating asyncio event loop...')
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        log.info('Creating Telegram application...')
        telegram_application = TelegramApp.builder().token(creds.telegram_bot_api_token).build()
        telegram_application.add_handler(TelegramCommandHandler(command='report', callback=telegram_bot_cmd))
        telegram_application.add_handler(TelegramCommandHandler(command='inputon', callback=telegram_bot_cmd))
        telegram_application.add_handler(TelegramCommandHandler(command='inputoff', callback=telegram_bot_cmd))
        telegram_application.add_handler(TelegramCommandHandler(command='outputson', callback=telegram_bot_cmd))
        telegram_application.add_handler(TelegramCommandHandler(command='outputsoff', callback=telegram_bot_cmd))
        telegram_application.add_handler(TelegramMessageHandler(filters.TEXT & ~filters.COMMAND, telegram_bot_echo))
        telegram_application.add_error_handler(callback=telegram_error_handler)
        self.t_app = telegram_application
        log.info('Registering coroutine for ZMQ-Telegram messages...')
        self.get_socket()
        outcome = asyncio.run_coroutine_threadsafe(
            TBot.tbot_run(
                t_app=self.t_app,
                zmq_socket=self.socket,
                chat_id=self.chat_id,
                sns_fallback=self.sns_fallback),
            loop)
        log.info('Starting Telegram application...')
        self.t_app.run_polling(stop_signals=None)
        log.info('Waiting for coroutine exceptions...')
        exc = outcome.exception()
        if exc is not None:
            log.warning('Completed with exception.', exc)
        log.info('Closing event loop...')
        loop.close()
        log.info('Shutdown complete.')

    def shutdown(self):
        # TODO: shut down Telegram bot from external
        # event loop if stop_signals=None
        log.info('Closing ZMQ socket...')
        self.close()


class MqttSubscriber(AppThread, Closable):

    def __init__(self):
        AppThread.__init__(self, name=self.__class__.__name__)
        Closable.__init__(self, connect_url=URL_WORKER_HEARTBEAT_NANNY, socket_type=zmq.PUSH)
        self._mqtt_client = None
        self._mqtt_server_address = app_config.get('mqtt', 'server_address')
        self._mqtt_subscribe_topics = list()
        # match Paho interface
        for mqtt_source in app_config.get('mqtt', 'subscription_sources').split(','):
            self._mqtt_subscribe_topics.append((mqtt_source, 0))
        self._source_subscribers = dict()

        self._disconnected = False

    def close(self):
        Closable.close(self)
        try:
            self._mqtt_client.disconnect()
        except Exception:
            log.warning('Ignoring error closing MQTT socket.', exc_info=True)

    def add_source_subscriber(self, topic, subscriber):
        self._source_subscribers[topic] = subscriber

    def on_connect(self, client, userdata, connect_flags, reason_code, properties):
        log.info('Subscribing to topics {}'.format(self._mqtt_subscribe_topics))
        self._mqtt_client.subscribe(self._mqtt_subscribe_topics)

    def on_disconnect(self, client, userdata, disconnect_flags, reason_code, properties):
        log.info('MQTT client has disconnected.')
        self._disconnected = True

    def on_message(self, client, userdata, message):
        log.debug('{} received {} bytes.'.format(message.topic, len(message.payload)))
        msg_data = None
        try:
            msg_data = json.loads(message.payload)
        except JSONDecodeError:
            log.exception('Unstructured message: {}'.format(message.payload))
            return
        # ignore any message without a device ID
        if 'device_id' not in msg_data:
            warning_message = f'Ignoring {len(message.payload)} bytes from topic {message.topic} with no device_id set.'
            if len(message.payload) <= 100:
                warning_message += f' Payload: {message.payload!s}'
            log.warning(warning_message)
            return
        device_id = msg_data['device_id']
        input_location = msg_data['input_location']
        # FIXME: create a canonical topic form
        topic_base = '/'.join(message.topic.split('/')[0:2])
        # get the subscriber and update the timestamp
        if topic_base in self._source_subscribers:
            subscriber = self._source_subscribers[topic_base]
            subscriber.last_message = time.time()
        event_timestamp = make_timestamp()
        # unpack the message
        try:
            if message.topic.startswith('sensor'):
                device_inputs = list()
                active_devices = list()
                for msg_key in msg_data:
                    # look for inputs
                    if re.match("input_\d+", msg_key): # pylint: disable=anomalous-backslash-in-string
                        input_label = msg_data[msg_key]['input_label']
                        sample_value = msg_data[msg_key]['sample_value']
                        input_active = msg_data[msg_key]['active']
                        device_description = {
                            'type': input_label,
                            'location': input_location,
                            'device_key': '{} {}'.format(input_location, input_label)
                        }
                        if input_active:
                            log.info('{}: {} {} ({})'.format(device_id, input_location, input_label, sample_value))
                            device_description['sample_value'] = sample_value
                            active_devices.append(device_description)
                        else:
                            device_inputs.append(device_description)
                self.socket.send_pyobj({
                    topic_base: {
                        'device_info': {'inputs': device_inputs},
                        'inputs': device_inputs,
                        'active_devices': active_devices,
                        'outputs_triggered': active_devices,
                        'timestamp': event_timestamp
                    }
                })
            elif message.topic.startswith('meter'):
                topic_parts = message.topic.split('/')
                device_type = topic_parts[0]
                device_name = topic_parts[1]
                device_key = '{} {}'.format(device_name, device_type).title()
                metered = msg_data['last_minute_metered']
                register = msg_data['register_reading']
                log.debug('{}: {} ({} of {})'.format(device_id, input_location, metered, register))
                active_devices = [{
                    'device_key': device_key,
                    'type': 'meter',
                    'sample_values': {
                        'meter': metered,
                        'register': register
                    }
                }]
                self.socket.send_pyobj({
                    topic_base: {
                        'device_info': {
                            'inputs': [{
                                'device_key': device_key,
                                'type': 'meter'
                            }]
                        },
                        'active_devices': active_devices,
                        'outputs_triggered': active_devices,
                        'timestamp': event_timestamp
                    }
                })
        except ContextTerminated:
            self.close()

    # noinspection PyBroadException
    def run(self):
        log.info('Connecting to MQTT server {}...'.format(self._mqtt_server_address))
        self.get_socket()
        self._mqtt_client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
        self._mqtt_client.on_connect = self.on_connect
        self._mqtt_client.on_disconnect = self.on_disconnect
        self._mqtt_client.on_message = self.on_message
        self._mqtt_client.connect(self._mqtt_server_address)
        with exception_handler(connect_url=URL_WORKER_MQTT_PUBLISH, socket_type=zmq.PULL, and_raise=False, shutdown_on_error=True) as zmq_socket:
            while not threads.shutting_down:
                rc = self._mqtt_client.loop()
                if rc == MQTT_ERR_NO_CONN or self._disconnected:
                    raise ResourceWarning(f'No connection to MQTT broker at {self._mqtt_server_address} (disconnected? {self._disconnected})')
                # check for messages to publish
                try:
                    mqtt_pub_topic, message_data = zmq_socket.recv_pyobj(flags=zmq.NOBLOCK)
                    log.debug('Publishing {} bytes to topic {}...'.format(len(message_data), mqtt_pub_topic))
                    self._mqtt_client.publish(topic=mqtt_pub_topic, payload=message_data)
                except Again:
                    # ignore, no data
                    pass
        self.close()


class EventSourceSubscriber(object):

    def __init__(self, label):
        self._label = label

    @property
    def label(self):
        return self._label


class MqttEventSourceSubscriber(EventSourceSubscriber):

    def __init__(self, mqtt_topic, device_id):
        super(MqttEventSourceSubscriber, self).__init__(label=mqtt_topic)
        self._device_id = device_id

    @property
    def device_id(self):
        return self._device_id


class SQSListener(AppThread):

    def __init__(self):
        AppThread.__init__(self, name=self.__class__.__name__)
        self._sqs = None
        self._sqs_queue = None

    # noinspection PyBroadException
    def run(self):
        sqs_queue_name = os.environ['SQS_QUEUE']
        log.info('Listening for control messages on SQS queue {}'.format(sqs_queue_name))
        # set up notifications
        self._sqs = boto3.resource('sqs')
        self._sqs_queue = self._sqs.get_queue_by_name(QueueName=sqs_queue_name)
        with exception_handler(connect_url=URL_WORKER_APP, and_raise=False, shutdown_on_error=True) as zmq_socket:
            while not threads.shutting_down:
                try:
                    for sqs_message in self._sqs_queue.receive_messages(WaitTimeSeconds=10):
                        # forward for further processing
                        message_body = sqs_message.body
                        try:
                            zmq_socket.send_pyobj({'sqs': json.loads(message_body)})
                        except JSONDecodeError:
                            log.exception('Unstructured SQS message: {}'.format(message_body))
                        # Let the queue know that the message is processed
                        sqs_message.delete()
                    # test for ZMQ shutdown
                    zmq_socket.poll(timeout=0)
                except bcece:
                    log.warning(f'SQS', exc_info=True)
                    threads.interruptable_sleep.wait(10)


class AutoScheduler(AppThread, Closable):

    def __init__(self):
        AppThread.__init__(self, name=self.__class__.__name__)
        Closable.__init__(self, connect_url=URL_WORKER_APP, socket_type=zmq.PUSH)

    def update_device(self, device_key, device_label, device_state):
        log.info('Updating {} to enabled={}'.format(device_key, device_label, device_state))
        self.socket.send_pyobj({
            'auto-scheduler': {
                'device_key': device_key,
                'device_label': device_label,
                'device_state': device_state
            }})

    def _schedule(self, device_key, device_label, schedule_time, device_state):
        schedule.every().day.at(schedule_time).do(self.update_device, device_key=device_key, device_label=device_label, device_state=device_state).tag(device_key)

    # noinspection PyBroadException
    def run(self):
        self.get_socket()
        with exception_handler(connect_url=URL_WORKER_AUTO_SCHEDULER, socket_type=zmq.PULL, and_raise=False, shutdown_on_error=True) as zmq_socket:
            while not threads.shutting_down:
                next_message = False
                # trigger any scheduled work
                schedule.run_pending()
                # look for device updates
                device_key = None
                try:
                    device_key, device_label, auto_schedule, auto_schedule_enable, auto_schedule_disable = zmq_socket.recv_pyobj(flags=zmq.NOBLOCK)
                    next_message = True
                except ZMQError:
                    # ignore, no data
                    next_message = False
                if device_key:
                    # clear any previous schedule
                    schedule.clear(device_key)
                    if auto_schedule:
                        log.info(f'Setting auto-schedule for {device_label} to disable at {auto_schedule_disable} and enable at {auto_schedule_enable}...')
                        try:
                            # install a new scedule
                            self._schedule(
                                device_key=device_key,
                                device_label=device_label,
                                schedule_time=auto_schedule_disable,
                                device_state=False)
                            self._schedule(
                                device_key=device_key,
                                device_label=device_label,
                                schedule_time=auto_schedule_enable,
                                device_state=True)
                        except ScheduleValueError:
                            log.exception(f'Unable to schedule.')
                    else:
                        log.warning(f'Disabled auto-schedule for {device_label}.')
                # don't spin
                if not next_message:
                    threads.interruptable_sleep.wait(10)
        self.close()


class HeartbeatFilter(ZmqRelay):

    def __init__(self):
        ZmqRelay.__init__(self,
            name=self.__class__.__name__,
            source_zmq_url=URL_WORKER_HEARTBEAT_NANNY,
            sink_zmq_url=URL_WORKER_APP)

        self._heartbeat_report_interval = int(app_config.get('app', 'heartbeat_report_interval_seconds'))
        self._max_heartbeat_delay = int(app_config.get('app', 'max_heartbeat_delay_seconds'))

        self._heartbeats = {}

        self._last_report = int(time.time())
        self._heartbeat_report_due = False
        self._max_reported_heartbeat = -1

    def process_message(self, sink_socket):
        event = self.socket.recv_pyobj()
        origin, data = list(event.items())[0]
        now = int(time.time())
        last_time = None
        if origin in self._heartbeats:
            last_time = self._heartbeats[origin]
        # update new heartbeat
        self._heartbeats[origin] = now
        if last_time:
            heartbeat_age = now - last_time
            data['heartbeat_age'] = heartbeat_age
            last_time_formatted = make_timestamp(timestamp=last_time, make_string=True)
            if heartbeat_age > self._max_heartbeat_delay:
                # used as an out-of-band trigger for something amiss
                data['stale_heartbeat'] = last_time_formatted
            # update maximum heartbeat seen in this interval
            if heartbeat_age > self._max_reported_heartbeat:
                self._max_reported_heartbeat = heartbeat_age
        else:
            heartbeat_age = None
            last_time_formatted = "never"
        log.debug(f'Last activity from {origin} was {heartbeat_age}s ago ({last_time_formatted}).')
        # send the maximum heartbeat, and reset
        if now - self._last_report > self._heartbeat_report_interval:
            data['max_heartbeat_age'] = self._max_reported_heartbeat
            self._last_report = now
            self._max_reported_heartbeat = -1
        # forward the original payload
        sink_socket.send_pyobj({origin: data})


class BridgeFilter(AppThread):

    def __init__(self):
        AppThread.__init__(self, name=self.__class__.__name__)
        self.bot = zmq_socket(zmq.PUSH)

    def visit_keys(dictionary, parent_key=''):
        for key, value in dictionary.items():
            full_key = f'{parent_key}.{key}' if parent_key else key
            if isinstance(value, dict):
                BridgeFilter.visit_keys(value, full_key)
            elif isinstance(value, str) or isinstance(value, int):
                log.info(f'{full_key}::{value}')
            else:
                log.info(f'{full_key}::{type(value)}')

    # noinspection PyBroadException
    def run(self):
        self.bot.connect(URL_WORKER_TELEGRAM_BOT)
        with exception_handler(connect_url=URL_WORKER_APP_BRIDGE, socket_type=zmq.PULL, and_raise=False) as zmq_socket:
            while not threads.shutting_down:
                control_payload = zmq_socket.recv_pyobj()
                if not isinstance(control_payload, dict):
                    log.info('Malformed event; expecting dictionary.')
                    continue
                if 'sms' in control_payload.keys():
                    log.info(f'Sending payload to bot {control_payload.keys()}...')
                    try:
                        self.bot.send_pyobj(control_payload['sms'])
                    except ZMQError as e:
                        log.warn(f'ZMQ error {e!s}', exc_info=True)
                    log.info(f'Sent payload to bot...')
                else:
                    log.warn(f'Unsupported payload with keys {control_payload.keys()}')
                    BridgeFilter.visit_keys(control_payload)
        try_close(self.bot)


class EventSourceDiscovery(AppThread):
    def __init__(self):
        AppThread.__init__(self, name=self.__class__.__name__)
        self._mdash_api_key = creds.mdash_api_key
        self._mdash_devices_url = app_config.get('mdash', 'base_url')
        self._app_config_mqtt_pub_topic = app_config.get('mdash', 'app_config_mqtt_pub_topic')
        self._device_tags = app_config.get('mdash', 'device_tags').split(',')

    def run(self):
        if threads.shutting_down:
            return

        with exception_handler(connect_url=URL_WORKER_APP, and_raise=False, shutdown_on_error=True) as zmq_socket:
            # register MQTT inputs
            mdash_devices = None
            log.info(f'Requesting mDash device listing from {self._mdash_devices_url}')
            # get listing of all applications
            mdash_devices = requests.get(
                url=self._mdash_devices_url,
                params={
                    "access_token": self._mdash_api_key
                }).json()
            log.info(f'mDash returns {len(mdash_devices)} devices.')
            for device in mdash_devices:
                device_id = device['id']
                device_online = device['online']
                if 'shadow' not in device.keys():
                    log.warning(f'Ignoring mDash device {device_id} with missing shadow configuration.')
                    continue
                device_shadow = device['shadow']
                if 'tags' not in device_shadow or 'labels' not in device_shadow['tags']:
                    log.warning(f'mDash device {device_id} is missing tagging information for inclusion.')
                    continue
                tag_supported = False
                device_tags = device_shadow['tags']['labels'].split(',')
                for device_tag in device_tags:
                    if device_tag in self._device_tags:
                        tag_supported = True
                        break
                if not tag_supported:
                    log.warning(f'mDash device {device_id} ({device_tags=}) not in supported tags: {self._device_tags!s}.')
                    continue
                log.info(f'Retrieving mDash information for device {device_id} ({device_tags!s}) ({device_online=})')
                # retrieve specific configuration item
                mqtt_pub_topic = requests.post(
                    url='{}/{}/rpc/Config.Get'.format(
                        self._mdash_devices_url,
                        device_id),
                    json={
                        'key': self._app_config_mqtt_pub_topic
                    },
                    params={
                        "access_token": self._mdash_api_key
                    })
                # strip double-quotes from topic name
                mqtt_pub_topic_name = mqtt_pub_topic.text.replace('"', '').rstrip()
                log.info(f"MQTT topic for {device_id} is '{mqtt_pub_topic_name}'")
                zmq_socket.send_pyobj({'register_mqtt_origin': {mqtt_pub_topic_name: device_id}})

        # un-nanny and goodbye
        self.untrack()


class CallbackUrlDiscovery(AppThread):

    def __init__(self):
        AppThread.__init__(self, name=self.__class__.__name__)

        self._discover_url = 'http://127.0.0.1:{}/api/tunnels/{}'.format(
            app_config.get('ngrok', 'client_api_port'),
            app_config.get('ngrok', 'tunnel_name'))

    # noinspection PyBroadException
    def run(self):
        # sudo update
        global ngrok_tunnel_url
        while not threads.shutting_down:
            try:
                ngrok_tunnel_url = requests.get(self._discover_url).json()['public_url']
            except (KeyError, ConnectionError) as e:
                log.warning('Still attempting to discover ngrok tunnel URL ({})...'.format(repr(e)))
                threads.interruptable_sleep.wait(10)
                continue
            log.info('External call-back URL is {}'.format(ngrok_tunnel_url))
            break
        purl = urlparse(ngrok_tunnel_url)
        # decorate the URL with basic-auth details
        global ngrok_tunnel_url_with_bauth
        ngrok_tunnel_url_with_bauth = '{}://{}:{}@{}{}'.format(purl.scheme,
                                                                creds.flask_basic_auth_username,
                                                                creds.flask_basic_auth_password,
                                                                purl.netloc,
                                                                purl.path)
        # thread is done
        self.untrack()


class ApiServer(Thread):

    def __init__(self):
        super(ApiServer, self).__init__(name=self.__class__.__name__)
        self.server = None

        config = uvicorn.Config(
            app="app.__main__:api_app",
            host='0.0.0.0',
            port=int(app_config.get('flask', 'http_port')),
            log_level="warning",
            timeout_graceful_shutdown=1)
        self.server = uvicorn.Server(config)

    def run(self):
        log.warning('Starting API server...')
        self.server.run()
        log.warning('API server is finished.')

    def shutdown(self):
        if self.server:
            log.warning(f'API server shutting down: {self.__class__.__name__}')
            # emulate signal handler latch in server.handle_exit()
            self.server.force_exit = True
            try:
                asyncio.run(self.server.shutdown())
            except Exception:
                log.warning("Ignoring API server shutdown issue.", exc_info=True)
            log.warning(f'API server shutdown complete: {self.__class__.__name__}')


def main():
    log.setLevel(logging.INFO)
    # ensure proper signal handling; must be main thread
    signal_handler = SignalHandler()
    if not threads.shutting_down:
        log.info('Creating application threads...')
        # bind listeners first
        heartbeat_filter = HeartbeatFilter()
        app_bridge = BridgeFilter()
        mq_server_address=app_config.get('rabbitmq', 'server_address').split(',')
        mq_exchange_name=app_config.get('rabbitmq', 'mq_exchange')
        mq_listener = ZMQListener(
            zmq_url=URL_WORKER_HEARTBEAT_NANNY,
            mq_server_address=mq_server_address,
            mq_exchange_name=mq_exchange_name,
            mq_topic_filter='event.#',
            mq_exchange_type='topic')
        mq_listener_bridge = ZMQListener(
            zmq_url=URL_WORKER_APP_BRIDGE,
            mq_server_address=mq_server_address,
            mq_exchange_name=f'{mq_exchange_name}_control',
            mq_topic_filter='event.trigger.sms',
            mq_exchange_type='direct')
        mqtt_subscriber = MqttSubscriber()
        auto_scheduler = AutoScheduler()
        event_processor = EventProcessor(
            mqtt_subscriber,
            mq_server_address,
            f'{mq_exchange_name}_control')
        # connect ZMQ IPC clients next
        event_source_discovery = EventSourceDiscovery()
        sqs_listener = None
        if app_config.getboolean('app', 'sns_control_enabled'):
            sqs_listener = SQSListener()
        # configure Telegram bot
        telegram_bot = TBot(
            chat_id=app_config.getint('telegram', 'chat_room_id'),
            sns_fallback=app_config.getboolean('telegram', 'sns_fallback_enabled'))
        # start the nanny
        nanny = threading.Thread(
            daemon=True,
            name='nanny',
            target=thread_nanny,
            args=(signal_handler,))
        # not tracked by nanny because this is used for Flask bootstrap
        server = ApiServer()
        # startup completed
        # back to INFO logging
        log.setLevel(logging.INFO)
        try:
            log.info(f'Starting {APP_NAME} threads...')
            # start the binders
            event_processor.start()
            heartbeat_filter.start()
            app_bridge.start()
            telegram_bot.start()
            # start the connectors
            event_source_discovery.start()
            mqtt_subscriber.start()
            auto_scheduler.start()
            if sqs_listener:
                sqs_listener.start()
            mq_listener.start()
            mq_listener_bridge.start()
            # HTTP APIs
            server.start()
            # get supporting services going
            if app_config.getboolean('ngrok', 'enabled'):
                # discover ngrok callback URL
                CallbackUrlDiscovery().start()
            nanny.start()
            global startup_complete
            startup_complete = True
            log.info('Startup complete.')
            # block on threading event
            threads.interruptable_sleep.wait()
        finally:
            die()
            message = "Shutting down {}..."
            log.info(message.format('API server'))
            server.shutdown()
            log.info(message.format('Main event processor'))
            event_processor.stop()
            log.info(message.format('Telegram Bot'))
            telegram_bot.shutdown()
            log.info(message.format('Rabbit MQ listener'))
            mq_listener.stop()
            zmq_term()
        bye()


if __name__ == "__main__":
    main()