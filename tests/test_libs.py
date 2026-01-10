def test_app_imports(monkeypatch):
    import asyncio
    import requests
    import schedule
    import threading
    import time
    import uvicorn
    import zmq
    import zmq.asyncio

    import sentry_sdk

    from sentry_sdk import capture_exception
    from sentry_sdk.integrations.asyncio import AsyncioIntegration
    from sentry_sdk.integrations.flask import FlaskIntegration
    from sentry_sdk.integrations.logging import ignore_logger
    from sentry_sdk.integrations.threading import ThreadingIntegration
    from sentry_sdk.integrations.sys_exit import SysExitIntegration

    from collections import OrderedDict, deque
    from flask_sqlalchemy import SQLAlchemy
    from functools import wraps
    from io import BytesIO
    from os import path
    from pylru import lrucache
    from pytz import timezone
    from requests.exceptions import ConnectionError
    from schedule import ScheduleValueError

    from telegram import Update, InputMediaPhoto, MessageEntity
    from telegram.constants import MediaGroupLimit
    from telegram.ext import (
        Application as TelegramApp,
        CommandHandler as TelegramCommandHandler,
        ContextTypes as TelegramContextTypes,
        MessageHandler as TelegramMessageHandler,
        filters,
    )
    from telegram.error import NetworkError, RetryAfter, TimedOut
    from httpx import ConnectError
    from threading import Thread


    from fastapi import FastAPI, Depends, status, HTTPException, Request
    from fastapi.responses import RedirectResponse
    from fastapi.middleware.wsgi import WSGIMiddleware

    from pydantic import BaseModel

    from sqlalchemy.ext.asyncio import AsyncEngine, create_async_engine, AsyncSession
    from sqlalchemy.orm import declarative_base, sessionmaker
    from sqlalchemy import Column, Integer, String, DateTime, Boolean, Text, Float
    from sqlalchemy import ForeignKey, UniqueConstraint
    from sqlalchemy.future import select
    from sqlalchemy.orm import relationship
    from sqlalchemy import or_

    from flask import Flask, flash, request, render_template, url_for, redirect
    from flask.logging import default_handler
    from flask_compress import Compress

    from flask_login import LoginManager
    from flask_login import login_required, login_user, logout_user, current_user, UserMixin

    from permit.sync import Permit

    from zmq.asyncio import Poller
    from zmq.error import ZMQError

    from tailucas_pylib import APP_NAME, app_config, creds, DEVICE_NAME, log, log_handler
    from tailucas_pylib.flags import is_flag_enabled
    from tailucas_pylib.datetime import (
        make_timestamp,
        make_unix_timestamp,
        make_iso_timestamp,
    )
    from tailucas_pylib.device import Device
    from tailucas_pylib.process import SignalHandler
    from tailucas_pylib.rabbit import ZMQListener
    from tailucas_pylib import threads
    from tailucas_pylib.threads import thread_nanny, die, bye
    from tailucas_pylib.app import AppThread
    from tailucas_pylib.zmq import zmq_term, Closable, zmq_socket, try_close, URL_WORKER_APP
    from tailucas_pylib.handler import exception_handler

def test_permit_client():
    from permit.sync import Permit
    permit = Permit(pdp="hostname", token="permit_token")
