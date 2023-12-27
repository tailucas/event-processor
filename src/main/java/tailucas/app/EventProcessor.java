package tailucas.app;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.zeromq.ZMQ;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;


@SpringBootApplication
public class EventProcessor
{
    private static Logger log = LoggerFactory.getLogger(EventProcessor.class);

    private static ExecutorService srv = null;

    private static void registerShutdownHook() {
        final Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread("shutdown hook") {
            public void run() {
                try {
                    if (srv != null) {
                        log.info("shutting down executor");
                        srv.shutdown();
                    }
                    log.info("triggered");
                    mainThread.join();
                } catch (InterruptedException ex) {
                    log.error(ex.getMessage(), ex);
                }
            }
        });
    }

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

    public static void main( String[] args )
    {
        SpringApplication.run(EventProcessor.class, args);
        Thread.currentThread().setName("main");
        final Locale locale = Locale.getDefault();
        final Map<String, String> envVars = System.getenv();
        log.info("{} starting event processor in working directory {}, locale language {}, country {} and environment {}", Runtime.version().toString(), System.getProperty("user.dir"), locale.getLanguage(), locale.getCountry(), envVars.keySet());
        registerShutdownHook();

        // read application settings
        try {
            Ini appConfig = new Ini(new File("./app.conf"));
            log.info("App Device Name: " + appConfig.get("app", "device_name"));
        } catch (IOException e) {
            log.error(e.getMessage());
        }

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

        srv = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("app-event-", 1).factory());
        ThreadFactory appThreadFactory = Thread.ofVirtual().name("app-", 1).factory();

        Thread mqttThread = appThreadFactory.newThread(() -> {
            IMqttClient mqttClient = null;
            try {
                final String clientId = UUID.randomUUID().toString();
                mqttClient = new MqttClient("tcp://192.168.0.5:1883", clientId, new MemoryPersistence());
                final MqttConnectOptions options = new MqttConnectOptions();
                options.setAutomaticReconnect(true);
                options.setCleanSession(true);
                options.setConnectionTimeout(10);
                mqttClient.connect(options);
                mqttClient.subscribe("#", (topic, msg) -> {
                    final byte[] payload = msg.getPayload();
                    final String payloadString = new String(payload);
                    srv.submit(new DeviceEvent(topic, Map.of("mqtt", Collections.singletonMap(topic, payloadString))));
                });
            } catch (MqttException e) {
                log.error(e.getMessage(), e);
            }
        });

        Thread rabbitMqThread = appThreadFactory.newThread(() -> {
            Channel channel = null;
            try {
                ConnectionFactory factory = new ConnectionFactory();
                factory.setHost("192.168.0.5");
                Connection connection = factory.newConnection();
                channel = connection.createChannel();
                final String EXCHANGE_NAME = "home_automation";
                channel.exchangeDeclare(EXCHANGE_NAME, "topic");
                String queueName = channel.queueDeclare().getQueue();
                channel.queueBind(queueName, EXCHANGE_NAME, "#");
                DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                    final byte[] msgBody = delivery.getBody();
                    ObjectMapper objectMapper = new MessagePackMapper();
                    var payloadObject = objectMapper.readValue(msgBody, new TypeReference<Map<String, Object>>() {});
                    log.debug("{}", payloadObject);
                    srv.submit(new DeviceEvent(delivery.getEnvelope().getRoutingKey(), payloadObject));
                };
                channel.basicConsume(queueName, true, deliverCallback, consumerTag -> { });
            } catch (IOException e) {
                log.error(e.getMessage(), e);
            } catch (TimeoutException e) {
                log.error(e.getMessage(), e);
            }
        });

        rabbitMqThread.start();
        mqttThread.start();

        MyClass myc = new MyClass("foo", 1.0, new String[]{"hello", "world"});
        myc.name();
        myc.score();

        socket.close();
        context.close();
        op.close();
    }
}
