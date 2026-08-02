package tailucas.app.provider;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.type.CollectionType;

import tailucas.app.device.Generic;
import tailucas.app.device.Ring;
import tailucas.app.device.config.Config;
import tailucas.app.device.config.HAConfig;
import tailucas.app.device.config.InputConfig;
import tailucas.app.device.config.MeterConfig;
import tailucas.app.device.config.OutputConfig;
import tailucas.app.device.config.Config.ConfigType;

public class DeviceConfig {

    private static final Logger log = LoggerFactory.getLogger(DeviceConfig.class);

    private static final class StringMapTypeRef extends TypeReference<Map<String,String>> { }

    private HttpClient httpClient = null;
    private ObjectMapper mapper = null;
    private Map<ConfigType, CollectionType> collectionTypes = null;
    private Map<String, Pair<Instant, List<Config>>> configCache;
    private Map<String, HAConfig> haConfigCache;
    private String configHost = null;
    private String configHostPort = null;

    private DeviceConfig() {
        this(HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build(),
            System.getenv().get("CONFIG_HOST"),
            System.getenv().get("CONFIG_HOST_PORT"));
    }

    DeviceConfig(HttpClient httpClient, String configHost, String configHostPort) {
        this.httpClient = httpClient;
        this.mapper = new ObjectMapper();
        this.mapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        this.mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        this.collectionTypes = new ConcurrentHashMap<>(4);
        this.configCache = new ConcurrentHashMap<>(100);
        this.haConfigCache = new ConcurrentHashMap<>(100);
        this.configHost = configHost;
        this.configHostPort = configHostPort;
    }

    private static final class InstanceHolder {
        private static final DeviceConfig INSTANCE = new DeviceConfig();
    }

    public static DeviceConfig getInstance() {
        return InstanceHolder.INSTANCE;
    }

    public void close() {
        httpClient.close();
    }

    public void putHaConfig(HAConfig haConfig) {
        haConfig.getDevice().getIds().forEach(id -> {
            haConfigCache.put(id, haConfig);
        });
    }

    public HAConfig getHaConfig(Ring ringDevice) {
        return haConfigCache.get(ringDevice.getDeviceId());
    }

    public InputConfig fetchInputDeviceConfig(String deviceKey) throws IOException, InterruptedException {
        List<Config> deviceConfig = fetchDeviceConfiguration(ConfigType.INPUT_CONFIG, deviceKey);
        if (deviceConfig == null) {
            return null;
        }
        if (deviceConfig.size() != 1) {
            throw new IllegalStateException(String.format("Expected exactly 1 configuration item for %s", deviceKey));
        }
        final InputConfig inputConfig = (InputConfig) deviceConfig.getFirst();
        final String configDeviceKey = inputConfig.getDeviceKey();
        if (!configDeviceKey.equals(deviceKey)) {
            throw new IllegalStateException(String.format("Device key mismatch between device (%s) and config (%s).", deviceKey, configDeviceKey));
        }
        return inputConfig;
    }

    public OutputConfig fetchOutputDeviceConfig(String deviceKey) throws IOException, InterruptedException {
        List<Config> deviceConfig = fetchDeviceConfiguration(ConfigType.OUTPUT_CONFIG, deviceKey);
        if (deviceConfig == null) {
            return null;
        }
        if (deviceConfig.size() != 1) {
            throw new IllegalStateException(String.format("Expected exactly 1 configuration item for %s", deviceKey));
        }
        final OutputConfig outputConfig = (OutputConfig) deviceConfig.getFirst();
        final String configDeviceKey = outputConfig.getDeviceKey();
        if (!configDeviceKey.equals(deviceKey)) {
            throw new IllegalStateException(String.format("Device key mismatch between device (%s) and config (%s).", deviceKey, configDeviceKey));
        }
        return outputConfig;
    }

    public MeterConfig fetchMeterConfig(String deviceKey) throws IOException, InterruptedException {
        List<Config> deviceConfig = fetchDeviceConfiguration(ConfigType.METER_CONFIG, deviceKey);
        if (deviceConfig == null) {
            return null;
        }
        if (deviceConfig.size() != 1) {
            throw new IllegalStateException(String.format("Expected exactly 1 configuration item for %s", deviceKey));
        }
        return (MeterConfig) deviceConfig.getFirst();
    }

    public List<OutputConfig> getLinkedOutputs(InputConfig inputConfig) throws IOException, InterruptedException, IllegalStateException {
        if (inputConfig == null) {
            return null;
        }
        List<Config> outputConfig = fetchDeviceConfiguration(ConfigType.OUTPUT_LINK, inputConfig.getDeviceKey());
        if (outputConfig == null || outputConfig.size() == 0) {
            return null;
        }
        List<OutputConfig> outputConfigs = new ArrayList<>();
        outputConfig.forEach(config -> {
            outputConfigs.add((OutputConfig) config);
        });
        return outputConfigs;
    }

    public void invalidateConfiguration(String deviceKey) {
        final List<String> keysToRemove = new ArrayList<>();
        configCache.forEach((k, v) -> {
            // collect top-level cache keys
            if (k.startsWith(deviceKey)) {
                log.atDebug().setMessage("Adding key to remove from config cache").addKeyValue("cache_key", k).log();
                keysToRemove.add(k);
            }
            // ALSO collect nested cache keys
            if (v.getRight() != null) {
                v.getRight().forEach(c -> {
                    if (c instanceof OutputConfig) {
                        OutputConfig outputConfig = (OutputConfig) c;
                        if (outputConfig.getDeviceKey().equals(deviceKey)) {
                            log.atDebug().setMessage("Adding key to remove from config cache (included by device)")
                                .addKeyValue("cache_key", k)
                                .addKeyValue("device_key", deviceKey)
                                .log();
                            keysToRemove.add(k);
                        }
                    } else if (c instanceof InputConfig) {
                        InputConfig inputConfig = (InputConfig) c;
                        if (inputConfig.getDeviceKey().equals(deviceKey)) {
                            log.atDebug().setMessage("Adding key to remove from config cache (included by device)")
                                .addKeyValue("cache_key", k)
                                .addKeyValue("device_key", deviceKey)
                                .log();
                            keysToRemove.add(k);
                        }
                    }
                });
            }
        });
        // now remove all implied keys
        keysToRemove.forEach(k -> configCache.remove(k));
        log.atDebug().setMessage("Removed keys from config cache").addKeyValue("removed_keys", keysToRemove).log();
    }

    protected List<Config> fetchDeviceConfiguration(ConfigType api, String deviceKey) throws IOException, InterruptedException {
        final String apiName = api.toString().toLowerCase(Locale.ROOT);
        final Instant now = Instant.now();
        final String cacheKey = deviceKey + "/" + apiName;
        final var cached = configCache.get(cacheKey);
        if (cached != null) {
            final Instant fetchedAt = cached.getLeft();
            final long cacheAge = fetchedAt.until(now, ChronoUnit.SECONDS);
            // FIXME
            if (cacheAge <= 3600) {
                List<Config> cachedConfig = cached.getRight();
                if (cachedConfig != null) {
                    log.atDebug().setMessage("Returning config from config cache")
                        .addKeyValue("config_items", cachedConfig.size())
                        .addKeyValue("cache_key", cacheKey)
                        .addKeyValue("cache_age_seconds", cacheAge)
                        .log();
                }
                return cachedConfig;
            } else {
                log.atDebug().setMessage("Invalidating config cache")
                    .addKeyValue("cache_key", cacheKey)
                    .addKeyValue("cache_age_seconds", cacheAge)
                    .log();
                configCache.remove(cacheKey);
            }
        }
        log.atDebug().setMessage("Fetching configuration")
            .addKeyValue("device_key", deviceKey)
            .addKeyValue("api_name", apiName)
            .addKeyValue("config_host", configHost)
            .log();
        UriComponents uriComponents = UriComponentsBuilder.newInstance()
            .scheme("http")
            .host(configHost)
            .port(configHostPort)
            .path("/{scope}/{function}")
            .queryParam("device_key", deviceKey)
            .build()
            .expand("api", apiName)
            .encode();
        log.atDebug().setMessage("HTTP request")
            .addKeyValue("api_name", apiName)
            .addKeyValue("device_key", deviceKey)
            .addKeyValue("uri", uriComponents.toUriString())
            .log();
        final HttpRequest request = HttpRequest.newBuilder()
            .GET()
            .uri(uriComponents.toUri())
            .timeout(Duration.ofSeconds(2))
            .build();
        HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
        final int responseCode = response.statusCode();
        final String responseBody = response.body();
        log.atDebug().setMessage("HTTP response")
            .addKeyValue("response_code", responseCode)
            .addKeyValue("device_key", deviceKey)
            .addKeyValue("response_body", responseBody)
            .log();
        List<Config> configs = null;
        if (responseCode / 100 != 2) {
            String responseDetail = null;
            try {
                Map<String, String> jsonResponse = mapper.readValue(responseBody, new StringMapTypeRef());
                responseDetail = jsonResponse.get("detail");
            } catch (JsonProcessingException e) {
                responseDetail = responseBody;
            }
            log.atDebug().setMessage("HTTP error response")
                .addKeyValue("response_code", responseCode)
                .addKeyValue("api_name", apiName)
                .addKeyValue("device_key", deviceKey)
                .addKeyValue("response_detail", responseDetail)
                .log();
        } else {
            configs = mapper.readValue(responseBody, getCollectionType(api));
        }
        log.atDebug().setMessage("Updating configuration config cache").addKeyValue("cache_key", cacheKey).log();
        configCache.put(cacheKey, Pair.of(now, configs));
        log.atDebug().setMessage("Received configuration items")
            .addKeyValue("config_items", (configs != null) ? configs.size() : 0)
            .addKeyValue("device_key", deviceKey)
            .addKeyValue("cache_key", cacheKey)
            .log();
        return configs;
    }

    public void postDeviceInfo(Generic device) throws IOException, InterruptedException {
        final String deviceKey = device.getDeviceKey();
        log.atDebug().setMessage("Posting device update").addKeyValue("device_key", deviceKey).log();
        UriComponents uriComponents = UriComponentsBuilder.newInstance()
            .scheme("http")
            .host(configHost)
            .port(configHostPort)
            .path("/{scope}/{function}")
            .build()
            .expand("api", "device_info")
            .encode();
        final String deviceJson = mapper.writeValueAsString(device);
        final HttpRequest request = HttpRequest.newBuilder()
            .uri(uriComponents.toUri())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(deviceJson, StandardCharsets.UTF_8))
            .timeout(Duration.ofSeconds(5))
            .build();
        log.atDebug().setMessage("HTTP POST")
            .addKeyValue("http_version", httpClient.version())
            .addKeyValue("device_key", deviceKey)
            .addKeyValue("uri", uriComponents.toUriString())
            .log();
        log.atDebug().setMessage("Request headers").addKeyValue("headers", request.headers().map()).log();
        log.atDebug().setMessage("Request body").addKeyValue("body", deviceJson).log();
        HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
        final int responseCode = response.statusCode();
        final String responseBody = response.body();
        if (responseCode / 100 != 2) {
            log.atWarn().setMessage("Device update failed")
                .addKeyValue("device_key", deviceKey)
                .addKeyValue("response_code", responseCode)
                .addKeyValue("response_body", responseBody)
                .addKeyValue("request_payload", deviceJson)
                .log();
        }
    }

    private CollectionType getCollectionType(ConfigType api) {
        return collectionTypes.computeIfAbsent(api, s -> {
            return mapper.getTypeFactory().constructCollectionType(
                List.class,
                Config.getConfigClass(api));
        });
    }
}
