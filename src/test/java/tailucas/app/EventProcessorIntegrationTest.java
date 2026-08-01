package tailucas.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import com.github.dikhan.pagerduty.client.events.PagerDutyEventsClient;

import tailucas.app.provider.OnePassword;

/**
 * Boots the complete Spring Boot application on a random port and proves the service is
 * functional in principle: every bean is registered, configuration properties are bound
 * and validated, MVC/actuator annotations are processed, the HTTP API serves real
 * requests, and the context shuts down gracefully.
 *
 * <p>No external service is required or contacted:
 * <ul>
 *   <li><b>1Password Connect</b> - replaced at the static seam with a mock whose feature
 *       flags all evaluate to {@code "false"}; the application code otherwise only touches
 *       it from {@code main()} and {@code @PreDestroy}.</li>
 *   <li><b>PagerDuty</b> - replaced at the static seam with a mock that must never be
 *       invoked while feature flags are disabled (verified during shutdown).</li>
 *   <li><b>Sentry</b> - never initialized in tests, so {@code Sentry.captureException} is
 *       a no-op against an unconfigured hub.</li>
 *   <li><b>RabbitMQ (AMQP), Paho MQTT, ZeroMQ, Prometheus HTTP server</b> - all created
 *       procedurally in {@code main()}, which {@code @SpringBootTest} never executes, so
 *       none of these clients is ever constructed.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(OrderAnnotation.class)
class EventProcessorIntegrationTest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(REQUEST_TIMEOUT)
        .build();

    private static OnePassword onePassword;
    private static PagerDutyEventsClient pagerDuty;

    @Autowired
    private ApplicationContext ctx;

    @Value("${local.server.port}")
    private int port;

    @BeforeAll
    static void installExternalDependencyMocks() {
        // 1Password Connect mock: feature flags resolve to disabled without a server.
        onePassword = mock(OnePassword.class);
        when(onePassword.getField(anyString(), anyString(), anyString())).thenReturn("false");
        // PagerDuty mock: must remain untouched while feature flags are disabled.
        pagerDuty = mock(PagerDutyEventsClient.class);
        TestStatics.setStaticField(EventProcessor.class, "creds", onePassword);
        TestStatics.setStaticField(EventProcessor.class, "pagerDuty", pagerDuty);
        TestStatics.setStaticField(EventProcessor.class, "pagerDutyRoutingKey", "test-routing-key");
        TestStatics.setStaticField(EventProcessor.class, "appName", TestStatics.TEST_APP_NAME);
        TestStatics.setStaticField(EventProcessor.class, "deviceName", TestStatics.TEST_DEVICE_NAME);
    }

    @AfterAll
    static void clearStaticState() {
        TestStatics.setStaticField(EventProcessor.class, "creds", null);
        TestStatics.setStaticField(EventProcessor.class, "pagerDuty", null);
        TestStatics.setStaticField(EventProcessor.class, "pagerDutyRoutingKey", null);
        TestStatics.setStaticField(EventProcessor.class, "appName", null);
        TestStatics.setStaticField(EventProcessor.class, "deviceName", null);
        @SuppressWarnings("unchecked")
        final Map<String, Boolean> featureFlagCache =
            (Map<String, Boolean>) TestStatics.getStaticField(EventProcessor.class, "featureFlagCache");
        featureFlagCache.clear();
    }

    @Test
    @Order(1)
    void contextStartsAndRegistersAllBeans() {
        // MVC controllers from component scanning
        assertThat(ctx.containsBean("eventHandler")).isTrue();
        assertThat(ctx.containsBean("deviceConfigUpdate")).isTrue();
        // application configuration and lifecycle beans
        assertThat(ctx.containsBean("appConfig")).isTrue();
        assertThat(ctx.containsBean("commandLineRunner")).isTrue();
        assertThat(ctx.containsBean("exitCodeGenerator")).isTrue();
        assertThat(ctx.containsBean("produceLogger")).isTrue();
        // @ConfigurationProperties binding and validation (see src/test/resources/application.properties)
        final AppProperties props = ctx.getBean(AppProperties.class);
        assertThat(props.getProjectName()).isEqualTo("test_project");
        assertThat(props.getMessageControlExchangeName()).isEqualTo("test_control_exchange");
        assertThat(props.getMessageEventExchangeName()).isEqualTo("test_exchange");
        assertThat(props.getMessageControlExpiryMs()).isEqualTo(30000);
        // the injection-point logger factory is registered as a prototype-scoped bean
        final var loggerBeanDefinition = ((ConfigurableApplicationContext) ctx)
            .getBeanFactory().getBeanDefinition("produceLogger");
        assertThat(loggerBeanDefinition.getScope()).isEqualTo("prototype");
    }

    @Test
    @Order(2)
    void indexEndpointServesApplicationIdentity() throws Exception {
        final HttpResponse<String> response = get("/");
        assertThat(response.statusCode()).isEqualTo(200);
        final String body = response.body();
        assertThat(body).contains("spring.threads.virtual.enabled true");
        assertThat(body).contains("test_project");
        assertThat(body).contains("test_control_exchange");
        // the request was actually served on a virtual thread
        assertThat(body).contains("VirtualThread");
    }

    @Test
    @Order(3)
    void invalidateConfigEchoesDeviceKey() throws Exception {
        final HttpResponse<String> response = post("/invalidate_config?device_key=test_device");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("test_device");
    }

    @Test
    @Order(4)
    void invalidateConfigWithoutDeviceKeyIsRejected() throws Exception {
        final HttpResponse<String> response = post("/invalidate_config");
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @Order(5)
    void actuatorHealthEndpointReportsUp() throws Exception {
        final HttpResponse<String> response = get("/actuator/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    @Order(6)
    void applicationStartsServesAndShutsDownGracefullyWithoutExternalServices() throws Exception {
        // boot a dedicated, self-managed context exactly as main() does, on a free port
        final ConfigurableApplicationContext appCtx =
            SpringApplication.run(EventProcessor.class, "--server.port=0");
        try {
            final int appPort = appCtx.getEnvironment()
                .getProperty("local.server.port", Integer.class);
            // it serves HTTP before shutdown
            final HttpResponse<String> response = get(appPort, "/");
            assertThat(response.statusCode()).isEqualTo(200);
            // graceful shutdown: the container invokes @PreDestroy on all beans
            appCtx.close();
            // the credential client was closed ...
            verify(onePassword).close();
            // ... without contacting PagerDuty, since all feature flags are disabled
            verifyNoInteractions(pagerDuty);
            // the web server stopped accepting connections
            assertThat(awaitPortClosed(appPort))
                .as("port %s refuses connections after graceful shutdown", appPort)
                .isTrue();
        } finally {
            if (appCtx.isActive()) {
                appCtx.close();
            }
        }
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return get(port, path);
    }

    private HttpResponse<String> get(int serverPort, String path) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
            .GET()
            .uri(uri(serverPort, path))
            .timeout(REQUEST_TIMEOUT)
            .build();
        return HTTP_CLIENT.send(request, BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder()
            .POST(HttpRequest.BodyPublishers.noBody())
            .uri(uri(port, path))
            .timeout(REQUEST_TIMEOUT)
            .build();
        return HTTP_CLIENT.send(request, BodyHandlers.ofString());
    }

    private URI uri(int serverPort, String path) {
        return URI.create(String.format("http://127.0.0.1:%d%s", serverPort, path));
    }

    private static boolean awaitPortClosed(int port) throws InterruptedException {
        final long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket ignored = new Socket("127.0.0.1", port)) {
                // still accepting connections; wait and retry
                Thread.sleep(100);
            } catch (ConnectException e) {
                return true;
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
        return false;
    }
}
