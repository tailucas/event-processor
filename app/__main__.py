#!/usr/bin/env python

import asyncio
from collections import OrderedDict, deque
from contextlib import suppress
from functools import wraps
from io import BytesIO
from os import path
import threading
from threading import Thread
import time

from fastapi import Depends, FastAPI, HTTPException, Request, status
from fastapi.middleware.wsgi import WSGIMiddleware
from fastapi.responses import RedirectResponse
from flask import Flask, flash, redirect, render_template, request, url_for
from flask_compress import Compress
from flask_login import LoginManager, UserMixin, current_user, login_required, login_user, logout_user
from flask_sqlalchemy import SQLAlchemy
from httpx import ConnectError
from pydantic import BaseModel, ConfigDict
from pylru import lrucache
from pytz import timezone
import requests
from requests.exceptions import ConnectionError
import schedule
from schedule import ScheduleValueError
import sentry_sdk
from sentry_sdk import capture_exception
from sentry_sdk.integrations.asyncio import AsyncioIntegration
from sentry_sdk.integrations.flask import FlaskIntegration
from sentry_sdk.integrations.logging import ignore_logger
from sentry_sdk.integrations.sys_exit import SysExitIntegration
from sentry_sdk.integrations.threading import ThreadingIntegration
from sqlalchemy import Boolean, Column, DateTime, Float, ForeignKey, Integer, String, Text, UniqueConstraint, event, or_
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, create_async_engine
from sqlalchemy.future import select
from sqlalchemy.orm import declarative_base, relationship, sessionmaker
from telegram import InputMediaPhoto, MessageEntity, Update
from telegram.constants import MediaGroupLimit
from telegram.error import NetworkError, RetryAfter, TimedOut
from telegram.ext import (
    Application as TelegramApp,
)
from telegram.ext import (
    CommandHandler as TelegramCommandHandler,
)
from telegram.ext import (
    ContextTypes as TelegramContextTypes,
)
from telegram.ext import (
    MessageHandler as TelegramMessageHandler,
)
from telegram.ext import (
    filters,
)
import uvicorn
import zmq
import zmq.asyncio
from zmq.asyncio import Poller
from zmq.error import ZMQError

from tailucas_pylib import APP_NAME, DEVICE_NAME, app_config, log, threads
from tailucas_pylib.app import AppThread
from tailucas_pylib.creds import Creds
from tailucas_pylib.datetime import (
    make_iso_timestamp,
    make_timestamp,
    make_unix_timestamp,
)
from tailucas_pylib.device import Device
from tailucas_pylib.flags import is_flag_enabled
from tailucas_pylib.handler import exception_handler
from tailucas_pylib.process import SignalHandler
from tailucas_pylib.rabbit import ZMQListener
from tailucas_pylib.threads import bye, die, thread_nanny
from tailucas_pylib.zmq import URL_WORKER_APP, Closable, try_close, zmq_socket, zmq_term

# Reduce Sentry noise
ignore_logger("telegram.ext.Updater")
ignore_logger("telegram.ext._updater")
ignore_logger("asyncio")


db_tablespace_path = app_config.get("sqlite", "tablespace_path")
db_tablespace = path.join(db_tablespace_path, f"{APP_NAME}.db")
db_timeout = app_config.getint("sqlite", "timeout_seconds", fallback=30)
dburl: str = f"sqlite+aiosqlite:///{db_tablespace}?timeout={db_timeout}"
engine: AsyncEngine = create_async_engine(dburl)
async_session: AsyncSession = sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)
Base = declarative_base()


@event.listens_for(engine.sync_engine, "connect")
def set_sqlite_pragma_async(dbapi_connection, connection_record):
    cursor = dbapi_connection.cursor()
    cursor.execute("PRAGMA journal_mode=WAL")
    cursor.close()


async def get_db():
    async with async_session() as session:
        yield session


creds = None
sentry_dsn = None

user_tz = timezone(app_config.get("app", "user_tz"))
flask_app = Flask(APP_NAME)
flask_app.config["SQLALCHEMY_DATABASE_URI"] = f"sqlite:///{db_tablespace}"
flask_app.config["SQLALCHEMY_TRACK_MODIFICATIONS"] = False
flask_app.config["SQLALCHEMY_ENGINE_OPTIONS"] = {"connect_args": {"timeout": db_timeout}}
db = SQLAlchemy(app=flask_app, model_class=Base)
# set up flask application
flask_app.jinja_env.add_extension("jinja2.ext.loopcontrols")


def is_list(value):
    return isinstance(value, list)


flask_app.jinja_env.filters.update(
    {
        "is_list": is_list,
    }
)
flask_app.debug = app_config.getboolean("flask", "debug")

login_manager = LoginManager()
login_manager.login_view = "login"
login_manager.init_app(flask_app)

# enable compression
Compress().init_app(flask_app)
flask_ctx = flask_app.app_context()
flask_ctx.push()


@event.listens_for(db.engine, "connect")
def set_sqlite_pragma_sync(dbapi_connection, connection_record):
    cursor = dbapi_connection.cursor()
    cursor.execute("PRAGMA journal_mode=WAL")
    cursor.close()


api_app = FastAPI()
api_app.state.startup_complete = False
api_app.mount("/admin", WSGIMiddleware(flask_app))

# Custom exception handler for validation errors
from fastapi.exceptions import RequestValidationError  # noqa: E402
from fastapi.responses import JSONResponse  # noqa: E402


@api_app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    log.error("Request validation failed", extra={"url": str(request.url), "errors": exc.errors()})
    log.error("Request headers", extra={"headers": str(request.headers)})
    log.error("Request body", extra={"body": repr(await request.body())})
    return JSONResponse(
        status_code=422,
        content={"detail": exc.errors()},
    )


URL_WORKER_TELEGRAM_BOT = "inproc://telegram-bot"
URL_WORKER_AUTO_SCHEDULER = "inproc://auto-scheduler"

CONFIG_AUTO_SCHEDULER = "auto-scheduler"


class GeneralConfig(Base):
    __tablename__ = "general_config"
    id = Column(Integer, primary_key=True)
    config_key = Column(String(50), index=True)
    config_value = Column(Text)

    def __init__(self, config_key, config_value):
        self.config_key = config_key
        self.config_value = config_value


class Heartbeat(Base):
    __tablename__ = "heartbeat"
    id = Column(Integer, primary_key=True)
    dt = Column(DateTime)
    ts = Column(Float)

    def __init__(self, dt, ts):
        self.dt = dt
        self.ts = ts


class EventLog(Base):
    __tablename__ = "event_log"
    id = Column(Integer, primary_key=True)
    input_device = Column(String(100), index=True)
    output_device = Column(String(100), index=True)
    timestamp = Column(DateTime, index=True)

    def __init__(self, input_device, output_device, timestamp):
        self.input_device = input_device
        self.output_device = output_device
        self.timestamp = timestamp


class InputConfig(Base):
    __tablename__ = "input_config"
    id = Column(Integer, primary_key=True, autoincrement=True)
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
    activation_escalation = Column(Integer)
    group_name = Column(String(100), index=True)
    info_notify = Column(Boolean)
    links_il = relationship(
        "InputLink",
        backref="input_config",
        cascade="all, delete-orphan",
        lazy="dynamic",
    )
    links_ol = relationship(
        "OutputLink",
        backref="input_config",
        cascade="all, delete-orphan",
        lazy="dynamic",
    )
    links_mc = relationship(
        "MeterConfig",
        backref="input_config",
        cascade="all, delete-orphan",
        lazy="dynamic",
    )

    def __init__(
        self,
        device_key,
        device_type,
        device_label,
        customized,
        auto_schedule,
        auto_schedule_enable,
        auto_schedule_disable,
        device_enabled,
        trigger_latch_duration,
        multi_trigger_rate,
        multi_trigger_interval,
        activation_escalation,
        group_name,
        info_notify,
    ):
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
        self.activation_escalation = activation_escalation
        self.group_name = group_name
        self.info_notify = info_notify

    def as_dict(self):
        return {c.name: getattr(self, c.name) for c in self.__table__.columns}

    def __str__(self):
        if self.device_label:
            return self.device_label
        return self.device_key


class MeterConfig(Base):
    __tablename__ = "meter_config"
    id = Column(Integer, primary_key=True, autoincrement=True)
    input_device_id = Column(Integer, ForeignKey("input_config.id"), index=True, nullable=False)
    meter_value = Column(Integer, default=0, nullable=False)
    register_value = Column(Integer, default=0, nullable=False)
    meter_reading = Column(String, default="0", nullable=False)
    meter_iot_topic = Column(String(100), nullable=False)
    meter_low_limit = Column(Integer)
    meter_high_limit = Column(Integer)
    meter_reset_value = Column(Integer)
    meter_reset_additive = Column(Boolean)
    meter_reading_unit = Column(String(10))
    meter_reading_unit_factor = Column(Integer)
    meter_reading_unit_precision = Column(Integer)

    def __init__(
        self,
        input_device_id,
        meter_iot_topic,
        meter_low_limit,
        meter_high_limit,
        meter_reset_value,
        meter_reset_additive,
        meter_reading_unit,
        meter_reading_unit_factor,
        meter_reading_unit_precision,
    ):
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
    __tablename__ = "output_config"
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
    links = relationship(
        "OutputLink",
        backref="output_config",
        cascade="all, delete-orphan",
        lazy="dynamic",
    )

    def __init__(
        self,
        device_key,
        device_type,
        device_label,
        device_params,
        trigger_topic,
        trigger_interval,
        device_enabled,
        auto_schedule,
        auto_schedule_enable,
        auto_schedule_disable,
    ):
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
    __tablename__ = "input_link"
    id = Column(Integer, primary_key=True, autoincrement=True)
    input_device_id = Column(Integer, ForeignKey("input_config.id"), index=True, nullable=False)
    linked_device_id = Column(Integer, nullable=False)
    UniqueConstraint("input_device_id", "linked_device_id", name="unique_link")

    def __init__(self, input_device_id, linked_device_id):
        self.input_device_id = input_device_id
        self.linked_device_id = linked_device_id

    def as_dict(self):
        return {c.name: getattr(self, c.name) for c in self.__table__.columns}


class OutputLink(Base):
    __tablename__ = "output_link"
    id = Column(Integer, primary_key=True, autoincrement=True)
    input_device_id = Column(Integer, ForeignKey("input_config.id"), index=True, nullable=False)
    output_device_id = Column(Integer, ForeignKey("output_config.id"), nullable=False)
    UniqueConstraint("input_device_id", "output_device_id", name="unique_link")

    def __init__(self, input_device_id, output_device_id):
        self.input_device_id = input_device_id
        self.output_device_id = output_device_id

    def as_dict(self):
        return {c.name: getattr(self, c.name) for c in self.__table__.columns}


class InputConfigWrapper:
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
            self._device_type = "group"
            # enable group if any input is enable
            if input_config.device_enabled is not None:
                self._device_enabled |= input_config.device_enabled
            if self._input_config is None:
                self._input_config = list()
            self._input_config.append(input_config)
        else:
            self._input_config = input_config


class InputConfigCollection:
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
        if input_device_key not in self._input_configs:
            ic_wrapper = InputConfigWrapper()
            self._input_configs[input_device_key] = ic_wrapper
        else:
            ic_wrapper = self._input_configs[input_device_key]
        ic_wrapper.input_config = input_config


def update_meter_config(input_device_key, meter_config, register_value, meter_value=None):
    if register_value < 0:
        log.debug(
            "Resetting negative meter register value to 0",
            extra={"device_key": input_device_key, "register_value": register_value},
        )
        register_value = 0
    meter_reading_unit = " " + meter_config.meter_reading_unit
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
async def api_running(request: Request):
    return request.app.state.startup_complete


class DeviceInfo(BaseModel):
    model_config = ConfigDict(extra="ignore")

    device_key: str
    device_label: str | None = None
    device_type: str | None = None
    group_name: str | None = None
    location: str | None = None
    is_input: bool
    is_output: bool


@api_app.post("/api/device_info")
async def api_device_info(di: DeviceInfo):
    log.debug(
        "Device info request received",
        extra={"device_key": di.device_key, "is_input": di.is_input, "is_output": di.is_output},
    )
    try:
        with exception_handler(connect_url=URL_WORKER_APP, and_raise=False, shutdown_on_error=False) as zmq_socket:
            di_model = di.model_dump()
            if di.is_input:
                zmq_socket.send_pyobj({"device_info_input": di_model})
            if di.is_output:
                zmq_socket.send_pyobj({"device_info_output": di_model})
    except ZMQError as e:
        log.warning(
            "Cannot forward device info to event processor",
            extra={"device_key": di.device_key, "is_input": di.is_input, "is_output": di.is_output},
            exc_info=e,
        )
    return "OK"


class SessionUser(BaseModel, UserMixin):
    id: str
    key: str
    name: str


active_users = dict()


def auth_enabled(func):
    @wraps(func)
    def decorated_view(*args, **kwargs):
        if is_flag_enabled("console-auth"):
            flask_app.config.pop("LOGIN_DISABLED", None)
        else:
            flask_app.config["LOGIN_DISABLED"] = True
        return func(*args, **kwargs)

    return decorated_view


def authz_required(action, resource):
    def authz_decorator(func):
        @wraps(func)
        def flask_wrapper(*args, **kwargs):
            if flask_app.config.get("LOGIN_DISABLED"):
                log.warning("Login disabled. Skipping authorization check.")
                return func(*args, **kwargs)
            user_details = "Unauthenticated user"
            user_fields = {"user_name": None, "user_key": None}
            flash_alert = "danger"
            permission_details = f"{action} {resource}"
            if current_user.is_authenticated:
                user_details = f"User {current_user.name} ({current_user.key})"
                user_fields = {"user_name": current_user.name, "user_key": current_user.key}
                log.debug("User is authenticated", extra=user_fields)
                # FIXME: use permit.io or other AuthN/Z here
                permitted = True
                if permitted:
                    log.debug("User is authorized", extra={**user_fields, "action": action, "resource": resource})
                    return func(*args, **kwargs)
                else:
                    flash_alert = "warning"
            user_message = f"{user_details} is not authorized to {permission_details}."
            log.debug("User is not authorized", extra={**user_fields, "action": action, "resource": resource})
            flash(message=user_message, category=flash_alert)
            return redirect(url_for("index"))

        return flask_wrapper

    return authz_decorator


@flask_app.route("/login")
def login():
    return render_template("login.html")


@flask_app.route("/logout")
@login_required
def logout():
    logout_user()
    return redirect(url_for("index"))


@flask_app.route("/login", methods=["POST"])
def login_post():
    email = None
    try:
        email = request.form["user_email"]
        password = request.form.get("user_password")
        remember = bool(request.form.get("remember_user"))
        log.info("Login request received", extra={"user_email": email})
        if email == creds.get_creds("Users/user/email") and password == creds.get_creds("Users/user/creds"):
            # FIXME: actually support multiple users
            u = SessionUser(id=email, key=creds.get_creds("Users/user/key"), name=email)
            active_users[u.id] = u
            login_user(user=u, remember=remember)
            log_message = f"Login successful for {email}."
            log.info("Login successful", extra={"user_email": email})
            flash(message=log_message, category="success")
            return redirect(url_for("index"))
    except Exception:
        log.exception("Login failure.")
    log.info("Login failed", extra={"user_email": email})
    flash(message="Access Denied.", category="danger")
    return redirect(url_for("login"))


@login_manager.user_loader
def load_user(user_id):
    if user_id in active_users:
        active_user: SessionUser = active_users[user_id]
        log.info("User is an active user", extra={"user_name": active_user.name, "user_id": user_id})
        return active_user
    log.info("User is not an active user", extra={"user_id": user_id})
    return None


@flask_app.route("/debug-sentry")
def trigger_error():
    _ = 1 / 0


@flask_app.route("/logging")
def debug():
    log.setLevel(request.args.get("level"))
    return "OK"


@flask_app.errorhandler(500)
def internal_server_error(e):
    log.error("Internal server error", exc_info=e)
    last_event_id = capture_exception(error=e)
    log.debug("Sentry captured event", extra={"sentry_event_id": last_event_id})
    return render_template("error.html", sentry_event_id=last_event_id, sentry_dsn=sentry_dsn), 500


@flask_app.route("/", methods=["GET", "POST"])
def index():
    input_configs = InputConfig.query.order_by(InputConfig.device_key).all()
    inputs = InputConfigCollection()
    for input_config in input_configs:
        inputs.input_config = input_config
    if request.method == "POST":
        if "panic_button" in request.form:
            log.info("Panic button pressed.")
            with exception_handler(connect_url=URL_WORKER_APP, and_raise=False) as zmq_socket:
                active_devices = [
                    {
                        "device_key": "App Panic Button",
                        "device_label": "Panic Button",
                        "type": "Button",
                    }
                ]
                zmq_socket.send_pyobj(
                    {
                        DEVICE_NAME: {
                            "active_devices": active_devices,
                            "inputs": active_devices,
                        }
                    }
                )
        elif "meter_reset" in request.form:
            device_key = request.form["meter_reset"]
            input_cfg = InputConfig.query.filter_by(device_label=device_key).first()
            meter_cfg = MeterConfig.query.filter_by(input_device_id=input_cfg.id).first()
            reset_value = 0
            if meter_cfg.meter_reset_value:
                reset_value = meter_cfg.meter_reset_value
            # override with the prompt value if specified
            if "prompt_val" in request.form:
                with suppress(ValueError):
                    reset_value = int(request.form["prompt_val"])
            # pylint: disable=unused-variable
            if meter_cfg.meter_reset_additive:
                iot_message = {"adjust_register": reset_value}
                meter_register = meter_cfg.register_value
                meter_register += reset_value
                # override the reset value
                reset_value = meter_register
            else:
                iot_message = {"set_register": reset_value}
            # update the in memory model
            update_meter_config(
                input_device_key=device_key,
                meter_config=meter_cfg,
                register_value=reset_value,
            )
            # send IoT message
            # FIXME
            log.warning("No handler to send IOT message", extra={"iot_message": iot_message})
        elif "device_key" in request.form:
            device_key = request.form["device_key"]
            input_cfg = inputs.input_config[device_key]
            input_enabled = input_cfg.device_enabled
            # null or false => disabled
            input_enabled = not input_enabled
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
                log.info(
                    "Input device state changed",
                    extra={
                        "device_key": input_cfg.device_key,
                        "group_name": input_cfg.group_name,
                        "enabled": input_cfg.device_enabled,
                    },
                )
                db.session.add(input_cfg)
            db.session.commit()
            for input_cfg in input_cfgs:
                invalidate_remote_config(device_key=input_cfg.device_key)
        else:
            log.error("No action associated with this request", extra={"form": dict(request.form)})
    meters = dict()
    meter_configs = MeterConfig.query.all()
    for meter_config in meter_configs:
        meters[meter_config.input_device_id] = meter_config
    render_timestamp = make_iso_timestamp(timestamp=None, as_tz=user_tz)
    username = None
    if current_user.is_authenticated:
        username = current_user.name
    return render_template(
        "index.html",
        inputs=inputs.input_config,
        meters=meters,
        server_context=DEVICE_NAME,
        render_timestamp=render_timestamp,
        healthchecks_badges=app_config.get("app", "healthchecks_badges").split(","),
        username=username,
    )


@flask_app.route("/metrics", methods=["GET", "POST"])
def show_metrics():
    return redirect(creds.get_creds("Grafana/dashboard/URL"), code=302)


@flask_app.route("/event_log", methods=["GET", "POST"])
def event_log():
    events = {}
    if request.method == "GET":
        events = EventLog.query.order_by(EventLog.timestamp.desc()).limit(100).all()
    return render_template("event_log.html", events=events)


@flask_app.route("/config", methods=["GET", "POST"])
@auth_enabled
@login_required
@authz_required(action="read", resource="Configuration")
@authz_required(action="update", resource="Configuration")
def show_config():
    saved_device_id = None
    if request.method == "POST":
        if "device_id" in request.form:
            saved_device_id = request.form["device_id"]
            device_config = InputConfig.query.filter_by(device_key=saved_device_id).first()
            if device_config is None:
                device_config = OutputConfig.query.filter_by(device_key=saved_device_id).first()
            # sync up the device model for page load
            auto_schedule_enabled = bool(request.form.get("auto_schedule"))
            auto_schedule_enable = request.form["auto_schedule_enable"]
            auto_schedule_disable = request.form["auto_schedule_disable"]
            log.info(
                "Saving auto-schedule configuration",
                extra={
                    "device_key": saved_device_id,
                    "auto_schedule": auto_schedule_enabled,
                    "enable_at": auto_schedule_enable,
                    "disable_at": auto_schedule_disable,
                },
            )
            device_config.auto_schedule = auto_schedule_enabled
            device_config.auto_schedule_enable = auto_schedule_enable
            device_config.auto_schedule_disable = auto_schedule_disable
            db.session.add(device_config)
            db.session.commit()
            # invalidate remote cache
            invalidate_remote_config(device_key=device_config.device_key)
            # open IPC
            with exception_handler(connect_url=URL_WORKER_AUTO_SCHEDULER, and_raise=False) as zmq_socket:
                zmq_socket.send_pyobj(
                    (
                        device_config.device_key,
                        str(device_config),
                        device_config.auto_schedule,
                        device_config.auto_schedule_enable,
                        device_config.auto_schedule_disable,
                    )
                )
        if "general_config" in request.form:
            autoscheduler_enabled = bool(request.form.get("autoscheduler_enabled"))
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
    return render_template(
        "config.html",
        devices=devices,
        saved_device_id=saved_device_id,
        config_autoscheduler_enabled=config_autoscheduler_enabled,
        tz_name=str(user_tz),
    )


@api_app.get("/api/input_configs")
async def api_input_configs(device_key: str, adb: AsyncSession = Depends(get_db)):
    log.debug("Async get input config", extra={"device_key": device_key})
    result = await adb.execute(select(InputConfig).where(InputConfig.device_key == device_key))
    config = result.scalars().one_or_none()
    if config:
        return config.as_dict()
    else:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No input configuration found for {device_key}",
        )


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
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"No input configuration found for {device_key}",
            )
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
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"No meter configuration found for {device_key}",
            )
        return configs


@flask_app.route("/input_config", methods=["GET", "POST"])
@auth_enabled
@login_required
@authz_required(action="read", resource="Configuration")
@authz_required(action="update", resource="Configuration")
def input_config():
    saved_device_id = None
    if request.method == "POST":
        saved_device_id = int(request.form["device_id"])
        input_cfg = InputConfig.query.filter_by(id=saved_device_id).first()
        meter_cfg = None
        if input_cfg.device_type == "meter":
            meter_cfg = MeterConfig.query.filter_by(input_device_id=input_cfg.id).first()
        customized = False
        if "group_name" in request.form:
            group_name = request.form["group_name"].strip()
            if len(group_name) > 0:
                input_cfg.group_name = group_name
                customized = True
            else:
                input_cfg.group_name = None
        if input_cfg.device_key in request.form.getlist("info_notify"):
            input_cfg.info_notify = True
            customized = True
        else:
            input_cfg.info_notify = None
        if len(request.form["trigger_latch_duration"]) > 0:
            input_cfg.trigger_latch_duration = int(request.form["trigger_latch_duration"])
            customized = True
        else:
            input_cfg.trigger_latch_duration = None
        if len(request.form["multi_trigger_rate"]) > 0:
            input_cfg.multi_trigger_rate = int(request.form["multi_trigger_rate"])
            customized = True
        else:
            input_cfg.multi_trigger_rate = None
        if len(request.form["multi_trigger_interval"]) > 0:
            input_cfg.multi_trigger_interval = int(request.form["multi_trigger_interval"])
            customized = True
        else:
            input_cfg.multi_trigger_interval = None
        if len(request.form["activation_escalation"]) > 0:
            input_cfg.activation_escalation = int(request.form["activation_escalation"])
            customized = True
        else:
            input_cfg.activation_escalation = None
        if meter_cfg:
            if request.form.get("meter_low_limit", None) and len(request.form["meter_low_limit"]) > 0:
                meter_cfg.meter_low_limit = int(request.form["meter_low_limit"])
                customized = True
            else:
                meter_cfg.meter_low_limit = None
            if request.form.get("meter_high_limit", None) and len(request.form["meter_high_limit"]) > 0:
                meter_cfg.meter_high_limit = int(request.form["meter_high_limit"])
                customized = True
            else:
                meter_cfg.meter_high_limit = None
            if request.form.get("meter_reset_value", None) and len(request.form["meter_reset_value"]) > 0:
                meter_cfg.meter_reset_value = int(request.form["meter_reset_value"])
                customized = True
            else:
                meter_cfg.meter_reset_value = None
            if input_cfg.device_key in request.form.getlist("meter_reset_additive"):
                meter_cfg.meter_reset_additive = True
                customized = True
            else:
                meter_cfg.meter_reset_additive = None
            if request.form.get("meter_iot_topic", None) and len(request.form["meter_iot_topic"].strip()) > 0:
                meter_cfg.meter_iot_topic = request.form["meter_iot_topic"].strip()
                customized = True
            else:
                meter_cfg.meter_iot_topic = None
            if request.form.get("meter_reading_unit", None) and len(request.form["meter_reading_unit"].strip()) > 0:
                meter_cfg.meter_reading_unit = request.form["meter_reading_unit"].strip()
                customized = True
            else:
                meter_cfg.meter_reading_unit = None
            if (
                request.form.get("meter_reading_unit_factor", None)
                and len(request.form["meter_reading_unit_factor"]) > 0
            ):
                meter_reading_unit_factor = int(request.form["meter_reading_unit_factor"])
                if 1 <= meter_reading_unit_factor <= 1000000000 and (meter_reading_unit_factor % 10 == 0):
                    meter_cfg.meter_reading_unit_factor = meter_reading_unit_factor
                    customized = True
            else:
                meter_cfg.meter_reading_unit_factor = None
            if (
                request.form.get("meter_reading_unit_precision", None)
                and len(request.form["meter_reading_unit_precision"]) > 0
            ):
                meter_reading_unit_precision = int(request.form["meter_reading_unit_precision"])
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
    return render_template(
        "input_config.html",
        inputs=inputs,
        meters=meters,
        saved_device_id=saved_device_id,
    )


@flask_app.route("/input_link", methods=["GET", "POST"])
@auth_enabled
@login_required
@authz_required(action="read", resource="Configuration")
@authz_required(action="update", resource="Configuration")
def input_link():
    saved_device_id = None
    if request.method == "POST":
        saved_device_id = int(request.form["device_id"])
        # remove existing links for this device
        links = InputLink.query.filter_by(input_device_id=saved_device_id).all()
        for link in links:
            db.session.delete(link)
        db.session.commit()
        # set new links
        for linked_id in request.form.getlist("linked_device_id"):
            db.session.add(InputLink(input_device_id=saved_device_id, linked_device_id=linked_id))
        # save the changes
        db.session.commit()
        # invalidate remote cache
        db_input_config = InputConfig.query.filter_by(id=saved_device_id).first()
        invalidate_remote_config(device_key=db_input_config.device_key)
    input_links = (
        InputConfig.query.add_entity(InputLink)
        .join(InputLink, InputConfig.id == InputLink.input_device_id, isouter=True)
        .order_by(InputConfig.device_key)
        .all()
    )
    inputs = OrderedDict()
    links = dict()
    for input, link in input_links:
        if input.id not in inputs:
            inputs[input.id] = input
        if link:
            if link.input_device_id not in links:
                links[link.input_device_id] = list()
            links[link.input_device_id].append(link.linked_device_id)
    return render_template(
        "input_link.html",
        inputs=inputs.values(),
        links=links,
        saved_device_id=saved_device_id,
    )


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
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"No output link configuration found for {device_key}",
            )
        return configs


@flask_app.route("/output_link", methods=["GET", "POST"])
@auth_enabled
@login_required
@authz_required(action="read", resource="Configuration")
@authz_required(action="update", resource="Configuration")
def output_link():
    saved_device_id = None
    if request.method == "POST":
        saved_device_id = int(request.form["device_id"])
        # remove existing links for this device
        links = OutputLink.query.filter_by(input_device_id=saved_device_id).all()
        for link in links:
            db.session.delete(link)
        db.session.commit()
        # set new links
        for output_device_id in request.form.getlist("linked_device_id"):
            db.session.add(OutputLink(input_device_id=saved_device_id, output_device_id=output_device_id))
        # save the changes
        db.session.commit()
        # invalidate remote cache
        db_input_config = InputConfig.query.filter_by(id=saved_device_id).first()
        invalidate_remote_config(device_key=db_input_config.device_key)
    output_links = (
        InputConfig.query.add_entity(OutputLink)
        .join(OutputLink, InputConfig.id == OutputLink.input_device_id, isouter=True)
        .order_by(InputConfig.device_key)
        .all()
    )
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
    return render_template(
        "output_link.html",
        inputs=inputs.values(),
        outputs=outputs,
        links=links,
        saved_device_id=saved_device_id,
    )


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
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"No output configuration found for {device_key}",
            )
        return configs


@flask_app.route("/output_config", methods=["GET", "POST"])
@auth_enabled
@login_required
@authz_required(action="read", resource="Configuration")
@authz_required(action="update", resource="Configuration")
def output_config():
    saved_device_id = None
    if request.method == "POST":
        saved_device_id = int(request.form["device_id"])
        output_config = OutputConfig.query.filter_by(id=saved_device_id).first()
        device_params = request.form["device_params"].strip()
        if len(device_params) > 0:
            output_config.device_params = device_params
        else:
            output_config.device_params = None
        trigger_topic = request.form["trigger_topic"].strip()
        if len(trigger_topic) > 0:
            output_config.trigger_topic = trigger_topic
        else:
            output_config.trigger_topic = None
        if len(request.form["trigger_interval"]) > 0:
            output_config.trigger_interval = int(request.form["trigger_interval"])
        else:
            output_config.trigger_interval = None
        if output_config.device_key in request.form.getlist("device_enabled"):
            output_config.device_enabled = True
        else:
            output_config.device_enabled = None
        db.session.add(output_config)
        db.session.commit()
        # invalidate remote cache
        invalidate_remote_config(device_key=output_config.device_key)
    outputs = OutputConfig.query.order_by(OutputConfig.device_key).all()
    return render_template("output_config.html", devices=outputs, saved_device_id=saved_device_id)


async def telegram_bot_echo(update: Update, context: TelegramContextTypes.DEFAULT_TYPE) -> None:
    try:
        authorized_users = app_config.get("telegram", "authorized_users").split(",")
        if str(update.effective_user.id) not in authorized_users:
            log.warning("Unauthorized message", extra={"update": str(update)})
            return

        log.info(
            "Telegram bot message received",
            extra={
                "bot_username": context.bot.username,
                "message_text": update.effective_message.text,
                "chat_id": update.effective_message.chat_id,
            },
        )

        group_info = await context.bot.get_chat(chat_id=app_config.getint("telegram", "chat_room_id"))
        bot_response = f"I am in the [{group_info.title}]({group_info.invite_link}) group."
        await update.message.reply_markdown(text=bot_response)
    except NetworkError:
        log.warning("bot handler", exc_info=True)
    except Exception:
        log.exception("bot handler")
        capture_exception()


async def telegram_bot_cmd(update: Update, context: TelegramContextTypes.DEFAULT_TYPE) -> None:
    try:
        authorized_users = app_config.get("telegram", "authorized_users").split(",")
        if str(update.effective_user.id) not in authorized_users:
            log.warning("Unauthorized message", extra={"update": str(update)})
            return

        log.info(
            "Telegram bot command received",
            extra={
                "bot_username": context.bot.username,
                "command_text": update.effective_message.text,
                "command_args": context.args,
                "chat_id": update.effective_message.chat_id,
            },
        )
        # status update
        if update.effective_message.text.startswith("/"):
            with exception_handler(connect_url=URL_WORKER_APP, and_raise=False) as zmq_socket:
                zmq_socket.send_pyobj({"bot": {"command": update.effective_message.text}})
    except NetworkError:
        log.warning("bot handler", exc_info=True)
    except Exception:
        log.exception("bot handler")
        capture_exception()


async def telegram_error_handler(update: Update, context: TelegramContextTypes.DEFAULT_TYPE) -> None:
    # do not capture because there's nothing to handle
    log.warning(msg="Telegram Bot Exception while handling an update:", exc_info=context.error)


def invalidate_remote_config(device_key):
    api_server = app_config.get("app", "event_processor_address")
    api_method = "invalidate_config"
    try:
        response = requests.post(url=f"{api_server}/{api_method}", params={"device_key": device_key})
        log.debug(
            "Remote config invalidation response",
            extra={
                "status_code": response.status_code,
                "api_method": api_method,
                "api_server": api_server,
                "device_key": device_key,
                "response": str(response),
            },
        )
    except ConnectionError as e:
        log.warning(
            "Unable to invalidate remote configuration",
            extra={"api_method": api_method, "api_server": api_server, "device_key": device_key},
            exc_info=e,
        )


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
        # self.event_log = zmq_socket(zmq.PUSH)

        self.bot = zmq_socket(zmq.PUSH)

        self._metric_last_posted_meter_value = 0
        self._metric_meter_value_accumulator = 0
        self._metric_last_posted_register_value = 0

    def _update_device(self, input_outputs, device_origin, origin_devices, event_origin, device):
        # device_key must always be present
        device_key = device["device_key"]
        # set the device label if that hasn't already been done
        if "device_label" not in device:
            device["device_label"] = device_key
        # associate the device with this event origin
        if event_origin not in origin_devices:
            origin_devices[event_origin] = set()
        if device_key not in origin_devices[event_origin]:
            origin_devices[event_origin].add(device_key)
        # has this device been seen?
        if device_key not in device_origin:
            device_origin[device_key] = event_origin
        elif device_origin[device_key] != event_origin:
            log.warning(
                "Device already known from another origin",
                extra={
                    "device_key": device_key,
                    "known_origin": device_origin[device_key],
                    "event_origin": event_origin,
                },
            )
        if device_key not in input_outputs:
            input_outputs[device_key] = device
        return device_key

    # noinspection PyBroadException
    def run(self):
        # bot
        if is_flag_enabled("telegram-bot"):
            self.bot.connect(URL_WORKER_TELEGRAM_BOT)
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
                    zmq_socket.send_pyobj(
                        (
                            device_config.device_key,
                            str(device_config),
                            device_config.auto_schedule,
                            device_config.auto_schedule_enable,
                            device_config.auto_schedule_disable,
                        )
                    )
        # informational notifications
        # TODO: move to UI configuration
        self.notify_not_before_time = make_timestamp(timestamp=app_config.get("info_notify", "not_before_time"))
        self.notify_not_after_time = make_timestamp(timestamp=app_config.get("info_notify", "not_after_time"))
        # message validity
        self._max_message_validity_seconds = int(app_config.get("app", "max_message_validity_seconds"))
        # set up the special input for the panic button
        self._update_device(
            input_outputs=self.inputs,
            device_origin=self._input_origin,
            origin_devices=self._inputs_by_origin,
            event_origin=DEVICE_NAME,
            device={
                "device_key": "App Panic Button",
                "device_label": "Panic Button",
                "type": "Panic Button",
            },
        )
        # set up the special input for the dash button
        self._update_device(
            input_outputs=self.inputs,
            device_origin=self._input_origin,
            origin_devices=self._inputs_by_origin,
            event_origin=DEVICE_NAME,
            device={
                "device_key": "App Dash Button",
                "device_label": "Dash Button",
                "type": "Dash Button",
            },
        )
        # set up special output for SMS (text notifications)
        self._update_device(
            input_outputs=self.outputs,
            device_origin=self._output_origin,
            origin_devices=self._outputs_by_origin,
            event_origin=DEVICE_NAME,
            device={"device_key": "SMS", "device_label": "SMS", "type": "SMS"},
        )
        with exception_handler(
            connect_url=URL_WORKER_APP,
            socket_type=zmq.PULL,
            and_raise=False,
            shutdown_on_error=True,
        ) as app_socket:
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
                    log.debug("Malformed event; expecting dictionary.")
                    continue
                if "sms" in event:
                    sms_message = event["sms"]
                    if is_flag_enabled("telegram-bot"):
                        log.debug("Sending payload to bot", extra={"event_keys": list(event.keys())})
                        self.bot.send_pyobj(sms_message)
                        log.debug("Sent payload to bot...")
                    else:
                        log.warning(
                            "Not sending message to Telegram bot, disabled with feature flag",
                            extra={"sms_part_count": len(sms_message)},
                        )
                    continue
                for event_origin, event_data in list(event.items()):
                    if not isinstance(event_data, dict):
                        log.warning(
                            "Ignoring non-dict event format",
                            extra={
                                "event_origin": event_origin,
                                "event_class": str(event_data.__class__),
                                "event_data": str(event_data),
                            },
                        )
                        continue
                    if "timestamp" in event_data:
                        str_timestamp = event_data["timestamp"]
                        log.debug(
                            "Event timestamp",
                            extra={"event_origin": event_origin, "event_timestamp": str_timestamp},
                        )
                        timestamp = make_timestamp(str_timestamp)
                    else:
                        timestamp = make_timestamp()
                        log_msg = "Message has no 'timestamp' so it can't be filtered if stale; using current time"
                        log_fields = {"event_origin": event_origin, "timestamp_used": make_iso_timestamp(timestamp)}
                        if "active_devices" in event_data or "outputs_triggered" in event_data:
                            log.warning(log_msg, extra=log_fields)
                        else:
                            log.debug(log_msg, extra=log_fields)
                    if event_origin == "device_info_input":
                        self._update_device(
                            input_outputs=self.inputs,
                            device_origin=self._input_origin,
                            origin_devices=self._inputs_by_origin,
                            event_origin=DEVICE_NAME,
                            device=event_data,
                        )
                        di: DeviceInfo = DeviceInfo.model_validate(event_data)
                        ic = InputConfig.query.filter_by(device_key=di.device_key).first()
                        if ic is None:
                            log.info(
                                "Adding new input configuration",
                                extra={"device_key": di.device_key, "device_label": di.device_label},
                            )
                            db.session.add(
                                InputConfig(
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
                                    activation_escalation=None,
                                    info_notify=None,
                                )
                            )
                            db.session.commit()
                    elif event_origin == "device_info_output":
                        self._update_device(
                            input_outputs=self.outputs,
                            device_origin=self._output_origin,
                            origin_devices=self._outputs_by_origin,
                            event_origin=DEVICE_NAME,
                            device=event_data,
                        )
                        di: DeviceInfo = DeviceInfo.model_validate(event_data)
                        oc = OutputConfig.query.filter_by(device_key=di.device_key).first()
                        if oc is None:
                            log.info(
                                "Adding new output configuration",
                                extra={"device_key": di.device_key, "device_label": di.device_label},
                            )
                            db.session.add(
                                OutputConfig(
                                    device_key=di.device_key,
                                    device_label=di.device_label,
                                    device_type=di.device_type,
                                    device_params=None,
                                    trigger_topic=None,
                                    trigger_interval=None,
                                    device_enabled=None,
                                    auto_schedule=None,
                                    auto_schedule_enable=None,
                                    auto_schedule_disable=None,
                                )
                            )
                            db.session.commit()
                    elif event_origin == "auto-scheduler":
                        device_key = event_data["device_key"]
                        device_label = event_data["device_label"]
                        device_enable = event_data["device_state"]
                        log.info(
                            "Auto-scheduler updating device",
                            extra={"device_label": device_label, "enabled": device_enable},
                        )
                        device_config = InputConfig.query.filter_by(device_key=device_key).first()
                        if device_config is None:
                            device_config = OutputConfig.query.filter_by(device_key=device_key).first()
                        device_config.device_enabled = device_enable
                        db.session.add(device_config)
                        db.session.commit()
                        invalidate_remote_config(device_key=device_key)
                        # skip further processing because of enable/disable
                        continue
                    elif event_origin == "bot":
                        log.debug("Bot command received", extra={"event_data": str(event_data)})
                        bot_command = event_data["command"].split()
                        bot_command_base = bot_command[0]
                        bot_command_args = None
                        if len(bot_command) > 0:
                            bot_command_args = bot_command[1:]
                        input_enable = None
                        output_enable = None
                        if bot_command_base.startswith("/outputon"):
                            output_enable = True
                        elif bot_command_base.startswith("/outputoff"):
                            output_enable = False
                        elif bot_command_base.startswith("/inputon"):
                            input_enable = True
                        elif bot_command_base.startswith("/inputoff"):
                            input_enable = False
                        state = "enable"
                        if not input_enable or not output_enable:
                            state = "disable"
                        bot_reply = f"No devices to {state}."
                        device_configs = list()
                        if bot_command_args:
                            for bot_command_arg in bot_command_args:
                                device_config = list()
                                # https://stackoverflow.com/questions/3325467/sqlalchemy-equivalent-to-sql-like-statement
                                sql_search = f"%{bot_command_arg}%"
                                if input_enable is not None:
                                    device_config = (
                                        InputConfig.query.filter(
                                            or_(
                                                InputConfig.device_key.like(sql_search),
                                                InputConfig.device_label.like(sql_search),
                                                InputConfig.group_name.like(sql_search),
                                            )
                                        )
                                        .order_by(InputConfig.device_key)
                                        .all()
                                    )
                                elif output_enable is not None:
                                    device_config = (
                                        OutputConfig.query.filter(
                                            or_(
                                                OutputConfig.device_key.like(sql_search),
                                                OutputConfig.device_label.like(sql_search),
                                            )
                                        )
                                        .order_by(OutputConfig.device_key)
                                        .all()
                                    )
                                # collect all configurations matched
                                log.debug(
                                    "Devices matched",
                                    extra={
                                        "match_count": len(device_config),
                                        "search_term": bot_command_arg,
                                        "state": state,
                                    },
                                )
                                if device_config:
                                    device_configs.extend(device_config)
                        else:
                            device_config = list()
                            if input_enable is not None:
                                # wildcard action is constrained to devices where auto-scheduling is enabled
                                device_config = (
                                    InputConfig.query.filter(InputConfig.auto_schedule.isnot(None))
                                    .order_by(InputConfig.device_key)
                                    .all()
                                )
                                log.debug(
                                    "Devices with auto-schedule not null",
                                    extra={"device_count": len(device_config), "state": state},
                                )
                            elif output_enable is not None:
                                device_config = OutputConfig.query.order_by(OutputConfig.device_key).all()
                            if len(device_config) > 0:
                                log.debug(
                                    "Devices selected",
                                    extra={"device_count": len(device_config), "state": state},
                                )
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
                                    log.debug(
                                        "Updating device state",
                                        extra={
                                            "device_key": dc.device_key,
                                            "group_name": dc.group_name,
                                            "state": state,
                                        },
                                    )
                                    dc.device_enabled = device_enable
                                    # update the database
                                    db.session.add(dc)
                                    # update auto-scheduled inputs
                                    if input_enable is not None and dc.auto_schedule is not None:
                                        # update the auto-scheduler task
                                        with exception_handler(
                                            connect_url=URL_WORKER_AUTO_SCHEDULER,
                                            and_raise=False,
                                        ) as zmq_socket:
                                            if device_enable:
                                                # restore auto-schedule actions
                                                zmq_socket.send_pyobj(
                                                    (
                                                        dc.device_key,
                                                        str(dc),
                                                        dc.auto_schedule,
                                                        dc.auto_schedule_enable,
                                                        dc.auto_schedule_disable,
                                                    )
                                                )
                                            else:
                                                # disable runtime auto-scheduling actions
                                                zmq_socket.send_pyobj(
                                                    (
                                                        dc.device_key,
                                                        str(dc),
                                                        None,
                                                        None,
                                                        None,
                                                    )
                                                )
                            if len(devices_updated) > 0:
                                db.session.commit()
                                for device_key in devices_updated:
                                    invalidate_remote_config(device_key=device_key)
                            bot_reply = f"{len(devices_updated)} devices changed to {state}."
                        else:
                            log.warning("No devices matched", extra={"state": state})
                        log.debug(
                            "Bot command result",
                            extra={"devices_updated": len(devices_updated), "state": state},
                        )
                        if is_flag_enabled("telegram-bot"):
                            self.bot.send_pyobj(BotMessage(device_label="notification", message=bot_reply).model_dump())
                        else:
                            log.warning(
                                "Not sending message to Telegram bot, disabled with feature flag",
                                extra={"reply_length": len(bot_reply)},
                            )
                        # stop processing
                        if not bot_command_base.startswith("/report"):
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

    @staticmethod
    def build_device_message(timestamp, input_device: Device) -> BotMessage:
        device_label = input_device.device_label
        if device_label is None:
            device_label = input_device.device_key
        event_detail = ""
        if input_device.event_detail:
            event_detail = f" {input_device.event_detail}"
        # include a timestamp in this SMS message
        message = f"{device_label}{event_detail} ({timestamp.hour}:{str(timestamp.minute).zfill(2)})"
        image_data = None
        if input_device.image:
            image_data = input_device.image
        image_timestamp = None
        if input_device.image_timestamp:
            image_timestamp = input_device.image_timestamp
        return BotMessage(
            device_label=device_label,
            message=message,
            url=input_device.storage_url,
            image=image_data,
            image_timestamp=image_timestamp,
        )

    @staticmethod
    def include_image(message):
        if not app_config.getboolean("telegram", "image_send_only_with_people"):
            return True
        return "person" in message or "face" in message

    @staticmethod
    async def tbot_run(t_app: TelegramApp, zmq_socket, chat_id):
        poller = Poller()
        poller.register(zmq_socket, zmq.POLLIN)
        pending_by_label = OrderedDict()
        log.debug("Waiting for events to forward to Telegram bot", extra={"chat_id": chat_id})
        call_again_timestamp = 0
        min_send_interval = app_config.getint("telegram", "min_send_interval")
        last_sent = 0
        while not threads.shutting_down and is_flag_enabled("telegram-bot"):
            now = round(time.time())
            event = None
            events = await poller.poll(timeout=1000)
            if zmq_socket in dict(events):
                try:
                    event = await zmq_socket.recv_pyobj()
                except ZMQError:
                    log.exception("Cannot get message from ZMQ channel.")
            if event is None and not pending_by_label:
                continue
            try:
                if isinstance(event, dict):
                    input_device: Device = None
                    output_device: Device = None
                    message = None
                    try:
                        if "active_input" in event:
                            input_device = Device(**event["active_input"])
                            log.debug("Input device for message", extra={"input_device": str(input_device)})
                        elif "output_triggered" in event:
                            output_device = Device(**event["output_triggered"])
                            log.debug("Output device for message", extra={"output_device": str(output_device)})
                        else:
                            message = BotMessage(**event)
                    except Exception:
                        log.warning("Bot message unpack problem", exc_info=True)
                        continue
                    timestamp = None
                    if "timestamp" in event:
                        timestamp = make_timestamp(timestamp=event["timestamp"], as_tz=user_tz)
                    else:
                        log.warning('No timestamp included in event message; using "now"')
                        timestamp = make_timestamp(as_tz=user_tz)
                    log.debug(
                        "Message context",
                        extra={
                            "input_device": str(input_device),
                            "output_device": str(output_device),
                            "message_timestamp": str(timestamp),
                        },
                    )
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
                    log.debug(
                        "Queueing message",
                        extra={
                            "device_label": message.device_label,
                            "message_timestamp": message.timestamp,
                            "queued_count": len(queued),
                        },
                    )
                    queued.append(message)
                # rate-limit the send
                # https://core.telegram.org/bots/faq#my-bot-is-hitting-limits-how-do-i-avoid-this
                if now < call_again_timestamp:
                    log.debug(
                        "Enforced rate limiting of message queue",
                        extra={
                            "device_count": len(pending_by_label),
                            "backoff_seconds": call_again_timestamp - now,
                        },
                    )
                    continue
                time_since_sent = now - last_sent
                if time_since_sent < min_send_interval:
                    log.debug(
                        "Elective rate limiting of message queue",
                        extra={
                            "device_count": len(pending_by_label),
                            "time_since_sent_seconds": time_since_sent,
                            "min_send_interval_seconds": min_send_interval,
                        },
                    )
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
                    log.error("No queued messages for any device.")
                    continue
                message = pending.popleft()
                image_batch = []
                # other messages to dedupe
                while True:
                    log.debug(
                        "Processing message",
                        extra={
                            "device_label": message.device_label,
                            "message_timestamp": message.timestamp,
                            "queued_count": len(pending),
                        },
                    )
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
                                            url=message.url,
                                        )
                                    ]
                                log.debug(
                                    "Batching image",
                                    extra={
                                        "device_label": device_label,
                                        "message_timestamp": message.timestamp,
                                        "image_timestamp": message.image_timestamp,
                                        "chat_id": chat_id,
                                        "caption": str(message),
                                        "batch_size": len(image_batch),
                                    },
                                )
                                image_batch.append(
                                    InputMediaPhoto(
                                        media=BytesIO(message.image),
                                        caption=str(message),
                                        caption_entities=caption_entities,
                                    )
                                )
                            else:
                                # enough is enough, re-enqueue the remainder
                                log.debug(
                                    "Re-enqueueing events, image batch full",
                                    extra={
                                        "remaining_count": len(pending),
                                        "device_label": device_label,
                                        "batch_size": len(image_batch),
                                    },
                                )
                                pending_by_label[device_label] = pending
                                break
                        else:
                            log.debug(
                                "Filtering out image message",
                                extra={
                                    "device_label": message.device_label,
                                    "message_timestamp": message.timestamp,
                                    "queued_count": len(pending),
                                },
                            )
                    try:
                        # attempt to fetch a newer image
                        message = pending.popleft()
                        log.debug(
                            "Fetched newer pending message",
                            extra={"device_label": message.device_label, "message_timestamp": message.timestamp},
                        )
                    except IndexError:
                        # message remains set to the current
                        break
                # send the message
                try:
                    if len(image_batch) > 0:
                        log.info("Sending image group", extra={"chat_id": chat_id, "image_count": len(image_batch)})
                        await t_app.bot.send_media_group(
                            chat_id=chat_id,
                            media=image_batch,
                            read_timeout=300,
                            write_timeout=300,
                            connect_timeout=300,
                            pool_timeout=300,
                        )
                    if not message.image:
                        log.info(
                            "Sending non-image message",
                            extra={
                                "device_label": device_label,
                                "message_timestamp": message.timestamp,
                                "chat_id": chat_id,
                                "caption": str(message),
                            },
                        )
                        await t_app.bot.send_message(chat_id=chat_id, text=str(message), parse_mode="Markdown")
                except RetryAfter as e:
                    call_again_timestamp = now + e.retry_after
                    log.debug(
                        "Telegram rate limit, deferring calls",
                        extra={"retry_after_seconds": e.retry_after, "call_again_timestamp": call_again_timestamp},
                    )
                    continue
                except (TimedOut, ConnectError) as e:
                    log.warning("Telegram send problem", exc_info=e)
                    continue
                # update send time
                last_sent = now
            except Exception:
                capture_exception()
                log.exception("General issue with bot message processing.")

    def run(self):
        log.debug("Creating asyncio event loop...")
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        log.debug("Creating Telegram application...")
        telegram_application = TelegramApp.builder().token(creds.get_creds(f"Telegram/{APP_NAME}/token")).build()
        telegram_application.add_handler(TelegramCommandHandler(command="inputon", callback=telegram_bot_cmd))
        telegram_application.add_handler(TelegramCommandHandler(command="inputoff", callback=telegram_bot_cmd))
        telegram_application.add_handler(TelegramCommandHandler(command="outputon", callback=telegram_bot_cmd))
        telegram_application.add_handler(TelegramCommandHandler(command="outputoff", callback=telegram_bot_cmd))
        telegram_application.add_handler(TelegramMessageHandler(filters.TEXT & ~filters.COMMAND, telegram_bot_echo))
        telegram_application.add_error_handler(callback=telegram_error_handler)
        self.t_app = telegram_application
        log.debug("Registering coroutine for ZMQ-Telegram messages...")
        self.get_socket()
        outcome = asyncio.run_coroutine_threadsafe(
            TBot.tbot_run(t_app=self.t_app, zmq_socket=self.socket, chat_id=self.chat_id),
            loop,
        )
        log.debug("Starting Telegram application...")
        self.t_app.run_polling(stop_signals=None)
        log.debug("Waiting for coroutine exceptions...")
        exc = outcome.exception()
        if exc is not None:
            log.warning("Completed with exception.", exc_info=exc)
        log.debug("Closing event loop...")
        loop.close()
        self.shutdown()
        log.info("Shutdown complete.")

    def shutdown(self):
        # TODO: shut down Telegram bot from external
        # event loop if stop_signals=None
        if not self._shutdown:
            log.debug("Closing ZMQ socket...")
            self.close()
            self._shutdown = True


class AutoScheduler(AppThread):
    def __init__(self):
        AppThread.__init__(self, name=self.__class__.__name__)
        self._is_enabled_cached = None
        self._is_enabled_ttl = 0.0

    @property
    def is_enabled(self):
        now = time.time()
        if self._is_enabled_cached is not None and now < self._is_enabled_ttl:
            return self._is_enabled_cached
        config_autoscheduler_enabled = False
        with flask_app.app_context():
            config_autoscheduler = GeneralConfig.query.filter_by(config_key=CONFIG_AUTO_SCHEDULER).first()
            if config_autoscheduler:
                config_autoscheduler_enabled = bool(int(config_autoscheduler.config_value))
        self._is_enabled_cached = config_autoscheduler_enabled
        self._is_enabled_ttl = now + 30
        return config_autoscheduler_enabled

    @staticmethod
    def update_device(device_key, device_label, device_state):
        log.info("Scheduler triggered", extra={"device_label": device_label, "enabled": device_state})
        with exception_handler(
            connect_url=URL_WORKER_APP,
            socket_type=zmq.PUSH,
            and_raise=False,
            shutdown_on_error=False,
        ) as zmq_socket:
            zmq_socket.send_pyobj(
                {
                    "auto-scheduler": {
                        "device_key": device_key,
                        "device_label": device_label,
                        "device_state": device_state,
                    }
                }
            )

    def _schedule(self, device_key, device_label, schedule_time, device_state):
        log.info(
            "Setting auto-schedule",
            extra={
                "device_label": device_label,
                "enabled": device_state,
                "schedule_time": schedule_time,
                "tz": str(user_tz),
            },
        )
        schedule.every().day.at(schedule_time, user_tz).do(
            AutoScheduler.update_device, device_key, device_label, device_state
        ).tag(device_key)

    # noinspection PyBroadException
    def run(self):
        if not self.is_enabled:
            log.warning("Auto-scheduler is not enabled; scheduled changes will not run.")
        with exception_handler(
            connect_url=URL_WORKER_AUTO_SCHEDULER,
            socket_type=zmq.PULL,
            and_raise=False,
            shutdown_on_error=True,
        ) as zmq_socket:
            while not threads.shutting_down:
                next_message = False
                # trigger any scheduled work
                if self.is_enabled:
                    schedule.run_pending()
                # look for device updates
                device_key = None
                try:
                    (
                        device_key,
                        device_label,
                        auto_schedule,
                        auto_schedule_enable,
                        auto_schedule_disable,
                    ) = zmq_socket.recv_pyobj(flags=zmq.NOBLOCK)
                    next_message = True
                except ZMQError:
                    # ignore, no data
                    next_message = False
                if device_key:
                    # clear any previous schedule
                    log.info("Removing auto-schedule", extra={"device_label": device_label})
                    schedule.clear(device_key)
                    if auto_schedule:
                        log.info(
                            "Resetting auto-schedule",
                            extra={
                                "device_label": device_label,
                                "disable_at": auto_schedule_disable,
                                "enable_at": auto_schedule_enable,
                            },
                        )
                        try:
                            # install a new scedule
                            self._schedule(
                                device_key=device_key,
                                device_label=device_label,
                                schedule_time=auto_schedule_disable,
                                device_state=False,
                            )
                            self._schedule(
                                device_key=device_key,
                                device_label=device_label,
                                schedule_time=auto_schedule_enable,
                                device_state=True,
                            )
                        except ScheduleValueError:
                            log.exception("Unable to schedule.")
                    else:
                        log.warning("Disabled auto-schedule", extra={"device_label": device_label})
                # don't spin
                if not next_message:
                    threads.interruptable_sleep.wait(10)


class ApiServer(Thread):
    def __init__(self):
        super().__init__(name=self.__class__.__name__)
        self.server = None

        config = uvicorn.Config(
            # app="app.__main__:api_app",
            app=api_app,
            host="0.0.0.0",
            # host=app_config.get("api", "host"),
            port=int(app_config.get("flask", "http_port")),
            log_level="warning",
            timeout_graceful_shutdown=1,
        )
        self.server = uvicorn.Server(config)

    def run(self):
        log.debug("Starting API server...")
        self.server.run()
        log.info("API server is finished.")

    def shutdown(self):
        if self.server:
            log.debug("API server shutting down", extra={"class_name": self.__class__.__name__})
            # emulate signal handler latch in server.handle_exit()
            self.server.should_exit = True
            self.server.force_exit = True


async def main():
    global creds
    global sentry_dsn
    # sentry instrumentation
    log.debug("Loading Sentry.io instrumentation...")
    sentry_dsn = creds.get_creds(app_config.get("creds", "sentry_dsn").replace("__APP_NAME__", APP_NAME))
    sentry_sdk.init(
        dsn=sentry_dsn,
        enable_logs=True,
        integrations=[
            AsyncioIntegration(),
            FlaskIntegration(transaction_style="url"),
            SysExitIntegration(capture_successful_exits=True),
            ThreadingIntegration(propagate_scope=True),
        ],
        send_default_pii=True,
    )
    # ensure proper signal handling; must be main thread
    log.debug("Installing signal handlers...")
    signal_handler = SignalHandler()
    if not threads.shutting_down:
        log.debug("Creating application threads...")
        # bind listeners first
        mq_server_address = app_config.get("rabbitmq", "server_address").split(",")
        mq_exchange_name = app_config.get("rabbitmq", "mq_exchange")
        mq_listener_sms = ZMQListener(
            zmq_url=URL_WORKER_APP,
            mq_server_address=mq_server_address,
            mq_exchange_name=f"{mq_exchange_name}_control",
            mq_topic_filter="event.trigger.sms",
            mq_exchange_type="direct",
        )
        auto_scheduler = AutoScheduler()
        event_processor = EventProcessor()
        # configure Telegram bot
        telegram_bot = None
        if is_flag_enabled("telegram-bot"):
            telegram_bot = TBot(
                chat_id=app_config.getint("telegram", "chat_room_id"),
                sns_fallback=app_config.getboolean("telegram", "sns_fallback_enabled"),
            )
        else:
            log.warning("Not running Telegram bot client due to feature flag.")
        # start the nanny
        nanny = threading.Thread(daemon=True, name="nanny", target=thread_nanny, args=(signal_handler,))
        # not tracked by nanny because this is used for Flask bootstrap
        server = ApiServer()
        try:
            log.debug("Starting app threads", extra={"app_name": APP_NAME})
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
            api_app.state.startup_complete = True
            log.info("Startup complete.")
            # block on threading event
            threads.interruptable_sleep.wait()
        finally:
            die()
            log.debug("Shutting down component", extra={"component": "API server"})
            server.shutdown()
            if telegram_bot:
                log.debug("Shutting down component", extra={"component": "Telegram Bot"})
                telegram_bot.shutdown()
            log.debug("Shutting down component", extra={"component": "Rabbit MQ listener bridge"})
            mq_listener_sms.stop()
            zmq_term()
        bye()


if __name__ == "__main__":
    creds = Creds()
    creds.validate_creds()
    flask_app.secret_key = creds.get_creds("Frontend/Flask/secret_key")
    asyncio.run(main())
