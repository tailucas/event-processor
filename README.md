<a name="readme-top"></a>

[![Contributors][contributors-shield]][contributors-url]
[![Forks][forks-shield]][forks-url]
[![Stargazers][stars-shield]][stars-url]
[![Issues][issues-shield]][issues-url]
[![MIT License][license-shield]][license-url]

## About The Project

### Overview

**Note 1**: See my write-up on [Home Security Automation][blog-url] which provides an architectural overview of how this project works with others in my collection.

**Note 2**: While I use the word *Automation* in these projects, there is no integration with sensible frameworks like [openHAB][oh-url] or [Home Assistant][ha-url]... not yet at least. The goal behind this project was a learning opportunity by employing some specific technologies and my opinion on design. The parts you'll most likely find useful are touch-points with third-party libraries like Flask, ZeroMQ and RabbitMQ, because seamless behavior comes after much trial and error.

This project hosts a basic event hub for my various automation applications. Others can be found in my [Snapshot Processor][snapshot-processor-url] and [Remote Monitor][remote-monitor-url] projects. It is designed with resource constraints in mind and so can run with no issues on a [Raspberry Pi][rpi-url].

The application is responsible for the following functions:

* Collects heartbeat messages from devices on a configured [RabbitMQ][rabbit-url] exchange to automatically register the device as an event input or output. A device can advertise both input and output devices on the same event source.
* Shows available output devices on a Web UI dashboard. Devices can be enabled (i.e. will trigger configured outputs) via either a button on the web page, or a configured schedule. The Web UI dashboard can be configured for reachability across either a free-tier [ngrok][ngrok-url] tunnel or via [Tailscale](https://tailscale.com/) if your network already has this set up.
* Shows available input-to-output linking options on the Web UI. Any known input/output combination can be created meaning that for a given set of inputs, a list of possible outputs is possible. Configuration is saved to a local [SQLite][sqlite-url] database, the schema for which can be found [here](https://github.com/tailucas/event-processor/blob/master/config/db_schema.sql). Database backups are created periodically on a cron job and are written to Amazon S3 with [this script](https://github.com/tailucas/event-processor/blob/master/backup_db.sh).
* Processes input messages and directs output triggers according to the configuration.
* A built-in *sms* output device type exists in the form of a Telegram bot. Optional fallback via SNS is supported.
* Supports MQTT messages with [mDash][mdash-url] device discovery.
* A crude audit trail exists in the local database.
* Updates from *meter* device types are written to a configured InfluxDB bucket.

This application extends my own [boilerplate application][baseapp-url] hosted in [docker hub][baseapp-image-url] and takes its own git submodule dependency on my own [package][pylib-url].

### Package Structure

This application has grown organically over a number of years, and so a fair amount of code has been factored out. The diagrams below show both the class inheritance structure. Here is the relationship between this project and my [pylib][pylib-url] submodule. For brevity, not everything is included such as the SQLAlchemy data-access-object classes, but those are mostly self-explanatory. These are the non-obvious relationships.

![classes](/../../../../tailucas/tailucas.github.io/blob/main/assets/event-processor/event-processor_classes.png)

* `EventProcessor` is responsible for the [main application loop](https://github.com/tailucas/event-processor/blob/master/app/__main__.py#L746-L792) and inherits [RabbitMQ][rabbit-url] message handling from `MQConnection`.
* `MQConnection` is responsible for connection to the RabbitMQ exchange and does channel management, error handing and shutdown. It inherits `AppThread` for [thread tracking](https://github.com/tailucas/pylib/blob/ac05d39592c2264143ec4a37fe76b7e0369515bd/pylib/app.py#L15) and `Closable` to [track and shutdown](https://github.com/tailucas/pylib/blob/ac05d39592c2264143ec4a37fe76b7e0369515bd/pylib/zmq.py#L46) all [ZeroMQ][zmq-url] sockets.
* `TBot`: Represents a Telegram bot wrapper that conforms to their `asyncio` paradigm.

See the diagram below for an example about how ZeroMQ is [used](https://github.com/tailucas/pylib/blob/ac05d39592c2264143ec4a37fe76b7e0369515bd/pylib/app.py#L26) as a message relay between threads.

![comms](/../../../../tailucas/tailucas.github.io/blob/main/assets/event-processor/event-processor_zmq_sockets.png)

* `HeartbeatFilter`: This is one of a variety of uses of ZeroMQ for internal message passing between threads in my applications. My [post][blog-url] goes into more detail. The filter is used as a [relay](https://github.com/tailucas/event-processor/blob/fa94393b835efa5de312ec182ba7dbae73bd60a3/app/__main__.py#L1732-L1749) for incoming devices messages: A message arrives from an MQTT or RabbitMQ event source, and then is forwarded to the filter to update its inventory of device-specific hearbeats. Thereafter, the message is forwarded to the `EventProcessor` instance for normal processing.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Built With

Technologies that help make this project useful:

[![1Password][1p-shield]][1p-url]
[![AWS][aws-shield]][aws-url]
[![Bootstrap][bootstrap-shield]][bootstrap-url]
[![Font Awesome][fontawesome-shield]][fontawesome-url]
[![Docker][docker-shield]][docker-url]
[![InfluxDB][influxdb-shield]][influxdb-url]
[![ngrok][ngrok-shield]][ngrok-url]
[![MQTT][mqtt-shield]][mqtt-url]
[![RabbitMQ][rabbit-shield]][rabbit-url]
[![Poetry][poetry-shield]][poetry-url]
[![Python][python-shield]][python-url]
[![Flask][flask-shield]][flask-url]
[![Sentry][sentry-shield]][sentry-url]
[![SQLite][sqlite-shield]][sqlite-url]
[![Telegram][telegram-shield]][telegram-url]
[![ZeroMQ][zmq-shield]][zmq-url]

Also:

* [mDash][mdash-url]
* [SQLAlchemy][sqlalchemy-url]

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- GETTING STARTED -->
## Getting Started

Here is some detail about the intended use of this project.

### Prerequisites

Beyond the Python dependencies defined in the [Poetry configuration](pyproject.toml), the project carries hardcoded dependencies on [Sentry][sentry-url] and [1Password][1p-url] in order to function.

### Required Tools
Install these tools and make sure that they are on the environment `$PATH`.

* `task` for project build orchestration: https://taskfile.dev/installation/#install-script

* `docker` and `docker-compose` for container builds and execution: https://docs.docker.com/engine/install/
* `mvn` Maven for Java build orchestration: https://maven.apache.org/download.cgi
* `poetry` for Python dependency management: https://python-poetry.org/docs/#installation

* `java` and `javac` for Java build and runtime: https://aws.amazon.com/corretto/
* `python` is `python3` for Python runtime: https://www.python.org/downloa

### Installation

0. :stop_sign: This project uses [1Password Secrets Automation][1p-url] to store both application key-value pairs as well as runtime secrets. It is assumed that the connect server containers are already running on your environment. If you do not want to use this, then you'll need to fork this package and make the changes as appropriate. It's actually very easy to set up, but note that 1Password is a paid product with a free-tier for secrets automation. Here is an example of how this looks for my application and the generation of the docker-compose.yml relies on this step. Your secrets automation vault must contain an entry called `ENV.event_processor` with these keys:

| Variable | Description | Example |
| --- | --- | --- |
| `APP_FLASK_DEBUG` | Web server debug | `false` |
| `APP_FLASK_HTTP_PORT` | Web server port | `8080` |
| `APP_NAME` | Application name used in logging and metrics | `event_processor` |
| `AWS_CONFIG_FILE` | AWS client configuration file | `/home/app/.aws/config` |
| `AWS_DEFAULT_REGION` | AWS region | `us-east-1` |
| `BACKUP_S3_BUCKET` | Bucket name for database backup | *project specific* |
| `CRONITOR_MONITOR_KEY` | [Cronitor][cronitor-url] configuration key | *project specific* |
| `DEVICE_NAME` | Used for container host name. | `event-processor-a` |
| `HC_PING_URL` | [Healthchecks][healthchecks-url] URL | *project specific* |
| `HEALTHCHECKS_BADGE_CSV` | [Healthchecks][healthchecks-url] badge | *project specific* |
| `INFLUXDB_BUCKET` | InfluxDB bucket for meter metrics | `meter` |
| `LEADER_ELECTION_ENABLED` | Use leader election for other instances of this application | `false` |
| `MDASH_API_BASE_URL` | mDash discovery API | `https://mdash.net/api/v2/devices` |
| `MDASH_APP_CONFIG_MQTT_PUB_TOPIC` | Application publish topic | `app.mqtt_pub_topic` |
| `MDASH_DEVICE_TAGS_CSV` | Only register devices with these mDash tags | `meter,sensor` |
| `MQTT_METER_RESET_TOPIC` | Topic to control reset of meter register | `meter/electricity/control` |
| `MQTT_PUB_TOPIC_CSV` | MQTT publication topics | `meter/electricity/#,sensor/garage/#` |
| `MQTT_SERVER_ADDRESS` | IP address of MQTT broker | *network specific* |
| `NGROK_CLIENT_API_PORT` | ngrok management port | `4040` |
| `NGROK_ENABLED` | Create ngrok tunnel with container | `true` |
| `NGROK_TUNNEL_NAME` | Tunnel name in configuration | `frontend` |
| `OP_CONNECT_SERVER` | 1Password connect server URL | *network specific* |
| `OP_CONNECT_TOKEN` | 1Password connect server token | *project specific* |
| `OP_VAULT` | 1Password vault | *project specific* |
| `OUTPUT_TYPE_BLUETOOTH` | Output type representing Bluetooth L2 ping | `l2ping` |
| `OUTPUT_TYPE_SNAPSHOT` | Output type for snapshots | `Camera` |
| `OUTPUT_TYPE_SWITCH` | Output types for switches | `switch,Buzzer` |
| `OUTPUT_TYPE_TTS` | Text-to-speech output type | `TTS` |
| `RABBITMQ_EXCHANGE` | Name of RabbitMQ exchange | `home_automation` |
| `RABBITMQ_SERVER_ADDRESS` | IP address of RabbitMQ exchange | *network specific* |
| `SNS_CONTROL_ENABLED` | Enable control messages from SQS | `false` |
| `SQS_QUEUE` | SQS queue name | `automation-control` |
| `TABLESPACE_PATH` | SQLite database for configuration | `/data/event_processor.db` |
| `TELEGRAM_CHAT_ROOM` | Telegram chat room ID | *project specific* |
| `TELEGRAM_IMAGE_SEND_ONLY_WITH_PEOPLE` | Send images only to humans | `true` |
| `TELEGRAM_SMS_FALLBACK_ENABLED` | Fall back to AWS SNS (SMS) | `false` |
| `TELEGRAM_USERS_CSV` | Permitted Telegram users to interact with bot (CSV) | *project specific* |
| `USER_TZ` | Set to override from `pytz.all_timezones` if not UTC. | *project specific* |

With these configured, you are now able to build the application. Any variables referenced in the [application configuration][appconf-url] will be automatically replaced.

In addition to this, [additional runtime configuration](https://github.com/tailucas/event-processor/blob/master/app/__main__.py#L58-L71) is used by the application, and also need to be contained within the secrets vault. With these configured, you are now able to run the application.

1. Clone the repo
   ```sh
   git clone https://github.com/tailucas/event-processor.git
   ```
2. Verify that the git submodule is present.
   ```sh
   git submodule init
   git submodule update
   ```
4. Make the Docker runtime user and set directory permissions. :hand: Be sure to first review the Makefile contents for assumptions around user IDs for Docker.
   ```sh
   task user
   ```
5. Now generate the docker-compose.yml:
   ```sh
   task setup
   ```
6. And generate the Docker image:
   ```sh
   task build
   ```
7. If successful and the local environment is running the 1Password connect containers, run the application. For foreground:
   ```sh
   task run
   ```
   For background:
   ```sh
   task rund
   ```

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- USAGE EXAMPLES -->
## Usage

Running the application will:

* Start the RabbitMQ client.
* Start the broker client.
* Start the auto-scheduler for devices that enable/disable on a desired schedule.
* Start the main application loop.
* Start discovery of MQTT event sources from mDash.
* Start the `asyncio` Telegram bot.
* Start an [instance](https://github.com/tailucas/pylib/blob/ac05d39592c2264143ec4a37fe76b7e0369515bd/pylib/threads.py#L59) of `pylib.threads.thread_nanny` which will notice and report on any thread death and will also help move the application to debug logging after a prolonged shutdown. Shutdown delay is normally as a result of failure to properly close all ZMQ sockets.
* Start the Flask web server.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Java Configuration

Suggested Java VM arguments to enable JMX profiling:
```
-Djava.net.preferIPv4Stack=true -Dcom.sun.management.jmxremote.host=127.0.0.1 -Dcom.sun.management.jmxremote.port=3333 -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.authenticate=false
```

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- LICENSE -->
## License

Distributed under the MIT License. See [LICENSE](LICENSE) for more information.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- ACKNOWLEDGMENTS -->
## Acknowledgments

* [Template on which this README is based](https://github.com/othneildrew/Best-README-Template)
* [All the Shields](https://github.com/progfay/shields-with-icon)

<p align="right">(<a href="#readme-top">back to top</a>)</p>

[![Hits](https://hits.seeyoufarm.com/api/count/incr/badge.svg?url=https%3A%2F%2Fgithub.com%2Ftailucas%2Fevent-processor%2F&count_bg=%2379C83D&title_bg=%23555555&icon=&icon_color=%23E7E7E7&title=visits&edge_flat=true)](https://hits.seeyoufarm.com)

<!-- MARKDOWN LINKS & IMAGES -->
<!-- https://www.markdownguide.org/basic-syntax/#reference-style-links -->
[contributors-shield]: https://img.shields.io/github/contributors/tailucas/event-processor.svg?style=for-the-badge
[contributors-url]: https://github.com/tailucas/event-processor/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/tailucas/event-processor.svg?style=for-the-badge
[forks-url]: https://github.com/tailucas/event-processor/network/members
[stars-shield]: https://img.shields.io/github/stars/tailucas/event-processor.svg?style=for-the-badge
[stars-url]: https://github.com/tailucas/event-processor/stargazers
[issues-shield]: https://img.shields.io/github/issues/tailucas/event-processor.svg?style=for-the-badge
[issues-url]: https://github.com/tailucas/event-processor/issues
[license-shield]: https://img.shields.io/github/license/tailucas/event-processor.svg?style=for-the-badge
[license-url]: https://github.com/tailucas/event-processor/blob/master/LICENSE

[blog-url]: https://tailucas.github.io/update/2023/06/18/home-security-automation.html
[appconf-url]: https://github.com/tailucas/event-processor/blob/master/config/app.conf
[baseapp-url]: https://github.com/tailucas/base-app
[baseapp-image-url]: https://hub.docker.com/repository/docker/tailucas/base-app/general
[pylib-url]: https://github.com/tailucas/pylib
[snapshot-processor-url]: https://github.com/tailucas/snapshot-processor
[remote-monitor-url]: https://github.com/tailucas/remote-monitor

[ha-url]: https://www.home-assistant.io/
[oh-url]: https://www.openhab.org/docs/

[1p-url]: https://developer.1password.com/docs/connect/
[1p-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=1Password&color=0094F5&logo=1Password&logoColor=FFFFFF&label=
[aws-url]: https://aws.amazon.com/
[aws-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=Amazon+AWS&color=232F3E&logo=Amazon+AWS&logoColor=FFFFFF&label=
[bootstrap-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=Bootstrap&color=7952B3&logo=Bootstrap&logoColor=FFFFFF&label=
[bootstrap-url]: https://getbootstrap.com/
[cronitor-url]: https://cronitor.io/
[docker-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=Docker&color=2496ED&logo=Docker&logoColor=FFFFFF&label=
[docker-url]: https://www.docker.com/
[fontawesome-url]: https://fontawesome.com/
[fontawesome-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=Font+Awesome&color=528DD7&logo=Font+Awesome&logoColor=FFFFFF&label=
[healthchecks-url]: https://healthchecks.io/
[influxdb-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=InfluxDB&color=22ADF6&logo=InfluxDB&logoColor=FFFFFF&label=
[influxdb-url]: https://www.influxdata.com/
[mdash-url]: https://mdash.net/home/
[mqtt-url]: https://mqtt.org/
[mqtt-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=MQTT&color=660066&logo=MQTT&logoColor=FFFFFF&label=
[ngrok-url]: https://ngrok.com/
[ngrok-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=ngrok&color=1F1E37&logo=ngrok&logoColor=FFFFFF&label=
[poetry-url]: https://python-poetry.org/
[poetry-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=Poetry&color=60A5FA&logo=Poetry&logoColor=FFFFFF&label=
[python-url]: https://www.python.org/
[python-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=Python&color=3776AB&logo=Python&logoColor=FFFFFF&label=
[flask-url]: https://flask.palletsprojects.com/
[flask-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=Flask&color=000000&logo=Flask&logoColor=FFFFFF&label=
[rabbit-url]: https://www.rabbitmq.com/
[rabbit-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=RabbitMQ&color=FF6600&logo=RabbitMQ&logoColor=FFFFFF&label=
[rpi-url]: https://www.raspberrypi.org/
[sentry-url]: https://sentry.io/
[sentry-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=Sentry&color=362D59&logo=Sentry&logoColor=FFFFFF&label=
[sqlalchemy-url]: https://www.sqlalchemy.org/
[sqlite-url]: https://www.sqlite.org/
[sqlite-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=SQLite&color=003B57&logo=SQLite&logoColor=FFFFFF&label=
[telegram-url]: https://telegram.org/
[telegram-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=Telegram&color=26A5E4&logo=Telegram&logoColor=FFFFFF&label=
[zmq-url]: https://zeromq.org/
[zmq-shield]: https://img.shields.io/static/v1?style=for-the-badge&message=ZeroMQ&color=DF0000&logo=ZeroMQ&logoColor=FFFFFF&label=
