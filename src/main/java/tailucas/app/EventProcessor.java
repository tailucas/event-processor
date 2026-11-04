package tailucas.app;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.zeromq.ZMQ;

import com.github.dikhan.pagerduty.client.events.PagerDutyEventsClient;
import com.github.dikhan.pagerduty.client.events.domain.EventResult;
import com.github.dikhan.pagerduty.client.events.domain.Payload;
import com.github.dikhan.pagerduty.client.events.domain.ResolveIncident;
import com.github.dikhan.pagerduty.client.events.domain.Severity;
import com.github.dikhan.pagerduty.client.events.domain.TriggerIncident;
import com.github.dikhan.pagerduty.client.events.exceptions.NotifyEventException;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.StrictExceptionHandler;
import com.sun.management.UnixOperatingSystemMXBean;

import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics;
import io.sentry.Sentry;
import jakarta.annotation.PreDestroy;
import tailucas.app.device.Event;
import tailucas.app.message.Mqtt;
import tailucas.app.message.RabbitMq;
import tailucas.app.provider.DeviceConfig;
import tailucas.app.provider.Metrics;
import tailucas.app.provider.OnePassword;
import tailucas.app.provider.RingDiscovery;

import org.zeromq.ZContext;
import org.zeromq.SocketType;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.ini4j.Ini;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;


@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class EventProcessor
{
    public static final String ZMQ_MQTT_URL = "inproc://mqtt-send";
    private static final AtomicInteger exitCode = new AtomicInteger(1);

    public static final int EXIT_CODE_MQTT = 2;
    public static final int EXIT_CODE_RABBITMQ = 4;
    public static final int EXIT_CODE_CREDENTIALS = 8;
    public static final int EXIT_CODE_SENTRY = 16;
    public static final int EXIT_CODE_CONFIG_SERVER = 32;
    public static final int EXIT_CODE_RING_DISCOVERY = 64;

    public static final String FEATURE_FLAG_HA_DISCOVERY = "send-home-assistant-discovery";
    public static final String FEATURE_FLAG_PAGER_DUTY_TICKETS = "pager-duty-tickets";

    /** Counter for the outcome of the Ring discovery check (tag {@code result}). */
    private static final String RING_DISCOVERY_METRIC = "ring_discovery";
    /** How long a startup that failed the Ring check keeps watching for late information. */
    private static final Duration LATE_RING_INFORMATION_TIMEOUT = Duration.ofHours(12);

    private static final Map<String, Boolean> featureFlagCache = new ConcurrentHashMap<>();
    private static final AtomicBoolean ringDiscoveryIncidentRaised = new AtomicBoolean();

    private static final Logger log = LoggerFactory.getLogger(EventProcessor.class);
    private static ExecutorService srv = null;
    private static IMqttClient mqttClient = null;
    private static Channel rabbitMqChannel = null;
    private static Connection rabbitMqConnection = null;
    private static ZContext zmqContext = null;
    private static OnePassword creds = null;
    private static PagerDutyEventsClient pagerDuty = null;
    private static String pagerDutyRoutingKey = null;
    private static String appName = null;
    private static String deviceName = null;
    private static HTTPServer metricsServer = null;
    private static ScheduledExecutorService fdMetricsExecutor = null;

    @Bean
	public CommandLineRunner commandLineRunner(ApplicationContext ctx) {
		return args -> {
			String[] beanNames = ctx.getBeanDefinitionNames();
			Arrays.sort(beanNames);
			for (String beanName : beanNames) {
				log.atDebug().setMessage("Spring bean").addKeyValue("bean_name", beanName).log();
			}
		};
	}

    @Bean
    public ExitCodeGenerator exitCodeGenerator() {
        return () -> getExitCode();
    }

    public static int getExitCode() {
        return exitCode.get();
    }

    public static void addExitCode(int code) {
        exitCode.accumulateAndGet(code, (current, update) -> current | update);
    }

    @PreDestroy
    private void shutdown() {
        if (fdMetricsExecutor != null) {
            fdMetricsExecutor.shutdownNow();
        }
        if (zmqContext != null) {
            try {
                zmqContext.close();
            } catch (Exception e) {
                log.atWarn().setMessage("During shutdown of ZeroMQ context").setCause(e).log();
            }
        }
        if (rabbitMqChannel != null) {
            try {
                rabbitMqChannel.close();
            } catch (Exception e) {
                log.atWarn().setMessage("During shutdown of RabbitMQ channel").setCause(e).log();
            }
        }
        if (rabbitMqConnection != null) {
            try {
                rabbitMqConnection.close();
            } catch (Exception e) {
                log.atWarn().setMessage("During shutdown of RabbitMQ connection").setCause(e).log();
            }
        }
        if (mqttClient != null) {
            try {
                // this is a race condition but disconnect tends to block indefinitely
                // irrespective of timeout when a connect is still in progress (bug).
                if (mqttClient.isConnected()) {
                    mqttClient.disconnect(10);
                }
            } catch (MqttException e) {
                log.atWarn().setMessage("During disconnect of MQTT client").setCause(e).log();
            } finally {
                try {
                    mqttClient.close();
                } catch (MqttException e) {
                    log.atWarn().setMessage("During closing of MQTT client").setCause(e).log();
                }
            }
        }
        if (srv != null) {
            srv.shutdown();
        }
        DeviceConfig.getInstance().close();
        if (metricsServer != null) {
            metricsServer.close();
        }
        Severity severity = Severity.INFO;
        if (getExitCode() != 0) {
            severity = Severity.WARNING;
        }
        if (pagerDuty != null && isFeatureEnabled(FEATURE_FLAG_PAGER_DUTY_TICKETS)) {
            final Payload payload = Payload.Builder.newBuilder()
                .setSummary(String.format("%s shutdown", appName))
                .setSource(deviceName)
                .setSeverity(severity)
                .setTimestamp(OffsetDateTime.now())
                .build();
            final TriggerIncident incident = TriggerIncident.TriggerIncidentBuilder
                .newBuilder(pagerDutyRoutingKey, payload)
                .setDedupKey(appName)
                .build();
            try {
                final EventResult result = pagerDuty.trigger(incident);
                log.atInfo().setMessage("Updated PagerDuty")
                    .addKeyValue("pagerduty_status", result.getStatus())
                    .addKeyValue("pagerduty_message", result.getMessage())
                    .addKeyValue("pagerduty_errors", result.getErrors())
                    .log();
            } catch (NotifyEventException e) {
                log.atWarn().setMessage("Cannot update PagerDuty").setCause(e).log();
                Sentry.captureException(e);
            }
        } else {
            log.atWarn().setMessage("Not creating PagerDuty shutdown event, disabled with feature flag")
                .addKeyValue("feature_flag", FEATURE_FLAG_PAGER_DUTY_TICKETS)
                .log();
        }
        if (creds != null) {
            creds.close();
        }
        log.atInfo().setMessage("Full shutdown complete").addKeyValue("exit_code", getExitCode()).log();
        OtelSupport.shutdown();
    }

    public static boolean isFeatureEnabled(String featureName) {
        if (creds == null) {
            log.atDebug().setMessage("No credential provider available; feature flag evaluates to disabled")
                .addKeyValue("feature_flag", featureName)
                .log();
            return false;
        }
        return featureFlagCache.computeIfAbsent(featureName, key -> Boolean.valueOf(creds.getField("flags", "value", featureName)));
    }

    public static String getAppName() {
        return appName;
    }

    public static String getDeviceName() {
        return deviceName;
    }

    public static PagerDutyEventsClient getPagerDuty() {
        return pagerDuty;
    }

    public static String getPagerDutyRoutingKey() {
        return pagerDutyRoutingKey;
    }

    public static void main( String[] args ) {
        Thread.currentThread().setName("main");
        creds = OnePassword.getInstance();
        try {
            var vaults = creds.listVaults();
            if (vaults == null || vaults.size() == 0) {
                throw new IllegalStateException("No credential vaults are available.");
            }
            vaults.forEach(vault -> {
                log.atDebug().setMessage("Credential vault")
                    .addKeyValue("vault_id", vault.getId())
                    .addKeyValue("vault_name", vault.getName())
                    .addKeyValue("vault_description", vault.getDescription())
                    .log();
            });
            log.atInfo().setMessage("Using credential vault").addKeyValue("vault_id", creds.getVaultId()).log();
        } catch (Exception e) {
            log.atError().setMessage("Problem with credential client").setCause(e).log();
            addExitCode(EXIT_CODE_CREDENTIALS);
            System.exit(getExitCode());
        }
        final Map<String, String> envVars = System.getenv();
        appName = envVars.get("APP_NAME");
        try {
            final String sentryDsn = creds.getField("Sentry", "dsn", appName);
            Sentry.init(options -> {
                options.setDsn(sentryDsn);
                options.setTracesSampleRate(1.0);
            });
        } catch (AssertionError e) {
                log.atError().setMessage("Problem with credential item").setCause(e).log();
                addExitCode(EXIT_CODE_CREDENTIALS);
                System.exit(getExitCode());
        } catch (CompletionException e) {
            log.atError().setMessage("Problem with credential item").setCause(e.getCause()).log();
            addExitCode(EXIT_CODE_CREDENTIALS);
            System.exit(getExitCode());
        } catch (IllegalArgumentException e) {
            log.atError().setMessage("Problem with Sentry client").setCause(e).log();
            addExitCode(EXIT_CODE_SENTRY);
            System.exit(getExitCode());
        }
        log.atDebug().setMessage("Sentry status")
            .addKeyValue("sentry_enabled", Sentry.isEnabled())
            .addKeyValue("sentry_healthy", Sentry.isHealthy())
            .log();
        OtelSupport.init();
        pagerDuty = PagerDutyEventsClient.create();
        pagerDutyRoutingKey = creds.getField("PagerDuty", "routing_key", appName);
        deviceName = envVars.get("DEVICE_NAME");
        final String hostName = envVars.get("CONFIG_HOST");
        final String hostNamePort = envVars.get("CONFIG_HOST_PORT");
        UriComponents uriComponents = UriComponentsBuilder.newInstance()
            .scheme("http")
            .host(hostName)
            .port(hostNamePort)
            .path("/api/running")
            .build()
            .encode();
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        final URI startupUri = uriComponents.toUri();
        final HttpRequest request = HttpRequest.newBuilder()
            .GET()
            .uri(startupUri)
            .timeout(Duration.ofSeconds(2))
            .build();
        boolean ready = false;
        while (!ready) {
            try {
                log.atDebug().setMessage("Startup: testing readiness").addKeyValue("startup_uri", String.valueOf(startupUri)).log();
                HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
                final int responseCode = response.statusCode();
                final String responseBody = response.body();
                log.atInfo().setMessage("Startup: configuration source is ready").addKeyValue("response_body", responseBody).log();
                if (responseCode / 100 == 2) {
                    ready = Boolean.valueOf(responseBody).booleanValue();
                }
            } catch (IOException e) {
                log.atDebug().setMessage("Startup: not ready").setCause(e).log();
            } catch (Exception e) {
                log.atError().setMessage("Problem reaching config server").setCause(e).log();
                Sentry.captureException(e);
                System.exit(EXIT_CODE_CONFIG_SERVER);
            } finally {
                if (!ready) {
                    try {
                        Thread.sleep(2*1000);
                    } catch (InterruptedException e1) {
                        System.exit(0);
                    }
                }
            }
        }
        httpClient.close();
        final ApplicationContext springApp = SpringApplication.run(EventProcessor.class, args);
        final Environment springEnv = springApp.getEnvironment();
        final String applicationName = springEnv.getProperty("app.project-name");
        final Locale locale = Locale.getDefault();
        log.atDebug().setMessage("Runtime environment")
            .addKeyValue("app_name", applicationName)
            .addKeyValue("java_version", Runtime.version().toString())
            .addKeyValue("working_directory", System.getProperty("user.dir"))
            .addKeyValue("locale_language", locale.getLanguage())
            .addKeyValue("locale_country", locale.getCountry())
            .addKeyValue("environment", envVars.keySet())
            .log();
        // read application settings
        try {
            Ini appConfig = new Ini(new File("./app.conf"));
            log.atDebug().setMessage("App device name").addKeyValue("device_name", appConfig.get("app", "device_name")).log();
        } catch (IOException e) {
            log.atError().setMessage("Cannot read app configuration").setCause(e).log();
            Sentry.captureException(e);
        }

        srv = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("app-event-", 1).factory());
        ThreadFactory appThreadFactory = Thread.ofVirtual().name("app-", 1).factory();

        ConnectionFactory rabbitMqConnectionFactory = new ConnectionFactory();
        rabbitMqConnectionFactory.setHost(envVars.get("RABBITMQ_SERVER_ADDRESS"));
        rabbitMqConnectionFactory.setExceptionHandler(new StrictExceptionHandler() {
            @Override
            public void handleUnexpectedConnectionDriverException(Connection conn, Throwable exception) {
                log.atWarn().setMessage("Handling RabbitMQ connection exception").setCause(exception).log();
                super.handleUnexpectedConnectionDriverException(conn, exception);
                addExitCode(EXIT_CODE_RABBITMQ);
                log.atWarn().setMessage("Triggering shutdown").addKeyValue("exit_code", getExitCode()).log();
                System.exit(SpringApplication.exit(springApp));
            }
        });
        try {
            rabbitMqConnection = rabbitMqConnectionFactory.newConnection(srv);
        } catch (Exception e) {
            log.atError().setMessage("Problem with RabbitMQ client").setCause(e).log();
            Sentry.captureException(e);
            addExitCode(EXIT_CODE_RABBITMQ);
            System.exit(SpringApplication.exit(springApp));
        }

        // init statics
        final String controlExchangeName = springEnv.getProperty("app.message-control-exchange-name");
        if (controlExchangeName == null || controlExchangeName.length() == 0) {
            throw new IllegalStateException("Empty control exchange name during RabbitMQ client setup.");
        }
        Event.configure(controlExchangeName, springEnv.getProperty("app.message-control-expiry-ms"));

        Thread rabbitMqThread = appThreadFactory.newThread(() -> {
            try {
                final String exchangeName = springEnv.getProperty("app.message-event-exchange-name");
                if (exchangeName == null || exchangeName.length() == 0) {
                    throw new IllegalStateException("Empty exchange name during RabbitMQ client setup.");
                }
                rabbitMqChannel = rabbitMqConnection.createChannel();
                rabbitMqChannel.exchangeDeclare(exchangeName, BuiltinExchangeType.TOPIC);
                String queueName = rabbitMqChannel.queueDeclare().getQueue();
                rabbitMqChannel.queueBind(queueName, exchangeName, "#");
                rabbitMqChannel.basicConsume(queueName, true, new RabbitMq(srv, rabbitMqConnection), consumerTag -> { });
            } catch (Exception e) {
                log.atError().setMessage("Problem with RabbitMQ client").setCause(e).log();
                Sentry.captureException(e);
                addExitCode(EXIT_CODE_RABBITMQ);
                System.exit(SpringApplication.exit(springApp));
            }
        });

        zmqContext = new ZContext();
        Thread mqttThread = appThreadFactory.newThread(() -> {
            ZMQ.Socket socket = null;
            try {
                final String clientId = UUID.randomUUID().toString();
                mqttClient = new MqttClient(
                    String.format("tcp://%s:1883", envVars.get("MQTT_SERVER_ADDRESS")),
                    clientId,
                    new MemoryPersistence());
                final MqttConnectOptions options = new MqttConnectOptions();
                options.setAutomaticReconnect(true);
                options.setCleanSession(true);
                options.setConnectionTimeout(5);
                mqttClient.connect(options);
                mqttClient.setCallback(new Mqtt(springApp, srv, rabbitMqConnection));
                mqttClient.subscribe("#");
                if (isFeatureEnabled(FEATURE_FLAG_HA_DISCOVERY)) {
                    // send MQTT discovery message
                    final String mqttDiscoveryTopic = "homeassistant/status";
                    final String mqttDiscoveryPayload = "online";
                    try {
                        log.atInfo().setMessage("Sending Home Assistant discovery message")
                            .addKeyValue("payload", mqttDiscoveryPayload)
                            .addKeyValue("topic", mqttDiscoveryTopic)
                            .log();
                        mqttClient.publish(mqttDiscoveryTopic, new MqttMessage(mqttDiscoveryPayload.getBytes()));
                    } catch (MqttException e) {
                        log.atError().setMessage("Problem sending MQTT discovery message")
                            .addKeyValue("topic", mqttDiscoveryTopic)
                            .setCause(e)
                            .log();
                        Sentry.captureException(e);
                        addExitCode(EXIT_CODE_MQTT);
                        System.exit(SpringApplication.exit(springApp));
                    };
                } else {
                    log.atWarn().setMessage("Not sending Home Assistant discovery message, disabled with feature flag")
                        .addKeyValue("feature_flag", FEATURE_FLAG_HA_DISCOVERY)
                        .log();
                }
                // the subscription (and the discovery message above) is the point at
                // which Ring device information is expected to start arriving
                RingDiscovery.getInstance().markDiscoveryRequested();
                // use inproc socket in ZMQ to serialize outbound messages for thread safety
                socket = zmqContext.createSocket(SocketType.PULL);
                socket.connect(ZMQ_MQTT_URL);
                while (!zmqContext.isClosed()) {
                    try {
                        final byte[] zmqData = socket.recv();
                        log.atDebug().setMessage("ZMQ data received").addKeyValue("zmq_data", new String(zmqData)).log();
                    } catch (Exception e) {
                        if (!zmqContext.isClosed()) {
                            throw e;
                        }
                    }
                }
            } catch (Exception e) {
                log.atError().setMessage("Problem with MQTT client").setCause(e).log();
                Sentry.captureException(e);
                addExitCode(EXIT_CODE_MQTT);
                System.exit(SpringApplication.exit(springApp));
            } finally {
                if (socket != null) {
                    socket.close();
                }
            }
        });

        rabbitMqThread.start();
        mqttThread.start();

        if (isFeatureEnabled(FEATURE_FLAG_PAGER_DUTY_TICKETS)) {
            final ResolveIncident resolve = ResolveIncident.ResolveIncidentBuilder
                .newBuilder(pagerDutyRoutingKey, appName)
                .build();
            try {
                final EventResult result = pagerDuty.resolve(resolve);
                log.atInfo().setMessage("Updated PagerDuty")
                    .addKeyValue("pagerduty_status", result.getStatus())
                    .addKeyValue("pagerduty_message", result.getMessage())
                    .addKeyValue("pagerduty_errors", result.getErrors())
                    .log();
            } catch (NotifyEventException e) {
                log.atError().setMessage("Cannot update PagerDuty").setCause(e).log();
                Sentry.captureException(e);
            }
        } else {
            log.atWarn().setMessage("Not updating PagerDuty status, disabled with feature flag")
                .addKeyValue("feature_flag", FEATURE_FLAG_PAGER_DUTY_TICKETS)
                .log();
        }

        JvmMetrics.builder().register();
        Metrics.getInstance().postMetric("startup");
        final int metricsServerPort = springEnv.getProperty("metrics.port", Integer.class, 9400);
        try {
            metricsServer = HTTPServer.builder().port(metricsServerPort).buildAndStart();
        } catch (IOException e) {
            log.atError().setMessage("Cannot start metrics server").setCause(e).log();
            Sentry.captureException(e);
        }

        // export process file descriptor usage as Prometheus gauges so EMFILE
        // ("Too many open files") conditions are visible before they bite
        fdMetricsExecutor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("app-fd-metrics", 1).factory());
        fdMetricsExecutor.scheduleAtFixedRate(() -> {
            try {
                final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
                if (osBean instanceof UnixOperatingSystemMXBean unixOsBean) {
                    final double openFds = unixOsBean.getOpenFileDescriptorCount();
                    final double maxFds = unixOsBean.getMaxFileDescriptorCount();
                    Metrics.getInstance().postMetric("open_file_descriptors", openFds);
                    Metrics.getInstance().postMetric("max_file_descriptors", maxFds);
                    log.atDebug().setMessage("File descriptor metrics")
                        .addKeyValue("open_fds", openFds)
                        .addKeyValue("max_fds", maxFds)
                        .log();
                }
            } catch (Exception e) {
                log.atDebug().setMessage("Cannot post file descriptor metrics").setCause(e).log();
            }
        }, 0, 30, TimeUnit.SECONDS);

        log.atDebug().setMessage("Metrics server started").addKeyValue("metrics_port", metricsServerPort).log();
        final AppProperties appProperties = springApp.getBean(AppProperties.class);
        if (!awaitRingDeviceInformation(appProperties)) {
            if (appProperties.isRingDiscoveryRequired()) {
                log.atError().setMessage("Exiting, Ring device information is required")
                    .addKeyValue("timeout_seconds", appProperties.getRingDiscoveryTimeoutSeconds())
                    .log();
                addExitCode(EXIT_CODE_RING_DISCOVERY);
                System.exit(SpringApplication.exit(springApp));
            }
            // keep watching so the incident resolves when the bridge starts publishing
            Thread.ofVirtual().name("ring-discovery-watch").start(EventProcessor::awaitLateRingDeviceInformation);
        }
        log.atInfo().setMessage("Startup complete").addKeyValue("app_name", applicationName).log();
        exitCode.set(0);
    }

    /**
     * Waits for Ring device information to arrive over MQTT after discovery has
     * been requested, then raises a PagerDuty incident when the Ring bridge stays
     * silent. A bridge that cannot reach the Ring API publishes nothing at all,
     * so without this check the only symptom is an absence of Ring events.
     *
     * <p>A timeout of zero disables the check.
     *
     * @return true when Ring device information arrived, or the check is disabled
     */
    static boolean awaitRingDeviceInformation(AppProperties appProperties) {
        final int timeoutSeconds = appProperties.getRingDiscoveryTimeoutSeconds();
        if (timeoutSeconds <= 0) {
            log.atWarn().setMessage("Not awaiting Ring device information, disabled by configuration")
                .addKeyValue("setting", "app.ring-discovery-timeout-seconds")
                .log();
            return true;
        }
        final RingDiscovery ringDiscovery = RingDiscovery.getInstance();
        final Instant waitStart = Instant.now();
        final boolean informationSeen;
        try {
            informationSeen = ringDiscovery.awaitInformation(Duration.ofSeconds(timeoutSeconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.atWarn().setMessage("Interrupted while awaiting Ring device information").log();
            return false;
        }
        final long waitedSeconds = Duration.between(waitStart, Instant.now()).toSeconds();
        if (informationSeen) {
            log.atInfo().setMessage("Ring device information is available")
                .addKeyValue("topic", ringDiscovery.getInformationTopic())
                .addKeyValue("retained_only", ringDiscovery.isRetainedInformationOnly())
                .addKeyValue("waited_seconds", waitedSeconds)
                .log();
            Metrics.getInstance().postMetric(RING_DISCOVERY_METRIC, Map.of("result", "received"));
            return true;
        }
        log.atError().setMessage("No Ring device information after MQTT discovery")
            .addKeyValue("timeout_seconds", timeoutSeconds)
            .addKeyValue("waited_seconds", waitedSeconds)
            .log();
        Metrics.getInstance().postMetric(RING_DISCOVERY_METRIC, Map.of("result", "missing"));
        raiseRingDiscoveryIncident(timeoutSeconds);
        return false;
    }

    private static void awaitLateRingDeviceInformation() {
        try {
            if (RingDiscovery.getInstance().awaitInformation(LATE_RING_INFORMATION_TIMEOUT)) {
                log.atInfo().setMessage("Ring device information arrived after the startup check")
                    .addKeyValue("topic", RingDiscovery.getInstance().getInformationTopic())
                    .log();
                resolveRingDiscoveryIncident();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Raises the PagerDuty incident for a silent Ring bridge. The dedup key is
     * stable, so repeated restarts update a single incident instead of opening a
     * new one every time.
     */
    static void raiseRingDiscoveryIncident(int timeoutSeconds) {
        if (!isFeatureEnabled(FEATURE_FLAG_PAGER_DUTY_TICKETS)) {
            log.atWarn().setMessage("Not raising PagerDuty event for missing Ring device information, disabled with feature flag")
                .addKeyValue("feature_flag", FEATURE_FLAG_PAGER_DUTY_TICKETS)
                .log();
            return;
        }
        if (!ringDiscoveryIncidentRaised.compareAndSet(false, true)) {
            log.atDebug().setMessage("PagerDuty event for missing Ring device information was already raised")
                .addKeyValue("pagerduty_dedup_key", getRingDiscoveryDedupKey())
                .log();
            return;
        }
        final Payload payload = Payload.Builder.newBuilder()
            .setSummary(String.format("%s: no Ring device information after MQTT discovery", appName))
            .setSource(deviceName)
            .setSeverity(Severity.CRITICAL)
            .setTimestamp(OffsetDateTime.now())
            .build();
        final TriggerIncident incident = TriggerIncident.TriggerIncidentBuilder
            .newBuilder(pagerDutyRoutingKey, payload)
            .setDedupKey(getRingDiscoveryDedupKey())
            .build();
        try {
            final EventResult result = pagerDuty.trigger(incident);
            log.atInfo().setMessage("Updated PagerDuty")
                .addKeyValue("pagerduty_dedup_key", result.getDedupKey())
                .addKeyValue("pagerduty_status", result.getStatus())
                .addKeyValue("pagerduty_message", result.getMessage())
                .addKeyValue("pagerduty_errors", result.getErrors())
                .addKeyValue("timeout_seconds", timeoutSeconds)
                .log();
        } catch (NotifyEventException e) {
            log.atError().setMessage("Cannot update PagerDuty").setCause(e).log();
            Sentry.captureException(e);
        }
    }

    static void resolveRingDiscoveryIncident() {
        if (!ringDiscoveryIncidentRaised.compareAndSet(true, false)) {
            return;
        }
        if (!isFeatureEnabled(FEATURE_FLAG_PAGER_DUTY_TICKETS)) {
            log.atWarn().setMessage("Not resolving PagerDuty event for Ring device information, disabled with feature flag")
                .addKeyValue("feature_flag", FEATURE_FLAG_PAGER_DUTY_TICKETS)
                .log();
            return;
        }
        final ResolveIncident resolve = ResolveIncident.ResolveIncidentBuilder
            .newBuilder(pagerDutyRoutingKey, getRingDiscoveryDedupKey())
            .build();
        try {
            final EventResult result = pagerDuty.resolve(resolve);
            log.atInfo().setMessage("Updated PagerDuty")
                .addKeyValue("pagerduty_dedup_key", result.getDedupKey())
                .addKeyValue("pagerduty_status", result.getStatus())
                .addKeyValue("pagerduty_message", result.getMessage())
                .addKeyValue("pagerduty_errors", result.getErrors())
                .log();
        } catch (NotifyEventException e) {
            log.atError().setMessage("Cannot update PagerDuty").setCause(e).log();
            Sentry.captureException(e);
        }
    }

    private static String getRingDiscoveryDedupKey() {
        return String.format("%s-ring-discovery", appName);
    }
}
