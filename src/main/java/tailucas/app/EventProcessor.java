package tailucas.app;

import java.io.File;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.zeromq.ZMQ;

import com.github.dikhan.pagerduty.client.events.PagerDutyEventsClient;
import com.github.dikhan.pagerduty.client.events.domain.Payload;
import com.github.dikhan.pagerduty.client.events.domain.Severity;
import com.github.dikhan.pagerduty.client.events.domain.TriggerIncident;
import com.github.dikhan.pagerduty.client.events.exceptions.NotifyEventException;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.StrictExceptionHandler;

import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import jakarta.annotation.PreDestroy;
import tailucas.app.message.Mqtt;
import tailucas.app.message.RabbitMq;
import tailucas.app.provider.DeviceConfig;
import tailucas.app.provider.OnePassword;

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

    private static Logger log = LoggerFactory.getLogger(EventProcessor.class);
    private static ExecutorService srv = null;
    private static IMqttClient mqttClient = null;
    private static Channel rabbitMqChannel = null;
    private static Connection rabbitMqConnection = null;
    private static ZContext zmqContext = null;
    private static OnePassword creds = null;
    private static int exitCode = 0;

    private static final int EXIT_CODE_MQTT = 2;
    private static final int EXIT_CODE_RABBITMQ = 4;
    private static final int EXIT_CODE_CREDENTIALS = 8;
    private static final int EXIT_CODE_SENTRY = 16;

    @Bean
	public CommandLineRunner commandLineRunner(ApplicationContext ctx) {
		return args -> {
			String[] beanNames = ctx.getBeanDefinitionNames();
			Arrays.sort(beanNames);
			for (String beanName : beanNames) {
				log.debug(beanName);
			}
		};
	}

    @Bean
    public ExitCodeGenerator exitCodeGenerator() {
        return () -> exitCode;
    }

    @PreDestroy
    private void shutdown() {
        if (zmqContext != null) {
            try {
                zmqContext.close();
            } catch (Exception e) {
                log.warn("During shutdown of ZeroMQ context: {}", e.getMessage());
            }
        }
        if (rabbitMqChannel != null) {
            try {
                rabbitMqChannel.close();
            } catch (Exception e) {
                log.warn("During shutdown of RabbitMQ channel: {}", e.getMessage());
            }
        }
        if (rabbitMqConnection != null) {
            try {
                rabbitMqConnection.close();
            } catch (Exception e) {
                log.warn("During shutdown of RabbitMQ connection: {}", e.getMessage());
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
                log.warn("During disconnect of MQTT client: {}", e.getMessage());
            } finally {
                try {
                    mqttClient.close();
                } catch (MqttException e) {
                    log.warn("During closing of MQTT client: {}", e.getMessage());
                }
            }
        }
        if (srv != null) {
            srv.shutdown();
        }
        DeviceConfig.getInstance().close();
        if (creds != null) {
            creds.close();
        }
        log.info("Full shutdown complete.");
    }

    public static void main( String[] args )
    {
        Thread.currentThread().setName("main");
        creds = OnePassword.getInstance();
        try {
            var vaults = creds.listVaults();
            if (vaults == null || vaults.size() == 0) {
                throw new RuntimeException("No credential vaults are available.");
            }
            vaults.forEach(vault -> {
                log.info("Credential vault {}: is {} ({}).", vault.getId(), vault.getName(), vault.getDescription());
            });
            log.info("Using credential vault {}.", creds.getVaultId());
        } catch (Exception e) {
            log.error("Problem with credential client", e);
            exitCode |= EXIT_CODE_CREDENTIALS;
            System.exit(exitCode);
        }
        try {
            final String sentryDsn = creds.getField("Sentry", "dsn", "event-processor");
            Sentry.init(options -> {
                options.setDsn(sentryDsn);
            });
        } catch (AssertionError e) {
                log.error("Problem with credential item", e);
                exitCode |= EXIT_CODE_CREDENTIALS;
                System.exit(exitCode);
        } catch (CompletionException e) {
            log.error("Problem with credential item", e.getCause());
            exitCode |= EXIT_CODE_CREDENTIALS;
            System.exit(exitCode);
        } catch (IllegalArgumentException e) {
            log.error("Problem with Sentry client", e);
            exitCode |= EXIT_CODE_SENTRY;
            System.exit(exitCode);
        }
        log.info("Sentry enabled: {}, healthy: {}.", Sentry.isEnabled(), Sentry.isHealthy());
        final String hostName = "192.168.0.5";
        UriComponents uriComponents = UriComponentsBuilder.newInstance()
            .scheme("http")
            .host(hostName)
            .path("/api/running")
            .build()
            .encode();
        HttpRequest request = HttpRequest.newBuilder().GET().uri(uriComponents.toUri()).build();
        HttpClient httpClient = HttpClient.newHttpClient();
        boolean ready = false;
        while (!ready) {
            try {
                HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
                final int responseCode = response.statusCode();
                final String responseBody = response.body();
                log.info("Peer response is {}...", responseBody);
                if (responseCode % 200 == 0) {
                    ready = Boolean.valueOf(responseBody).booleanValue();
                }
            } catch (Exception e) {
                log.warn("Not ready for startup.");
            } finally {
                if (!ready) {
                    try {
                        Thread.sleep(10*1000);
                    } catch (InterruptedException e1) {
                        System.exit(1);
                    }
                }
            }
        }
        final ApplicationContext springApp = SpringApplication.run(EventProcessor.class, args);
        final Environment springEnv = springApp.getEnvironment();
        final Locale locale = Locale.getDefault();
        final Map<String, String> envVars = System.getenv();
        log.info("{} starting {} in working directory {}, locale language {}, country {} and environment {}",
            springEnv.getProperty("app.project-name"),
            Runtime.version().toString(),
            System.getProperty("user.dir"),
            locale.getLanguage(),
            locale.getCountry(),
            envVars.keySet());
        // read application settings
        try {
            Ini appConfig = new Ini(new File("./app.conf"));
            log.info("App Device Name: " + appConfig.get("app", "device_name"));
        } catch (IOException e) {
            log.error(e.getMessage());
        }

        srv = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("app-event-", 1).factory());
        ThreadFactory appThreadFactory = Thread.ofVirtual().name("app-", 1).factory();

        ConnectionFactory rabbitMqConnectionFactory = new ConnectionFactory();
        rabbitMqConnectionFactory.setHost("192.168.0.5");
        rabbitMqConnectionFactory.setExceptionHandler(new StrictExceptionHandler() {
            @Override
            public void handleUnexpectedConnectionDriverException(Connection conn, Throwable exception) {
                super.handleUnexpectedConnectionDriverException(conn, exception);
                exitCode |= EXIT_CODE_RABBITMQ;
                System.exit(SpringApplication.exit(springApp));
            }
        });
        try {
            rabbitMqConnection = rabbitMqConnectionFactory.newConnection(srv);
        } catch (Exception e) {
            log.error("Problem with RabbitMQ client", e);
            exitCode |= EXIT_CODE_RABBITMQ;
            System.exit(SpringApplication.exit(springApp));
        }

        Thread rabbitMqThread = appThreadFactory.newThread(() -> {
            try {
                rabbitMqChannel = rabbitMqConnection.createChannel();
                final String exchangeName = springEnv.getProperty("app.message-event-exchange-name");
                if (exchangeName == null || exchangeName.length() == 0) {
                    throw new RuntimeException("Empty exchange name during RabbitMQ client setup.");
                }
                rabbitMqChannel.exchangeDeclare(exchangeName, BuiltinExchangeType.TOPIC);
                String queueName = rabbitMqChannel.queueDeclare().getQueue();
                rabbitMqChannel.queueBind(queueName, exchangeName, "#");
                rabbitMqChannel.basicConsume(queueName, true, new RabbitMq(srv, rabbitMqConnection), consumerTag -> { });
            } catch (Exception e) {
                log.error("Problem with RabbitMQ client", e);
                exitCode |= EXIT_CODE_RABBITMQ;
                System.exit(SpringApplication.exit(springApp));
            }
        });

        zmqContext = new ZContext();
        Thread mqttThread = appThreadFactory.newThread(() -> {
            ZMQ.Socket socket = null;
            try {
                final String clientId = UUID.randomUUID().toString();
                mqttClient = new MqttClient("tcp://192.168.0.5:1883", clientId, new MemoryPersistence());
                final MqttConnectOptions options = new MqttConnectOptions();
                options.setAutomaticReconnect(true);
                options.setCleanSession(true);
                options.setConnectionTimeout(5);
                mqttClient.connect(options);
                mqttClient.setCallback(new Mqtt(springApp, srv, rabbitMqConnection));
                mqttClient.subscribe("#");
                // send MQTT discovery message
                final String mqttDiscoveryTopic = "homeassistant/status";
                final String mqttDiscoveryPayload = "online";
                try {
                    log.info("Sending Home Assistant discovery message ({}) to topic: {}", mqttDiscoveryPayload, mqttDiscoveryTopic);
                    mqttClient.publish(mqttDiscoveryTopic, new MqttMessage(mqttDiscoveryPayload.getBytes()));
                } catch (MqttException e) {
                    log.error("Problem sending MQTT discovery message to topic {}", mqttDiscoveryTopic, e);
                    exitCode |= EXIT_CODE_MQTT;
                    System.exit(SpringApplication.exit(springApp));
                };
                // use inproc socket in ZMQ to serialize outbound messages for thread safety
                socket = zmqContext.createSocket(SocketType.PULL);
                socket.connect(ZMQ_MQTT_URL);
                while (!zmqContext.isClosed()) {
                    try {
                        final byte[] zmqData = socket.recv();
                        log.info("ZMQ data received {}", new String(zmqData));
                    } catch (Exception e) {
                        if (!zmqContext.isClosed()) {
                            throw e;
                        }
                    }
                }
            } catch (MqttException e) {
                log.error("Problem with MQTT client", e);
                exitCode |= EXIT_CODE_MQTT;
                System.exit(SpringApplication.exit(springApp));
            } finally {
                if (socket != null) {
                    socket.close();
                }
            }
        });

        rabbitMqThread.start();
        mqttThread.start();

        ITransaction transaction = Sentry.startTransaction("meh", "task");
        try {
            throw new Exception("This is a test.");
        } catch (Exception e) {
            Sentry.captureException(e);
        }
        final ISpan span = transaction.getLatestActiveSpan();
        if (span != null) {
            span.setMeasurement("meh", 123);
        }
        transaction.setMeasurement("hello", 12);
        transaction.finish();

        PagerDutyEventsClient pagerDuty = PagerDutyEventsClient.create();
        Payload payload = Payload.Builder.newBuilder()
            .setSummary("Event Processor startup")
            .setSource("testing host")
            .setSeverity(Severity.INFO)
            .setTimestamp(OffsetDateTime.now())
            .build();
        final String pagerDutyRoutingKey = creds.getField("PagerDuty", "routing_key", "event-processor");
        TriggerIncident incident = TriggerIncident.TriggerIncidentBuilder
                .newBuilder(pagerDutyRoutingKey, payload)
                .setDedupKey(EventProcessor.class.getName())
                .build();
        try {
            pagerDuty.trigger(incident);
        } catch (NotifyEventException e) {
            log.error("Cannot trigger PagerDuty alert.", e);
        }
    }
}
