---
paths:
  - "app/**"
  - "tests/**"
  - "pyproject.toml"
  - "Makefile"
---

# Telegram Bot Standard (event-processor)

The Telegram bot is one worker inside the Python web application, not a separate
process. It follows the same conventions as the sibling projects
(`inverter-monitor`, `net-tool`), adapted to this app's single-module layout. All
new bot code must comply.

> **Logging:** structured only — static message plus `extra` fields. Never
> interpolate values into the message. See `.clinerules/logging.md`.

---

## 1. Where bot code lives

- `app/__main__.py` — handlers, allowlist checks, `TBot` (the queue, batching and
  image filter in `tbot_run`), and `main()` wiring.
- **Pure siblings** — no `tailucas_pylib`, no I/O, no global state, each with tests,
  so `pytest` imports them without booting the application:
  - `app/ring_report.py` — `/ring` composition (formatting only;
    `tests/test_ring_report.py`).
  - `app/snapshot_command.py` — `/snapshot` camera selection and reply text
    (`tests/test_snapshot_command.py`).
  - `app/snapshot_window.py` — the requested-image delivery window
    (`tests/test_snapshot_window.py`).
  - `app/telegram_filter.py` — the image-forwarding policy
    (`tests/test_telegram_filter.py`).
- `tests/` — pytest coverage for those modules.

**Rule:** a formatter must have no imports from `tailucas_pylib`, no I/O, and no
global state, so `pytest` can import it directly. `import app.__main__` in a test is
forbidden: it constructs credentials, Sentry, Flask, SQLAlchemy and the engines at
import time.

---

## 2. Handler conventions

### Signature and skeleton

```python
async def telegram_bot_<command>(update: Update, context: TelegramContextTypes.DEFAULT_TYPE) -> None:
    with otel_tracer().start_as_current_span("telegram.<command>") as span:
        message = update.effective_message
        try:
            authorized_users = app_config.get("telegram", "authorized_users").split(",")
            if str(update.effective_user.id) not in authorized_users:
                log.warning("Unauthorized message", extra={"update": str(update)})
                return
            if message is None:
                return
            ...
            span.set_status(StatusCode.OK)
        except NetworkError:
            span.set_status(StatusCode.ERROR)
            log.warning("bot handler", exc_info=True)
        except Exception as e:
            span.record_exception(e)
            span.set_status(StatusCode.ERROR)
            log.exception("Static description of what failed")
            capture_exception()
```

Mandatory, in this order:

1. **Span per command** — `otel_tracer().start_as_current_span("telegram.<command>")`.
2. **Allowlist first** — `authorized_users` from `app_config.get("telegram", "authorized_users")`;
   an unauthorised user logs `"Unauthorized message"` with `extra={"update": str(update)}`
   and returns without any other work.
3. **Typing action** before slow work:
   `await context.bot.send_chat_action(chat_id=message.chat_id, action=ChatAction.TYPING)`.
4. **Span attributes**: `telegram.command`, `telegram.chat_id`, `telegram.user_id`
   and any command-specific counter (e.g. `ring.device_count`).
5. **One INFO record per command**: message `"Telegram bot command received"` with
   `extra={"bot_username", "command_text", "chat_id"}` (send a command in the target
   group to discover its chat id).
6. **Never block the event loop**: synchronous work (`requests`, file I/O) runs in
   `await asyncio.get_running_loop().run_in_executor(None, callable)`.

### Registration

Handlers are registered in `TBot.run()` **before** `run_polling`:

```python
telegram_application.add_handler(TelegramCommandHandler(command="ring", callback=telegram_bot_ring))
telegram_application.add_handler(TelegramCommandHandler(command="snapshot", callback=telegram_bot_snapshot))
```

A command's *effect* decides its transport; there are three, and only one is the
generic path:

| Need | Path | Example |
|---|---|---|
| Read device state | `GET` the Java REST API in an executor | `/ring` → `GET /devices` |
| Change an input/output's enabled state | ZMQ to the Python `EventProcessor` (`{"bot": {"command": ...}}` → `URL_WORKER_APP`, replies pushed back to `URL_WORKER_TELEGRAM_BOT`) | `/inputon`, `/outputoff` |
| Ask for something only the Java side can do | `POST` the Java REST API in an executor | `/snapshot` → `POST /snapshot` |

The generic `/inputon`, `/inputoff`, `/outputon`, `/outputoff` commands share
`telegram_bot_cmd` and use the middle path. **A new command that only reads does
not use it**, and a command that makes hardware act belongs on the Java side
(because Java owns MQTT — see `mq.md` §3) — never publish from the web app, and
never block the event loop on either call. A global error handler
(`telegram_error_handler`) is always registered.

### Configuration and credentials

- Bot token: `creds.get_creds(f"Telegram/{APP_NAME}/token")` — never hardcode.
- Allowlist / chat room: `app_config.get("telegram", ...)` from `config/app.conf`.
- Remote services: `app_config.get("app", "event_processor_address")` is the Java
  event processor base URL (`http://host.docker.internal:8085` in the container).
  A command that needs live device state calls its REST API, e.g.
  `GET {event_processor_address}/devices`, with an explicit `timeout`.

---

## 3. Message formatting and parse modes

A wrong parse mode either renders markup literally (users see `*` and backticks) or
raises `BadRequest: can't parse entities`. These rules are mandatory.

### 3a. Decision table

| Situation | Call | Parse mode |
|---|---|---|
| Default formatted reply | `reply_text(text=..., disable_web_page_preview=True, parse_mode=ParseMode.MARKDOWN)` | `MARKDOWN` |
| Message carrying an `InlineKeyboardMarkup` | `reply_html(text=..., reply_markup=...)` | `HTML` (implicit) |
| Photo / captions | `reply_photo(photo=..., caption=...)` | none — captions stay plain |
| Bot-initiated push (no user update) | `context.bot.send_message(chat_id=..., text=..., parse_mode=ParseMode.HTML)` | `HTML` |
| Error or acknowledgement with no markup | `reply_text("Settings updated.")` | none |
| Echo of user-supplied text | `reply_text(update.message.text)` | none — **never parse user input** |

### 3b. Rules

1. **Default to legacy `parse_mode=ParseMode.MARKDOWN`** (never `MARKDOWN_V2`, never
   `escape_markdown`) and always pass `disable_web_page_preview=True` when the text
   can contain a URL. V2 reserves `.`, `!`, `-`, `(`, `)`, `_`, `*`, `[`, `]`, `~`,
   `` ` ``, `>`, `#`, `+`, `=`, `|`, `{`, `}`, which guarantees failures on real
   device names.
2. **Sanitise data, never the whole message.** Use `app.ring_report.markdown_safe()`
   on external strings (device labels, model names, states) so `*`, `_`, `` ` ``,
   `[`, `]` cannot break markup, and compose the intentional markup around it.
3. **One style per message.** Never mix `<b>` into a Markdown message or `*bold*`
   into an HTML message.
4. **Any keyboard ⇒ `reply_html`** (and
   `edit_message_text(..., parse_mode=ParseMode.HTML, reply_markup=...)` for edits).
5. **Structure**: labels in `*...*`, dynamic values in backticks, fields joined with
   `" · "`, blocks joined with `"\n"`, and build multi-line messages as a list of
   lines (`"\n".join(lines)`) rather than string concatenation.
6. **Respect the 4096-character limit**: compose, then `split_message(...)` and send
   the parts in order.
7. **Emoji only via `emoji.emojize(':name:')`** — never raw Unicode in source.
   Icons in use: `:bell:` Ring, `:camera:` camera, `:musical_note:` chime,
   `:warning:` offline/error, `:low_battery:` battery at or below 20%,
   `:battery:` battery, `:check_mark:` success.

### 3c. Reference output (`/ring`)

```
🔔 *Ring devices* — 20 known, 20 online

📷 *Front Gate* — Ring Camera
`online` · battery `100%` full · signal `-67` dBm · reported 4m ago
`motion` OFF · `ding` OFF

🔔 *Garage Door* — Ring Contact Sensor
`online` · battery `95%` full · reported 2h ago
`contact` OFF
```

- Heading: icon, `*label*`, then the discovery model.
- Detail line: availability, battery (charge, and health only when it differs),
  signal in dBm, then only the flags that are **not** `ok` (tamper, comm, link), and
  freshness (`reported …` from the device's own last report, `seen …` when Ring does
  not report one).
- State line: the last known state per entity subject in `ring_report.STATE_ORDER`
  (`alarm`, `contact`, `motion`, `ding`, `siren`, `fire`, `police`, `stream`,
  `snapshot`, `volume`, `snooze`). Telemetry subjects (`battery`, `wireless`,
  `bypass_mode`, `chirp_tone`, …) are deliberately omitted.

---

## 4. Bot-initiated notifications

Alerts and device events are pushed from the `EventProcessor` thread into the bot's
ZMQ `PULL` socket (`URL_WORKER_TELEGRAM_BOT`), consumed by `TBot.tbot_run()`, which
sends them with `parse_mode="Markdown"` to the configured chat room. Producers must
check `is_flag_enabled("telegram-bot")` first and log a structured WARNING when the
bot is disabled, so the feature switch is honoured everywhere.

Telegram errors are handled in flight: `RetryAfter` defers the send until its
`retry_after` window; `TimedOut`/`ConnectError` log a WARNING and skip the message
(the next event will follow).

---

## 5. Images (`BotMessage.image`)

An event whose `active_input` carries `image` bytes is sent as a photo group, with
the composed message as the caption (`send_media_group`). The processor puts the
bytes there when a message's payload *is* a picture — a Ring
`<camera>/snapshot/image` wired to the output.

Which images reach the chat is decided by `app/telegram_filter.include_image()` (pure,
tested in `tests/test_telegram_filter.py`):

- `[telegram] image_send_only_with_people` (default **true**) gates *unattended*
  camera frames: they are frequent and only interesting when someone is in them.
  `PERSON_ANNOTATIONS` holds **both vocabularies** — the model's labels (`person`,
  `face`) and the archive's class token (`human`) — and the match is a substring
  search over the composed text, never a position in a file name. A still that
  already passed detection elsewhere arrives as "<camera> <n> human(s)." (its class
  is relayed by the snapshot processor, see its `config.md`), so filtering on the
  model's labels alone silently dropped the photo *and* the caption.
- An image an input **explicitly sent** always passes. `Ring.getEventDetail()`
  reports `snapshot` for those, it travels in the device payload as `event_detail`,
  and `BotMessage.source` carries it. Do not weaken the flag for those — extend the
  filter.
- **A requested image always passes, unconditionally.** `/snapshot` opens a
  time-bounded delivery window for each camera it addressed
  (`app/snapshot_window.py`, `[telegram] snapshot_window_seconds`, default 60s), and
  `BotMessage.snapshot_requested` is stamped when the message is **queued** — not
  when it is sent, because `min_send_interval` can defer the send past the window.
  Inside the window every image for that camera is forwarded *as taken*, including
  the detected copy whose `event_detail` became "0 dog(s)"; the request is the
  operator's intent, so the detector cannot veto it. This is deliberately **not** a
  relaxation of `image_send_only_with_people` for unattended frames — those stay
  filtered.
- A suppressed message is dropped **whole**: no photo and no text. Never compose an
  image message whose text is the only signal.

The window is a window, not consume-once: one request can yield the raw frame *and*
the snapshot processor's annotated copy for the same label, and both must be sent.

Batching: messages are queued per `device_label` and the newest per label wins, so a
burst of snapshots collapses into one send; `[telegram] min_send_interval` then
rate-limits the queue.

---

## 6. `/snapshot` — requesting a camera picture

```
/snapshot                  # every camera that is online
/snapshot front gate       # one camera: label, partial label, or Ring device id
```

**Resolution.** The argument is matched against `GET /devices` where
`component == "camera"` (`app/snapshot_command.select_cameras`): exact label, then
case-insensitive substring, then the Ring device id. No match, or several matches,
is refused with the candidate list — never guess, and never wake a camera the
operator did not name. A blank argument means every camera: the Ring cameras the
processor knows that are not `offline`, **and** every camera output the snapshot
processor captures from. An offline camera is skipped and *reported*, not silently
dropped.

**Two kinds of camera, one command.** Ring cameras are MQTT devices, addressed by
publishing `PRESS` to the bridge. The others are *output* configurations that the
snapshot processor captures from on trigger; they never appear in `GET /devices`,
so the bot enumerates them itself (`snapshot_camera_outputs`, read under its own
Flask app context) and passes their keys to the processor, which publishes the
normal output trigger for each. The same window and the same trace cover both.

**Transport.** One `POST {event_processor_address}/snapshot` in an executor
(`device_key` only when one was named). The processor publishes the bridge's
`PRESS` and answers with `requested` and `skipped`, each skip naming a reason
(`offline`, `recently_requested` with `retry_after_seconds`, `not_a_camera`,
`unknown_location`, `publish_failed`). The reply states a partial result honestly;
never claim a camera the processor skipped.

**Delivery.** The handler opens a `SnapshotWindows` window for every camera in
`requested` — that is what lets the images past the detector (§5). Key it on the
**device key from the processor's reply**, never on the label or the typed argument:
a Ring picture carries the Ring device id, while a snapshot-processor picture comes
back under the camera's friendly label (`Pool`) although the request named its key
(`Pool Camera`). `BotMessage` therefore carries `device_key` as well as
`device_label`, and the queue-time check tries the key first. The window is
`[telegram] snapshot_window_seconds` (default 60s) and is stamped when a message is
queued, so a rate-limited send still counts.

**The picture is not the reply.** The command answers in text only. The image
arrives on the normal event path (`.../snapshot/image` → output link → chat) and may
also reach that camera's other links, e.g. an SMS. A handler must never fetch or
re-send the image itself — that duplicates the event path and sends it twice.

**Widening to other camera types** is a change to `is_camera()` plus a trigger for
that type; the window, the filter and the command contract are camera-agnostic.
Only Ring cameras are selectable today, because only the Ring bridge exposes an
on-demand trigger — a bare `/snapshot` therefore addresses the Ring camera(s) and
says nothing about any other camera, and it must not list non-cameras as skips
(one "not a camera" line per sensor buries the outcome).

**Trace.** The command's span is `telegram.snapshot`. The carrier is injected on
the event loop and sent with both Java calls (`_trace_headers()`), the processor
continues that trace in its `snapshot.request` span, and the picture — which comes
back through the bridge with no context — starts its own trace whose span *links*
to that request. Expect two traces joined by a link, not one long trace; see
`observability.md` §2–3.
