package tailucas.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.zeromq.ZMQ;
import org.zeromq.ZMQException;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

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
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.ini4j.Ini;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;


@SpringBootApplication
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

    @Bean
	public CommandLineRunner commandLineRunner(ApplicationContext ctx) {
		return args -> {
			String[] beanNames = ctx.getBeanDefinitionNames();
			Arrays.sort(beanNames);
			for (String beanName : beanNames) {
				log.info(beanName);
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
        creds = new OnePassword();
        creds.listVaults();
        final String sentryDsn = System.getenv("SENTRY_DSN");
        Sentry.init(options -> {
            options.setDsn(sentryDsn);
        });
        log.info("Sentry enabled: {}, healthy: {}.", Sentry.isEnabled(), Sentry.isHealthy());
        final ApplicationContext springApp = SpringApplication.run(EventProcessor.class, args);
        Thread.currentThread().setName("main");
        final Locale locale = Locale.getDefault();
        final Map<String, String> envVars = System.getenv();
        log.info("{} starting event processor in working directory {}, locale language {}, country {} and environment {}", Runtime.version().toString(), System.getProperty("user.dir"), locale.getLanguage(), locale.getCountry(), envVars.keySet());

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
                final String EXCHANGE_NAME = "home_automation";
                rabbitMqChannel.exchangeDeclare(EXCHANGE_NAME, BuiltinExchangeType.TOPIC);
                String queueName = rabbitMqChannel.queueDeclare().getQueue();
                rabbitMqChannel.queueBind(queueName, EXCHANGE_NAME, "#");
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
                options.setConnectionTimeout(10);
                mqttClient.connect(options);
                mqttClient.subscribe("#", new Mqtt(srv, rabbitMqConnection));
                // use inproc socket in ZMQ to serialize outbound messages for thread safety
                socket = zmqContext.createSocket(SocketType.PULL);
                socket.connect(ZMQ_MQTT_URL);
                while (!zmqContext.isClosed()) {
                    try {
                        final byte[] zmqData = socket.recv();
                        log.info("ZMQ data received {}", new String(zmqData));
                    } catch (ZMQException e) {
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
    }
}
