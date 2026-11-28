---
paths:
  - "app/**"
  - "src/**"
  - "internal/**"
  - "rapp/**"
  - "rlib/**"
  - "pyproject.toml"
  - "pom.xml"
---

# base-app Messaging Patterns (ZeroMQ, RabbitMQ, MQTT)

base-app uses three messaging technologies for three different jobs. They are
**not interchangeable** and are documented in separate sections below. The
guiding rule is to match the transport to the boundary it crosses:

- **ZeroMQ** moves data *inside* a process, between threads, with no broker.
- **RabbitMQ** moves data *between* processes/services, through a broker.
- **MQTT** moves data *to/from constrained devices*, through a lightweight
  broker, over lossy or low-bandwidth links.

The Python implementations live in `app/__main__.py` and the shared
`tailucas_pylib` dependency (`zmq.py`, `app.py`, `rabbit.py`). The `mq` extra
in `pyproject.toml` pulls `pika` (RabbitMQ) and `pyzmq` (ZeroMQ).

In **this** project MQTT is not hypothetical: the Java event processor is the
MQTT edge (§3), RabbitMQ carries events between the Java core and the Python web
app, and ZeroMQ is in-process on both sides. The Python patterns below are the
reference; §1 also documents the Java in-process queue that serialises outbound
MQTT publishes.

---

## 1. ZeroMQ — in-process pipeline, relay, and RPC

ZeroMQ is the default inter-thread transport in base-app. It is a **brokerless
socket library**, not a message broker: there is no server, no queue
persistence, and no delivery guarantee beyond what the socket pattern itself
provides. In this project it is used over the `inproc://` transport, which
means messages never leave the process — they are handed between threads
through memory, giving "lockless" communication with no shared mutable state.

### The pipeline pattern (PUSH/PULL)

The canonical example in `app/__main__.py` is a three-stage pipeline:

```
DataReader (PUSH) ──► DataRelay (PULL→PUSH) ──► EventProcessor (PULL)
```

- A **producer** (`DataReader`) binds/connects a `PUSH` socket and sends
  messages downstream.
- A **consumer** (`EventProcessor`) binds a `PULL` socket and blocks in
  `recv_pyobj()`.
- PUSH/PULL is **one-way and load-balancing**: with multiple PULL peers,
  ZeroMQ round-robins messages among them. It is fire-and-forget — there is no
  acknowledgement, so a stage that must not lose work should not rely on PUSH
  alone.

The socket lifecycle is centralized in `exception_handler` (a context
manager): it decides **bind vs. connect** by socket type (`PULL`/`PUB`/`REP`
bind; `PUSH`/`REQ` connect), creates the socket, and guarantees teardown on
exit or error. `zmq_term()` shuts the shared context down at process exit.

### The relay pattern (`ZmqRelay`)

A relay is a stage that **receives on one socket and forwards on another**,
optionally transforming the message in between. `tailucas_pylib.app.ZmqRelay`
encodes this as a base class: a source socket (PULL) plus a sink socket
(PUSH), with a `process_message(sink_socket)` hook that subclasses override.
`DataRelay` in `app/__main__.py` is the concrete example — it receives a
message, re-injects a refreshed trace context, and forwards it.

This is the "processing relay" pattern: a relay is a **transform stage**, not
a router. It is the natural place to enrich, filter, or re-encode a message
as it moves through a pipeline. Generalize it by subclassing `ZmqRelay` and
implementing only `process_message`; the base class owns the socket plumbing
and the run loop.

### The RPC pattern (REQ/REP)

ZeroMQ also provides request/reply via `REQ`/`REP` sockets, exposed in
`tailucas_pylib.app.ZmqWorker`. A worker binds a `REP` socket, receives a
request object, computes a response, and sends it back; a client uses a `REQ`
socket. This is a **synchronous, one-request-one-reply** contract — the
client blocks until the reply arrives, and the worker must reply before it
can receive again. Use it for in-process command/query style interactions
where a return value is required, not for fire-and-forget data flow.

### The Java outbound queue (`inproc://mqtt-send`)

The Java side uses the same pattern for the opposite direction: a `PUSH` socket
feeds a `PULL` socket that owns a resource. `EventProcessor.publishMqtt(topic,
payload)` is the only public way to publish MQTT — the MQTT client is not thread
safe, so the MQTT thread consumes the queue and performs every
`mqttClient.publish` itself.

- **The consumer binds.** `inproc` requires the endpoint to exist before a
  publisher connects, so the long-lived MQTT thread binds the `PULL` at startup
  and request-time publishers connect a short-lived `PUSH`. (This matches the
  Python convention in `exception_handler`, where `PULL`/`PUB`/`REP` bind.)
- **Framing is `topic\npayload`**, split on the *first* newline only, so a payload
  may contain newlines. An empty topic or a frame without a separator is logged
  and dropped rather than throwing: one bad message must not kill the thread that
  also carries every device event.
- **A publisher never blocks a request.** The `PUSH` sets a send timeout, so a dead
  pipeline returns `false` (reported to the caller as "publish failed") instead of
  hanging the HTTP request.
- **Symptom to remember:** an `inproc` endpoint that is only ever *connected* to,
  never bound, accepts sends silently and delivers nothing. That is what
  `inproc://mqtt-send` did before the snapshot command needed it — the socket
  existed and logged nothing, which looked like "no messages" rather than a bug.

### Trace context travels with the message

Because ZeroMQ has no broker to carry metadata, any cross-cutting context
(e.g. W3C trace context) must be **embedded in the payload itself**. In
`app/__main__.py` the `PipelineMessage` dataclass carries a
`trace_context: dict[str, str]` field; producers `inject` into it, relays
`extract` then re-`inject` a refreshed context, and consumers `extract` it to
parent their span. Generalize this by keeping the carrier a plain
string-keyed dict on the message object, so it pickles cleanly and accepts
new entries without a schema change.

---

## 2. RabbitMQ — brokered messaging between services

RabbitMQ is the **broker** option: a standalone server that accepts messages
from producers and routes them to consumers via **exchanges** and **queues**.
Unlike ZeroMQ, it provides persistence, acknowledgements, and routing
semantics, and it decouples producers from consumers in time and space. In
base-app it is implemented in `tailucas_pylib.rabbit.py` using `pika`.

### The topic-exchange pattern

The core abstraction is `MQConnection`, which wraps a `pika.BlockingConnection`
and a **topic exchange**. Producers publish to the exchange with a
**routing key** (a dot-separated topic string); consumers declare an
exclusive queue, bind it to the exchange with a **topic filter** (e.g. `#` for
everything, or `device.*` for a subset), and consume. The broker does the
routing — the producer never knows who is listening.

Connection and channel lifecycle is handled defensively: connections and
channels are recreated lazily when closed, and publishes retry on
`StreamLostError` before giving up. This resilience matters because a broker
connection is a long-lived, failure-prone resource, unlike an in-process
ZeroMQ socket.

### The bridge pattern (ZMQ ↔ RabbitMQ)

base-app treats RabbitMQ as an **external boundary** and bridges it to the
internal ZeroMQ pipeline. Two classes express this:

- `ZMQListener` — consumes from RabbitMQ and forwards into ZeroMQ (broker →
  in-process). It unpacks the MessagePack body and pushes a Python object
  onto a ZMQ socket.
- `RabbitMQRelay` — receives from ZeroMQ and publishes to RabbitMQ
  (in-process → broker). It reads a `(topic, payload)` tuple from a PULL
  socket and publishes it to the exchange.

This is the general pattern for integrating a broker with an internal
pipeline: **keep the broker at the edge**, and translate at the boundary
rather than letting broker concepts leak into the core. Payloads are encoded
with MessagePack (`umsgpack`) for compact, cross-language-safe transport.

---

## 3. MQTT — lightweight pub/sub for constrained devices

MQTT is the **device-facing** transport, and in this project it is real: the Java
event processor runs a paho `MqttClient` and subscribes to everything (`#`),
while the Ring bridge publishes device state and images onto the same broker. The
Python web app never speaks MQTT — it reaches the broker only through the Java
REST surface, so there is one client, one topic vocabulary, and one place where
MQTT quirks live. (base-app itself does not use MQTT; do not add a second client
to the Python side.)

MQTT is a **publish/subscribe** protocol designed for constrained
environments: small code footprint, low bandwidth, and tolerance for
unreliable networks. Clients publish to **topics** and subscribe to topic
filters; a broker relays messages between them. Its distinguishing features
are **QoS levels** (0 = at most once, 1 = at least once, 2 = exactly once),
**retained messages** (the last message on a topic is stored and delivered to
new subscribers), and **last-will** messages (published on behalf of a client
that disconnects unexpectedly).

### When MQTT is the right choice

MQTT overlaps with RabbitMQ's pub/sub capability, but the two are aimed at
different problems. Prefer MQTT when:

- the producers/consumers are **embedded or battery-powered devices** with
  limited CPU, memory, or network,
- the link is **lossy or intermittent** (QoS 1/2 and the client's
  keep-alive/reconnect behavior matter),
- you need **retained state** so late subscribers see the latest value.

Prefer RabbitMQ when you need richer routing (exchanges, bindings, headers),
durable queues, acknowledgements, or integration with an existing AMQP
ecosystem. Prefer ZeroMQ when the communication is **in-process** and you
want no broker at all.

### How it is used here — the Ring bridge contract

The bridge (`tsightler/ring-mqtt`) publishes every device under a location-scoped
topic:

```
ring/<location_id>/<category>/<device_id>/<subject>/<kind>
```

`<category>` is `camera`, `alarm`, `chime` or `lighting`; `<kind>` is `state`,
`attributes` (JSON), `image` (raw bytes) or `command` (the control path).
`Ring.setMqttTopic()` parses parts 1/2/3 as location, category and device, and
`DeviceState` merges the newest value per device — that is what `GET /devices` and
the Telegram `/ring` report read.

Commands belong to the bridge: write to `<subject>/command` and it acts.

| Command | Payload | Effect |
|---|---|---|
| `<camera>/take_snapshot/command` | `PRESS` (exact) | publishes `<camera>/snapshot/image` (raw JPEG), then `<camera>/snapshot/attributes` with `{"type":"on-demand"}` |

Facts worth not rediscovering:

- **`PRESS` is the only accepted payload**, and a second request within **10s per
  camera** is ignored silently (debug log only). `POST /snapshot` therefore tracks
  the last request per device and reports `recently_requested` with a
  `retry_after_seconds`, instead of claiming a snapshot that will never arrive.
- **On-demand is independent of `snapshot_mode`** (`Auto` is the usual setting).
  The bridge only refuses when `snapshotsAreBlocked` (motion capture disabled).
  `snapshot_mode` governs the *unattended* snapshots — interval, motion, ding —
  not the button.
- **A snapshot carries bytes, not state.** `snapshot/image` is a whole JPEG, so the
  processor keeps it out of logs, spans and metric labels (`PayloadType.detect`,
  `Ring.setBinaryPayload`) and forwards it only as the trigger's payload. Never log
  or label a device with an image body.
- **The image topic is also a heartbeat**, so output links are evaluated for it like
  any other event: a bot-requested snapshot also reaches every other linked output
  (which is why a `/snapshot` in the chat can produce an SMS as well). Keep delivery
  a link decision; do not special-case the bot path in `Event`.
