package tailucas.app;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.zeromq.ZMQ;

import org.zeromq.ZContext;
import org.zeromq.SocketType;

import org.ini4j.Ini;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;


@SpringBootApplication
public class EventProcessor
{
    private static Logger log = LoggerFactory.getLogger(EventProcessor.class);

    private static void registerShutdownHook() {
        final Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread("shutdown hook") {
            public void run() {
                try {
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

			System.out.println("Let's inspect the beans provided by Spring Boot:");

			String[] beanNames = ctx.getBeanDefinitionNames();
			Arrays.sort(beanNames);
			for (String beanName : beanNames) {
				log.info("Bean: {}", beanName);
			}

		};
	}

    public static void main( String[] args )
    {
        final Locale locale = Locale.getDefault();
        log.info("Locale language: {} ", locale.getLanguage());
        log.info("Locale country: {}", locale.getCountry());
        Thread.currentThread().setName("main");
        registerShutdownHook();
        final Map<String, String> envVars = System.getenv();
        log.info("Starting application with env {}", envVars.keySet());

        OnePassword op = new OnePassword();
        op.getItems();

        final String javaVersion = Runtime.version().toString();
        ZContext context = new ZContext();
        ZMQ.Socket socket = context.createSocket(SocketType.PUSH);
        log.info( "Hello (print) " + javaVersion );
        log.trace("Hello (trace) {} ", javaVersion);
        log.debug("Hello (debug) {} ", javaVersion);
        log.info("Hello (info) {} ", javaVersion);
        log.error("Hello? (error) {}", javaVersion);
        socket.close();
        context.close();
        Set<Thread> threadSet = Thread.getAllStackTraces().keySet();
        for (Thread thread : threadSet) {
            log.info(thread + " daemon? " + thread.isDaemon());
        }
        log.info("Working directory is: " + System.getProperty("user.dir"));
        try {
            Ini appConfig = new Ini(new File("./app.conf"));
            log.info("App Device Name: " + appConfig.get("app", "device_name"));
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }

        log.info("Starting MQTT client...");
        Mqtt mqtt = new Mqtt();
        mqtt.start();

        log.info("Starting Rabbit MQ client...");
        RabbitMq rabbit = new RabbitMq();
        rabbit.start();

        MyClass myc = new MyClass("foo", 1.0, new String[]{"hello", "world"});
        myc.name();
        myc.score();

        SpringApplication.run(EventProcessor.class, args);

        try {
            Thread.currentThread().sleep(2000);
        } catch (InterruptedException e) {
            log.error(e.getMessage(), e);
        }
    }
}
