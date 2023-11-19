package tailucas.app;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.zeromq.ZMQ;

import org.zeromq.ZContext;
import org.zeromq.SocketType;

import org.ini4j.Ini;


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

    public static void main( String[] args )
    {
        final Locale locale = Locale.getDefault();
        log.info("Locale language: {} ", locale.getLanguage());
        log.info("Locale country: {}", locale.getCountry());
        Thread.currentThread().setName("main");
        registerShutdownHook();
        final Map<String, String> envVars = System.getenv();
        log.info("Starting application with env {}", envVars.keySet());

        /*
        OnePassword op = new OnePassword();
        op.getItems();
        */

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

        /*
        log.info("Starting MQTT client...");
        Mqtt mqtt = new Mqtt();
        mqtt.start();

        log.info("Starting Rabbit MQ client...");
        RabbitMq rabbit = new RabbitMq();
        rabbit.start();

        MyClass myc = new MyClass("foo");
        myc.getAge();
        */

        try {
            Thread.currentThread().sleep(2000);
        } catch (InterruptedException e) {
            log.error(e.getMessage(), e);
        }
    }
}
