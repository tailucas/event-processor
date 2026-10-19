package tailucas.app.provider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Publishes processed lifecycle events to the Python hub API.
 *
 * <p>The publisher is intentionally non-blocking: events are sent with
 * {@link HttpClient#sendAsync} so the virtual-thread event pipeline is never
 * held up by a slow or unavailable hub. A missing token or config host simply
 * disables publishing after one warning.</p>
 */
public final class HubEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(HubEventPublisher.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private static final AtomicBoolean warned = new AtomicBoolean(false);

    private static volatile String token;
    private static volatile String endpoint;

    private HubEventPublisher() {
    }

    private static String readToken() {
        if (token != null) {
            return token;
        }
        final String configured = System.getenv("HUB_API_TOKEN");
        if (configured != null && !configured.isBlank()) {
            token = configured.trim();
            return token;
        }
        final String tokenFile = System.getenv().getOrDefault("HUB_API_TOKEN_FILE", "/data/hub_api_token");
        try {
            token = Files.readString(Path.of(tokenFile), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            log.atDebug().setMessage("Hub API token not available")
                    .addKeyValue("token_file", tokenFile)
                    .setCause(e)
                    .log();
            token = "";
        }
        return token;
    }

    private static String endpoint() {
        if (endpoint != null) {
            return endpoint;
        }
        final String host = System.getenv("CONFIG_HOST");
        final String port = System.getenv("CONFIG_HOST_PORT");
        if (host == null || host.isBlank() || port == null || port.isBlank()) {
            return null;
        }
        endpoint = String.format("http://%s:%s/api/v1/events", host, port);
        return endpoint;
    }

    public static boolean isEnabled() {
        final String value = readToken();
        return value != null && !value.isBlank() && endpoint() != null;
    }

    public static void publish(String type, String deviceKey, Map<String, Object> data) {
        if (!isEnabled()) {
            if (warned.compareAndSet(false, true)) {
                log.atWarn().setMessage("Hub event publishing disabled; token or config host missing")
                        .addKeyValue("event_type", type)
                        .log();
            }
            return;
        }

        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        if (deviceKey != null) {
            payload.put("device_key", deviceKey);
        }
        payload.put("timestamp", Instant.now().toString());
        payload.put("data", data == null ? Map.of() : data);

        try {
            final String body = mapper.writeValueAsString(payload);
            final HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint()))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, error) -> {
                        if (error != null) {
                            log.atWarn().setMessage("Cannot publish hub event")
                                    .addKeyValue("event_type", type)
                                    .addKeyValue("device_key", deviceKey)
                                    .setCause(error)
                                    .log();
                        } else if (response.statusCode() / 100 != 2) {
                            log.atWarn().setMessage("Hub event rejected")
                                    .addKeyValue("event_type", type)
                                    .addKeyValue("device_key", deviceKey)
                                    .addKeyValue("status_code", response.statusCode())
                                    .log();
                        }
                    });
        } catch (Exception e) {
            log.atWarn().setMessage("Cannot serialize hub event")
                    .addKeyValue("event_type", type)
                    .addKeyValue("device_key", deviceKey)
                    .setCause(e)
                    .log();
        }
    }

    public static void close() {
        httpClient.close();
    }
}
