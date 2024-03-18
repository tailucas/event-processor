package tailucas.app.provider;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import com.fasterxml.jackson.databind.type.CollectionType;

import tailucas.app.device.Ring;
import tailucas.app.device.config.Config;
import tailucas.app.device.config.HAConfig;
import tailucas.app.device.config.InputConfig;
import tailucas.app.device.config.OutputConfig;
import tailucas.app.device.config.Config.ConfigType;

public class DeviceConfig {

    private static Logger log = null;
    private static DeviceConfig singleton = null;

    private HttpClient httpClient = null;
    private ObjectMapper mapper = null;
    private Map<ConfigType, CollectionType> collectionTypes = null;
    private Map<String, Pair<Instant, List<Config>>> configCache;
    private Map<String, HAConfig> haConfigCache;

    private DeviceConfig() {
        log = LoggerFactory.getLogger(DeviceConfig.class);
        httpClient = HttpClient.newHttpClient();
        mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        collectionTypes = new HashMap<>(4);
        configCache = new ConcurrentHashMap<>(100);
        haConfigCache = new HashMap<>(100);
    }

    public static synchronized DeviceConfig getInstance() {
        if (singleton == null) {
            singleton = new DeviceConfig();
        }
        return singleton;
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
            throw new RuntimeException(String.format("Expected exactly 1 configuration item for {}", deviceKey));
        }
        final InputConfig inputConfig = (InputConfig) deviceConfig.getFirst();
        final String configDeviceKey = inputConfig.getDeviceKey();
        if (!configDeviceKey.equals(deviceKey)) {
            throw new RuntimeException(String.format("Device key mismatch between device (%s) and config (%s).", deviceKey, configDeviceKey));
        }
        return inputConfig;
    }

    public OutputConfig fetchOutputDeviceConfig(String deviceKey) throws IOException, InterruptedException {
        List<Config> deviceConfig = fetchDeviceConfiguration(ConfigType.OUTPUT_CONFIG, deviceKey);
        if (deviceConfig == null) {
            return null;
        }
        if (deviceConfig.size() != 1) {
            throw new RuntimeException(String.format("Expected exactly 1 configuration item for {}", deviceKey));
        }
        final OutputConfig outputConfig = (OutputConfig) deviceConfig.getFirst();
        final String configDeviceKey = outputConfig.getDeviceKey();
        if (!configDeviceKey.equals(deviceKey)) {
            throw new RuntimeException(String.format("Device key mismatch between device (%s) and config (%s).", deviceKey, configDeviceKey));
        }
        return outputConfig;
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
        configCache.remove(deviceKey);
    }

    protected List<Config> fetchDeviceConfiguration(ConfigType api, String deviceKey) throws IOException, InterruptedException {
        final String hostName = "192.168.0.5";
        final String apiName = api.toString().toLowerCase();
        final Instant now = Instant.now();
        final String cacheKey = deviceKey + "/" + apiName;
        if (configCache.containsKey(cacheKey)) {
            var cached = configCache.get(cacheKey);
            final Instant fetchedAt = cached.getLeft();
            final long cacheAge = fetchedAt.until(now, ChronoUnit.SECONDS);
            // FIXME
            if (cacheAge <= 3600) {
                List<Config> cachedConfig = cached.getRight();
                log.debug("Returning cached config ({} items) for {} (age {}s).", cachedConfig.size(), cacheKey, cacheAge);
                return cachedConfig;
            } else {
                log.debug("Invalidating cache for {} (age {}s).", cacheKey, cacheAge);
                configCache.remove(cacheKey);
            }
        }
        log.info("{} needs {} from {}...", deviceKey, apiName, hostName);
        UriComponents uriComponents = UriComponentsBuilder.newInstance()
            .scheme("http")
            .host(hostName)
            .path("/{scope}/{function}")
            .queryParam("device_key", deviceKey)
            .build()
            .expand("api", apiName)
            .encode();
        log.debug("HTTP request {} for {} is: {}", apiName, deviceKey, uriComponents.toUriString());
        HttpRequest request = HttpRequest.newBuilder().GET().uri(uriComponents.toUri()).build();
        HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
        final int responseCode = response.statusCode();
        final String responseBody = response.body();
        List<Config> configs = null;
        if (responseCode % 200 != 0) {
            String responseDetail = null;
            try {
                Map<String, String> jsonResponse = mapper.readValue(responseBody, new TypeReference<Map<String,String>>() {});
                responseDetail = jsonResponse.get("detail");
            } catch (JsonProcessingException e) {
                responseDetail = responseBody;
            }
            log.debug("HTTP {} from {} for {}.", responseCode, apiName, deviceKey);
            log.error("{}: {}", deviceKey, responseDetail);
        } else {
            configs = mapper.readValue(responseBody, getCollectionType(api));
        }
        configCache.put(cacheKey, Pair.of(now, configs));
        return configs;
    }

    private CollectionType getCollectionType(ConfigType api) {
        return collectionTypes.computeIfAbsent(api, s -> {
            return mapper.getTypeFactory().constructCollectionType(
                List.class,
                Config.getConfigClass(api));
        });
    }
}
