#!/usr/bin/env python
import logging.handlers

import asyncio
import builtins
import os
import requests
import schedule
import threading
import time
import uvicorn
import zmq
import zmq.asyncio

from collections import OrderedDict, deque
from concurrent import futures
from datetime import datetime, timedelta
from dateutil import tz
from flask_sqlalchemy import SQLAlchemy
from io import BytesIO
from os import path
from pylru import lrucache
from pytz import timezone
from requests.exceptions import ConnectionError
from schedule import ScheduleValueError
from sentry_sdk import capture_exception
from sentry_sdk.integrations.excepthook import ExcepthookIntegration
from sentry_sdk.integrations.flask import FlaskIntegration
from sentry_sdk.integrations.logging import ignore_logger
from sentry_sdk.integrations.threading import ThreadingIntegration
from telegram import ForceReply, Update, InputMediaPhoto, MessageEntity
from telegram.constants import MediaGroupLimit
from telegram.ext import Application as TelegramApp, \
    Updater as TelegramUpdater, \
    CommandHandler as TelegramCommandHandler, \
    ContextTypes as TelegramContextTypes, \
    MessageHandler as TelegramMessageHandler, \
    filters
from telegram.error import NetworkError, RetryAfter, TimedOut
from threading import Thread
from UnleashClient import UnleashClient
from urllib.parse import urlparse

from fastapi import FastAPI, Depends, status, HTTPException
from fastapi.responses import RedirectResponse
from fastapi.middleware.wsgi import WSGIMiddleware

from pydantic import BaseModel

from flask import Flask, g, flash, request, render_template, url_for, redirect
from flask.logging import default_handler
from flask_compress import Compress
from werkzeug.serving import make_server

from zmq.asyncio import Poller
from zmq.error import ZMQError

# setup builtins used by pylib init
builtins.SENTRY_EXTRAS = [
    FlaskIntegration(transaction_style="url"),
    ThreadingIntegration(propagate_scope=True)
]
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
    telegram_bot_api_token: f'opitem:"Telegram" opfield:{APP_NAME}.token' = None # type: ignore
    influxdb_org: f'opitem:"InfluxDB" opfield:{influx_creds_section}.org' = None # type: ignore
    influxdb_token: f'opitem:"InfluxDB" opfield:{influx_creds_section}.token' = None # type: ignore
    influxdb_url: f'opitem:"InfluxDB" opfield:{influx_creds_section}.url' = None # type: ignore
    grafana_url: f'opitem:"Grafana" opfield:dashboard.URL' = None # type: ignore
    unleash_token: f'opitem:"Unleash" opfield:.password' = None # type: ignore
    unleash_url: f'opitem:"Unleash" opfield:default.url' = None # type: ignore
    unleash_app: f'opitem:"Unleash" opfield:default.app_name' = None # type: ignore


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
from tailucas_pylib.device import Device
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

from sqlalchemy.ext.asyncio import AsyncEngine, create_async_engine, AsyncSession
from sqlalchemy.orm import declarative_base, sessionmaker, Session

db_tablespace_path = app_config.get('sqlite', 'tablespace_path')
db_tablespace = path.join(db_tablespace_path, f'{APP_NAME}.db')
dburl: str = f'sqlite+aiosqlite:///{db_tablespace}'
engine: AsyncEngine = create_async_engine(dburl)
async_session: AsyncSession = sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)
Base = declarative_base()


async def get_db():
    async with async_session() as session:
        yield session


from sqlalchemy import Column, Integer, String, JSON, DateTime, Boolean, Text, Float

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


URL_WORKER_APP = 'inproc://app-worker'
URL_WORKER_TELEGRAM_BOT = 'inproc://telegram-bot'
URL_WORKER_AUTO_SCHEDULER = 'inproc://auto-scheduler'

CONFIG_AUTO_SCHEDULER='auto-scheduler'

startup_complete = False


# feature flags configuration
features = UnleashClient(
    url=creds.unleash_url,
    app_name=creds.unleash_app,
    custom_headers={'Authorization': creds.unleash_token})
features.initialize_client()


class GeneralConfig(Base):
    __tablename__ = 'general_config'
    id = Column(Integer, primary_key=True)
    config_key = Column(String(50), index=True)
    config_value = Column(Text)

    def __init__(self, config_key, config_value):
        self.config_key = config_key
        self.config_value = config_value


class Heartbeat(Base):
    __tablename__ = 'heartbeat'
    id = Column(Integer, primary_key=True)
    dt = Column(DateTime)
    ts = Column(Float)

    def __init__(self, dt, ts):
        self.dt = dt
        self.ts = ts


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
    trigger_topic = Column(String(100))
    trigger_interval = Column(Integer)
    device_enabled = Column(Boolean)
    auto_schedule = Column(Boolean)
    auto_schedule_enable = Column(String(5))
    auto_schedule_disable = Column(String(5))
    links = relationship('OutputLink', backref='output_config', cascade='all, delete-orphan', lazy='dynamic')

    def __init__(self, device_key, device_type, device_label, device_params, trigger_topic, trigger_interval, device_enabled, auto_schedule, auto_schedule_enable, auto_schedule_disable):
        self.device_key = device_key
        self.device_type = device_type
        self.device_label = device_label
        self.device_params = device_params
        self.trigger_topic = trigger_topic
        self.trigger_interval = trigger_interval
        self.device_enabled = device_enabled
        self.auto_schedule = auto_schedule
        self.auto_schedule_enable = auto_schedule_enable
        self.auto_schedule_disable = auto_schedule_disable

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
                    device_params=None,
                    trigger_topic=None,
                    trigger_interval=None,
                    device_enabled=None,
                    auto_schedule=None,
                    auto_schedule_enable=None,
                    auto_schedule_disable=None))
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
                        'type': 'Button'
                    }
                ]
                zmq_socket.send_pyobj({
                    device_name: {
                        'active_devices': active_devices,
                        'inputs': active_devices,
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
            # FIXME
            log.warning(f'No handler to send IOT message: {iot_message}')
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
        if 'device_id' in request.form:
            saved_device_id = request.form['device_id']
            device_config = InputConfig.query.filter_by(device_key=saved_device_id).first()
            if device_config is None:
                device_config = OutputConfig.query.filter_by(device_key=saved_device_id).first()
            # sync up the device model for page load
            auto_schedule_enabled = bool(request.form.get('auto_schedule'))
            auto_schedule_enable = request.form['auto_schedule_enable']
            auto_schedule_disable = request.form['auto_schedule_disable']
            log.info(f'Saving auto-schedule configuration for {saved_device_id} (enabled? {auto_schedule_enabled} enable at {auto_schedule_enable}, disable at {auto_schedule_disable})')
            device_config.auto_schedule = auto_schedule_enabled
            device_config.auto_schedule_enable = auto_schedule_enable
            device_config.auto_schedule_disable = auto_schedule_disable
            db.session.add(device_config)
            db.session.commit()
            # invalidate remote cache
            invalidate_remote_config(device_key=device_config.device_key)
            # open IPC
            with exception_handler(connect_url=URL_WORKER_AUTO_SCHEDULER, and_raise=False) as zmq_socket:
                zmq_socket.send_pyobj((device_config.device_key, str(device_config), device_config.auto_schedule, device_config.auto_schedule_enable, device_config.auto_schedule_disable))
        if 'general_config' in request.form:
            autoscheduler_enabled = bool(request.form.get('autoscheduler_enabled'))
            config = GeneralConfig.query.filter_by(config_key=CONFIG_AUTO_SCHEDULER).first()
            if config is None:
                config = GeneralConfig(config_key=CONFIG_AUTO_SCHEDULER, config_value=autoscheduler_enabled)
            else:
                config.config_value = autoscheduler_enabled
            db.session.add(config)
            db.session.commit()
    devices = []
    devices.extend(InputConfig.query.order_by(InputConfig.device_key).all())
    devices.extend(OutputConfig.query.order_by(OutputConfig.device_key).all())
    config_autoscheduler = GeneralConfig.query.filter_by(config_key=CONFIG_AUTO_SCHEDULER).first()
    config_autoscheduler_enabled = False
    if config_autoscheduler:
        config_autoscheduler_enabled = bool(int(config_autoscheduler.config_value))
    InputConfig.query.filter_by(id=saved_device_id).first()
    return render_template('config.html',
                           devices=devices,
                           saved_device_id=saved_device_id,
                           config_autoscheduler_enabled=config_autoscheduler_enabled)


@api_app.get("/api/input_configs")
async def api_input_configs(device_key: str, adb: AsyncSession = Depends(get_db)):
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
        db_input_config = InputConfig.query.filter_by(id=saved_device_id).first()
        invalidate_remote_config(device_key=db_input_config.device_key)
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
        trigger_topic = request.form['trigger_topic'].strip()
        if len(trigger_topic) > 0:
            output_config.trigger_topic = trigger_topic
        else:
            output_config.trigger_topic = None
        if len(request.form['trigger_interval']) > 0:
            output_config.trigger_interval = int(request.form['trigger_interval'])
        else:
            output_config.trigger_interval = None
        if output_config.device_enabled in request.form.getlist('device_enabled'):
            output_config.device_enabled = True
        else:
            output_config.device_enabled = None
        db.session.add(output_config)
        db.session.commit()
        # invalidate remote cache
        invalidate_remote_config(device_key=output_config.device_key)
    outputs = OutputConfig.query.order_by(OutputConfig.device_key).all()
    return render_template('output_config.html',
                           outputs=outputs,
                           saved_device_id=saved_device_id)


async def telegram_bot_echo(update: Update, context: TelegramContextTypes.DEFAULT_TYPE) -> None:
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
        await update.message.reply_markdown(text=bot_response)
    except NetworkError:
        log.warning('bot handler', exc_info=True)
    except Exception:
        log.exception('bot handler')
        capture_exception()


async def telegram_bot_cmd(update: Update, context: TelegramContextTypes.DEFAULT_TYPE) -> None:
    try:
        authorized_users = app_config.get('telegram', 'authorized_users').split(',')
        if str(update.effective_user.id) not in authorized_users:
            log.warning('Unauthorized message {}'.format(str(update)))
            return

        log.info('Telegram Bot {} got command {} with args {} (chat ID: {}).'.format(context.bot.username,
                                                                                     update.effective_message.text,
                                                                                     str(context.args),
                                                                                     update.effective_message.chat_id))
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
        log.warning(f'Unable to call {api_method} API at {api_server} to invalidate configuration for {device_key}: {e!s}')


class BotMessage(BaseModel):
    device_label: str
    message: str
    url: str | None = None
    image: bytes | None = None
    image_timestamp: str | None = None
    timestamp: int | None = None

    def __str__(self):
        return self.message


class EventProcessor(AppThread):

    def __init__(self):
        AppThread.__init__(self, name=self.__class__.__name__)

        self.inputs = {}
        self.outputs = {}

        self._input_trigger_history = {}
        self._input_active_history = {}

        self._input_origin = {}
        self._output_origin = {}

        self._inputs_by_origin = {}
        self._outputs_by_origin = {}

        self._max_message_validity_seconds = None

        self._device_event_lru = lrucache(100)

        # TODO
        #self.event_log = zmq_socket(zmq.PUSH)

        self.bot = zmq_socket(zmq.PUSH)

        self._metric_last_posted_meter_value = 0
        self._metric_meter_value_accumulator = 0
        self._metric_last_posted_register_value = 0

        self.influxdb = None
        self.influxdb_rw = None
        self.influxdb_ro = None
        self.influxdb_bucket = app_config.get('influxdb', 'bucket')

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
            log.warning(f'{device_key} already known from {device_origin[device_key]} but also sent by {event_origin}.')
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
        if features.is_enabled("telegram-bot"):
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
        device_configs = []
        device_configs.extend(InputConfig.query.all())
        device_configs.extend(OutputConfig.query.all())
        # load auto-scheduler
        with exception_handler(connect_url=URL_WORKER_AUTO_SCHEDULER, and_raise=False) as zmq_socket:
            for device_config in device_configs:
                if device_config.auto_schedule:
                    zmq_socket.send_pyobj((
                        device_config.device_key,
                        str(device_config),
                        device_config.auto_schedule,
                        device_config.auto_schedule_enable,
                        device_config.auto_schedule_disable))
        # informational notifications
        # TODO: move to UI configuration
        self.notify_not_before_time = make_timestamp(timestamp=app_config.get('info_notify', 'not_before_time'))
        self.notify_not_after_time = make_timestamp(timestamp=app_config.get('info_notify', 'not_after_time'))
        # message validity
        self._max_message_validity_seconds = int(app_config.get('app', 'max_message_validity_seconds'))
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
            while not threads.shutting_down:
                # write database heartbeat
                heartbeat = Heartbeat.query.first()
                now = make_timestamp()
                heartbeat.dt = now
                heartbeat.ts = now.timestamp()
                db.session.add(heartbeat)
                db.session.commit()
                # process the next event
                event = app_socket.recv_pyobj()
                if not isinstance(event, dict):
                    log.info('Malformed event; expecting dictionary.')
                    continue
                if 'sms' in event:
                    sms_message = event['sms']
                    if features.is_enabled("telegram-bot"):
                        log.debug(f'Sending payload to bot {event.keys()!s}...')
                        self.bot.send_pyobj(sms_message)
                        log.debug(f'Sent payload to bot...')
                    else:
                        log.warning(f'Not sending message to Telegram bot disabled with feature flag: {sms_message}')
                    continue
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
                    elif 'auto-scheduler' == event_origin:
                        device_key = event_data['device_key']
                        device_label = event_data['device_label']
                        device_enable = event_data['device_state']
                        log.info(f'Auto-scheduler updating device {device_label}; enable: {device_enable}')
                        device_config = InputConfig.query.filter_by(device_key=device_key).first()
                        if device_config is None:
                            device_config = OutputConfig.query.filter_by(device_key=device_key).first()
                        device_config.device_enabled = device_enable
                        db.session.add(device_config)
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
                        input_enable = None
                        output_enable = None
                        if bot_command_base.startswith('/outputon'):
                            output_enable = True
                        elif bot_command_base.startswith('/outputoff'):
                            output_enable = False
                        elif bot_command_base.startswith('/inputon'):
                            input_enable = True
                        elif bot_command_base.startswith('/inputoff'):
                            input_enable = False
                        state = 'enable'
                        if input_enable == False or output_enable == False:
                            state = 'disable'
                        bot_reply = f'No devices to {state}.'
                        device_configs = list()
                        if bot_command_args:
                            for bot_command_arg in bot_command_args:
                                device_config = list()
                                # https://stackoverflow.com/questions/3325467/sqlalchemy-equivalent-to-sql-like-statement
                                sql_search = f'%{bot_command_arg}%'
                                if input_enable is not None:
                                    device_config = InputConfig.query.filter(
                                        or_(
                                            InputConfig.device_key.like(sql_search),
                                            InputConfig.device_label.like(sql_search),
                                            InputConfig.group_name.like(sql_search)
                                        )).order_by(InputConfig.device_key).all()
                                elif output_enable is not None:
                                    device_config = OutputConfig.query.filter(
                                        or_(
                                            OutputConfig.device_key.like(sql_search),
                                            OutputConfig.device_label.like(sql_search)
                                        )).order_by(OutputConfig.device_key).all()
                                # collect all configurations matched
                                log.info(f'{state.title()} {len(device_config)} devices matching "{bot_command_arg}".')
                                if device_config:
                                    device_configs.extend(device_config)
                        else:
                            device_config = list()
                            if input_enable is not None:
                                # wildcard action is constrained to devices where auto-scheduling is enabled
                                device_config = InputConfig.query.filter(InputConfig.auto_schedule.isnot(None)).order_by(InputConfig.device_key).all()
                                log.info(f'{state.title()} {len(device_config)} devices with auto-schedule not null.')
                            elif output_enable is not None:
                                device_config = OutputConfig.query.order_by(OutputConfig.device_key).all()
                            if len(device_config) > 0:
                                log.info(f'{state.title()} {len(device_config)} devices...')
                                device_configs.extend(device_config)
                        # process all collected inputs
                        devices_updated = []
                        device_enable = input_enable
                        if device_enable is None:
                            device_enable = output_enable
                        if len(device_configs) > 0:
                            for dc in device_configs:
                                if dc.device_enabled != device_enable:
                                    devices_updated.append(dc.device_key)
                                    if input_enable is not None:
                                        log.info(f'{state.title()} {dc.device_key} (group {dc.group_name})')
                                    else:
                                        log.info(f'{state.title()} {dc.device_key}')
                                    dc.device_enabled = device_enable
                                    # update the database
                                    db.session.add(dc)
                                    # update auto-scheduled inputs
                                    if input_enable is not None and dc.auto_schedule is not None:
                                        # update the auto-scheduler task
                                        with exception_handler(connect_url=URL_WORKER_AUTO_SCHEDULER, and_raise=False) as zmq_socket:
                                            if device_enable:
                                                # restore auto-schedule actions
                                                zmq_socket.send_pyobj((dc.device_key, str(dc), dc.auto_schedule, dc.auto_schedule_enable, dc.auto_schedule_disable))
                                            else:
                                                # disable runtime auto-scheduling actions
                                                zmq_socket.send_pyobj((dc.device_key, str(dc), None, None, None))
                            if len(devices_updated) > 0:
                                db.session.commit()
                                for device_key in devices_updated:
                                    invalidate_remote_config(device_key=device_key)
                            bot_reply = f'{len(devices_updated)} devices changed to {state}.'
                        else:
                            log.warning(f'No devices matched to {state}.')
                        log.info(bot_reply)
                        if features.is_enabled("telegram-bot"):
                            self.bot.send_pyobj(BotMessage(device_label='notification', message=bot_reply).model_dump())
                        else:
                            log.warning(f'Not sending message to Telegram bot disabled with feature flag: {bot_reply}')
                        # stop processing
                        if not bot_command_base.startswith('/report'):
                            # no further processing needed after enable/disable
                            continue
        try_close(self.bot)


class TBot(AppThread, Closable):

    def __init__(self, chat_id, sns_fallback=False):
        AppThread.__init__(self, name=self.__class__.__name__)
        Closable.__init__(self, connect_url=URL_WORKER_TELEGRAM_BOT, is_async=True)
        self.chat_id = chat_id
        self.sns_fallback = sns_fallback
        self._shutdown = False

    def build_device_message(timestamp, input_device: Device) -> BotMessage:
        device_label = input_device.device_label
        if device_label is None:
            device_label = input_device.device_key
        event_detail = ''
        if input_device.event_detail:
            event_detail = f' {input_device.event_detail}'
        # include a timestamp in this SMS message
        message = '{}{} ({}:{})'.format(
            device_label,
            event_detail,
            timestamp.hour,
            str(timestamp.minute).zfill(2))
        image_data = None
        if input_device.image:
            image_data = input_device.image
        image_timestamp = None
        if input_device.image_timestamp:
            image_timestamp = input_device.image_timestamp
        return BotMessage(device_label=device_label, message=message, url=input_device.storage_url, image=image_data, image_timestamp=image_timestamp)

    def include_image(message):
        if not app_config.getboolean('telegram', 'image_send_only_with_people'):
            return True
        if 'person' in message:
            return True
        return False

    async def tbot_run(t_app: TelegramApp, zmq_socket, chat_id):
        poller = Poller()
        poller.register(zmq_socket, zmq.POLLIN)
        pending_by_label = OrderedDict()
        log.info(f'Waiting for events to forward to Telegram bot on chat ID {chat_id}...')
        call_again_timestamp = 0
        min_send_interval = app_config.getint('telegram', 'min_send_interval')
        last_sent = 0
        while not threads.shutting_down and features.is_enabled("telegram-bot"):
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
                if isinstance(event, dict):
                    input_device: Device = None
                    output_device: Device = None
                    message = None
                    try:
                        if 'active_input' in event.keys():
                            input_device = Device(**event['active_input'])
                            log.debug(f'Input device for message: {input_device!s}')
                        elif 'output_triggered' in event.keys():
                            output_device = Device(**event['output_triggered'])
                            log.debug(f'Output device for message: {output_device!s}')
                        else:
                            message = BotMessage(**event)
                    except Exception as e:
                        log.warning('Bot message unpack problem {e!s}', exc_info=True)
                        continue
                    timestamp = None
                    if 'timestamp' in event:
                        timestamp = make_timestamp(timestamp=event['timestamp'], as_tz=user_tz)
                    else:
                        log.warning('No timestamp included in event message; using "now"')
                        timestamp = make_timestamp(as_tz=user_tz)
                    log.debug(f'{input_device!s};{output_device!s};{timestamp!s}')
                    # build the message
                    if message is None and input_device is not None:
                        message = TBot.build_device_message(timestamp=timestamp, input_device=input_device)
                    # always queue the message
                    message.timestamp = make_unix_timestamp(timestamp=timestamp)
                    try:
                        queued = pending_by_label[message.device_label]
                    except KeyError:
                        queued = deque()
                        pending_by_label[message.device_label] = queued
                    log.info(f'Queueing message about {message.device_label} ({message.timestamp}) ({len(queued)} devices queued)...')
                    queued.append(message)
                # rate-limit the send
                # https://core.telegram.org/bots/faq#my-bot-is-hitting-limits-how-do-i-avoid-this
                if (now < call_again_timestamp):
                    log.warning(f'Enforced rate limiting message queue (of {len(pending_by_label)} devices). Telegram asked for {call_again_timestamp - now}s backoff.')
                    continue
                time_since_sent = now - last_sent
                if time_since_sent < min_send_interval:
                    log.warning(f'Elective rate limiting message queue (of {len(pending_by_label)} devices). Time since last send is {time_since_sent}s, min interval is {min_send_interval}s.')
                    continue
                # dequeue the message
                # since we favour brevity over temporal precision, access the event
                # dictionary by rough order of entry and take the latest event for a
                # label, except for images in which all images with features detected
                # should be batched and sent.
                pending = None
                device_label = None
                while not pending:
                    try:
                        device_label, pending = pending_by_label.popitem(last=False)
                    except KeyError:
                        break
                if not pending:
                    log.error('No queued messages for any device.')
                    continue
                message = pending.popleft()
                image_batch = []
                # other messages to dedupe
                while True:
                    log.info(f'Now processing message about {message.device_label} ({message.timestamp}) ({len(pending)} queued)...')
                    # keep all image data as configured
                    if message.image:
                        if TBot.include_image(message=str(message)):
                            if len(image_batch) < MediaGroupLimit.MAX_MEDIA_LENGTH:
                                caption_entities = None
                                if message.url:
                                    caption_entities = [
                                        MessageEntity(
                                            type=MessageEntity.TEXT_LINK,
                                            offset=0,
                                            length=len(device_label),
                                            url=message.url)
                                    ]
                                log.info(f'Batching image about {device_label} (t={message.timestamp}/it={message.image_timestamp}) for {chat_id!s} with caption "{message!s}". Batch size is {len(image_batch)}.')
                                image_batch.append(InputMediaPhoto(
                                    media=BytesIO(message.image),
                                    caption=str(message),
                                    caption_entities=caption_entities))
                            else:
                                # enough is enough, re-enqueue the remainder
                                log.info(f'Re-enqueing {len(pending)} remaining events for {device_label} because image batch to send is now {len(image_batch)} items.')
                                pending_by_label[device_label] = pending
                                break
                        else:
                            log.info(f'Filtering out image message about {message.device_label} ({message.timestamp}) ({len(pending)} queued).')
                    try:
                        # attempt to fetch a newer image
                        message = pending.popleft()
                        log.warning(f'Fetched newer pending message about {message.device_label} ({message.timestamp}).')
                    except IndexError:
                        # message remains set to the current
                        break
                # send the message
                try:
                    if len(image_batch) > 0:
                        log.info(f'Sending image group to {chat_id!s} containing {len(image_batch)} images.')
                        await t_app.bot.send_media_group(chat_id=chat_id, media=image_batch, read_timeout=300, write_timeout=300, connect_timeout=300, pool_timeout=300)
                    if not message.image:
                        log.info(f'Sending non-image message about {device_label} ({message.timestamp}) to {chat_id!s} with caption "{message!s}"')
                        await t_app.bot.send_message(chat_id=chat_id,
                                                text=str(message),
                                                parse_mode='Markdown')
                except RetryAfter as e:
                    call_again_timestamp = now + e.retry_after
                    log.warning(f'Telegram asks to call again in {e.retry_after}s. Deferring calls until {call_again_timestamp}.')
                    continue
                except TimedOut as e:
                    log.warning(f'Telegram send timeout: {e.message}.')
                    continue
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
        telegram_application.add_handler(TelegramCommandHandler(command='inputon', callback=telegram_bot_cmd))
        telegram_application.add_handler(TelegramCommandHandler(command='inputoff', callback=telegram_bot_cmd))
        telegram_application.add_handler(TelegramCommandHandler(command='outputon', callback=telegram_bot_cmd))
        telegram_application.add_handler(TelegramCommandHandler(command='outputoff', callback=telegram_bot_cmd))
        telegram_application.add_handler(TelegramMessageHandler(filters.TEXT & ~filters.COMMAND, telegram_bot_echo))
        telegram_application.add_error_handler(callback=telegram_error_handler)
        self.t_app = telegram_application
        log.info('Registering coroutine for ZMQ-Telegram messages...')
        self.get_socket()
        outcome = asyncio.run_coroutine_threadsafe(
            TBot.tbot_run(
                t_app=self.t_app,
                zmq_socket=self.socket,
                chat_id=self.chat_id),
            loop)
        log.info('Starting Telegram application...')
        self.t_app.run_polling(stop_signals=None)
        log.info('Waiting for coroutine exceptions...')
        exc = outcome.exception()
        if exc is not None:
            log.warning('Completed with exception.', exc)
        log.info('Closing event loop...')
        loop.close()
        self.shutdown()
        log.info('Shutdown complete.')

    def shutdown(self):
        # TODO: shut down Telegram bot from external
        # event loop if stop_signals=None
        if not self._shutdown:
            log.info('Closing ZMQ socket...')
            self.close()
            self._shutdown = True


class AutoScheduler(AppThread):

    def __init__(self):
        AppThread.__init__(self, name=self.__class__.__name__)

    @property
    def is_enabled(self):
        config_autoscheduler_enabled = False
        with flask_app.app_context():
            config_autoscheduler = GeneralConfig.query.filter_by(config_key=CONFIG_AUTO_SCHEDULER).first()
            if config_autoscheduler:
                config_autoscheduler_enabled = bool(int(config_autoscheduler.config_value))
        return config_autoscheduler_enabled

    def update_device(device_key, device_label, device_state):
        log.info('Scheduler triggered. {} to enabled={}'.format(device_label, device_state))
        with exception_handler(connect_url=URL_WORKER_APP, socket_type=zmq.PUSH, and_raise=False, shutdown_on_error=False) as zmq_socket:
            zmq_socket.send_pyobj({
                'auto-scheduler': {
                    'device_key': device_key,
                    'device_label': device_label,
                    'device_state': device_state
                }})

    def _schedule(self, device_key, device_label, schedule_time, device_state):
        log.info(f'Setting auto-schedule for {device_label}: enable? {device_state} at {schedule_time}.')
        schedule.every().day.at(schedule_time).do(AutoScheduler.update_device, device_key, device_label, device_state).tag(device_key)

    # noinspection PyBroadException
    def run(self):
        if not self.is_enabled:
            log.warning(f'Auto-scheduler is not enabled; scheduled changes will not run.')
        with exception_handler(connect_url=URL_WORKER_AUTO_SCHEDULER, socket_type=zmq.PULL, and_raise=False, shutdown_on_error=True) as zmq_socket:
            while not threads.shutting_down:
                next_message = False
                # trigger any scheduled work
                if self.is_enabled:
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
                    log.info(f'Removing auto-schedule for {device_label}.')
                    schedule.clear(device_key)
                    if auto_schedule:
                        log.info(f'Resetting auto-schedule for {device_label} to disable at {auto_schedule_disable} and enable at {auto_schedule_enable}.')
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
            self.server.should_exit = True
            self.server.force_exit = True


def main():
    log.setLevel(logging.INFO)
    # ensure proper signal handling; must be main thread
    signal_handler = SignalHandler()
    if not threads.shutting_down:
        log.info('Creating application threads...')
        # bind listeners first
        mq_server_address=app_config.get('rabbitmq', 'server_address').split(',')
        mq_exchange_name=app_config.get('rabbitmq', 'mq_exchange')
        mq_listener_sms = ZMQListener(
            zmq_url=URL_WORKER_APP,
            mq_server_address=mq_server_address,
            mq_exchange_name=f'{mq_exchange_name}_control',
            mq_topic_filter='event.trigger.sms',
            mq_exchange_type='direct')
        auto_scheduler = AutoScheduler()
        event_processor = EventProcessor()
        # configure Telegram bot
        telegram_bot = None
        if features.is_enabled("telegram-bot"):
            telegram_bot = TBot(
                chat_id=app_config.getint('telegram', 'chat_room_id'),
                sns_fallback=app_config.getboolean('telegram', 'sns_fallback_enabled'))
        else:
            log.warning(f'Not running Telegram bot client due to feature flag.')
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
            if telegram_bot:
                telegram_bot.start()
            # start the connectors
            auto_scheduler.start()
            mq_listener_sms.start()
            # HTTP APIs
            server.start()
            # get supporting services going
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
            if telegram_bot:
                log.info(message.format('Telegram Bot'))
                telegram_bot.shutdown()
            log.info(message.format('Rabbit MQ listener bridge'))
            mq_listener_sms.stop()
            zmq_term()
        bye()


if __name__ == "__main__":
    main()