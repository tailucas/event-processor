package tailucas.app;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.zeromq.ZMQ;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

import jakarta.annotation.PreDestroy;

import org.zeromq.ZContext;
import org.zeromq.SocketType;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.ini4j.Ini;
import org.msgpack.jackson.dataformat.MessagePackMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;


@SpringBootApplication
public class EventProcessor
{
    private static Logger log = LoggerFactory.getLogger(EventProcessor.class);
    private static ExecutorService srv = null;
    private static IMqttClient mqttClient = null;
    private static Channel rabbitMQChannel = null;
    private static int exitCode = 0;

    private static final int EXIT_CODE_MQTT = 2;
    private static final int EXIT_CODE_RABBITMQ = 4;

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
        if (rabbitMQChannel != null) {
            try {
                rabbitMQChannel.close();
            } catch (Exception e) {
                log.warn("During shutdown of RabbitMQ client: {}", e.getMessage());
            }
        }
        if (srv != null) {
            srv.shutdown();
        }
        log.info("Event handler shutdown complete.");
    }

    public static void main( String[] args )
    {
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

        Thread mqttThread = appThreadFactory.newThread(() -> {
            try {
                final String clientId = UUID.randomUUID().toString();
                mqttClient = new MqttClient("tcp://192.168.0.5:1883", clientId, new MemoryPersistence());
                final MqttConnectOptions options = new MqttConnectOptions();
                options.setAutomaticReconnect(true);
                options.setCleanSession(true);
                options.setConnectionTimeout(10);
                mqttClient.connect(options);
                mqttClient.subscribe("#", (topic, msg) -> {
                    try {
                        final byte[] payload = msg.getPayload();
                        DeviceEvent deviceEvent = null;
                        if (payload.length == 2 && payload[0] == 'O' && payload[1] == 'K') {
                            deviceEvent = new DeviceEvent(topic, DeviceEvent.SourceType.MQTT, payload.toString());
                        } else {
                            ObjectMapper mapper = new ObjectMapper();
                            Map<?,?> mqttUpdate = mapper.readerFor(new TypeReference<LinkedHashMap<?,?>>() { }).readValue(payload);
                            deviceEvent = new DeviceEvent(topic, DeviceEvent.SourceType.MQTT, mqttUpdate);
                        }
                        srv.submit(deviceEvent);
                    } catch (Exception e) {
                        log.warn(e.getMessage());
                    }
                });
            } catch (MqttException e) {
                log.error("Problem with MQTT client", e);
                exitCode |= EXIT_CODE_MQTT;
                System.exit(SpringApplication.exit(springApp));
            }
        });

        Thread rabbitMqThread = appThreadFactory.newThread(() -> {
            try {
                ConnectionFactory factory = new ConnectionFactory();
                factory.setHost("192.168.0.5");
                Connection connection = factory.newConnection();
                rabbitMQChannel = connection.createChannel();
                final String EXCHANGE_NAME = "home_automation";
                rabbitMQChannel.exchangeDeclare(EXCHANGE_NAME, "topic");
                String queueName = rabbitMQChannel.queueDeclare().getQueue();
                rabbitMQChannel.queueBind(queueName, EXCHANGE_NAME, "#");
                DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                    try {
                        final String source = delivery.getEnvelope().getRoutingKey();
                        final byte[] msgBody = delivery.getBody();
                        ObjectMapper mapper = new MessagePackMapper();
                        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
                        DeviceUpdate deviceUpdate = mapper.readerFor(new TypeReference<DeviceUpdate>() { }).readValue(msgBody);
                        if (deviceUpdate.outputs_triggered() != null) {
                            deviceUpdate.outputs_triggered().forEach(device -> {
                                srv.submit(new DeviceEvent(source, DeviceEvent.SourceType.RABBITMQ, device, deviceUpdate));
                            });
                        } else {
                            srv.submit(new DeviceEvent(source, DeviceEvent.SourceType.RABBITMQ, deviceUpdate));
                        }
                    } catch (Exception e) {
                        log.warn(e.getMessage());
                    }
                };
                rabbitMQChannel.basicConsume(queueName, true, deliverCallback, consumerTag -> { });
            } catch (Exception e) {
                log.error("Problem with RabbitMQ client", e);
                exitCode |= EXIT_CODE_RABBITMQ;
                System.exit(SpringApplication.exit(springApp));
            }
        });

        rabbitMqThread.start();
        mqttThread.start();

        OnePassword op = new OnePassword();
        //op.getItems();
        op.listVaults();

        ZContext context = new ZContext();
        ZMQ.Socket socket = context.createSocket(SocketType.PUSH);
        log.info( "Hello (print) {}", "world");
        log.trace("Hello (trace) {} ", "world");
        log.debug("Hello (debug) {} ", "world");
        log.info("Hello (info) {} ", "world");
        log.error("Hello? (error) {}", "world");

        MyClass myc = new MyClass("foo", 1.0, new String[]{"hello", "world"});
        myc.name();
        myc.score();

        socket.close();
        context.close();
        op.close();
    }
}
