package tailucas.app.provider;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;

import tailucas.app.device.config.Config;
import tailucas.app.device.config.InputConfig;
import tailucas.app.device.config.OutputConfig;
import tailucas.app.device.config.Config.ConfigType;

public class DeviceConfig {

    private static Logger log = null;
    private static DeviceConfig singleton = null;

    private HttpClient httpClient = null;
    private ObjectMapper mapper = null;
    private Map<ConfigType, CollectionType> collectionTypes = null;
    private Map<String, List<Config>> configCache;

    private DeviceConfig() {
        log = LoggerFactory.getLogger(DeviceConfig.class);
        httpClient = HttpClient.newHttpClient();
        mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        collectionTypes = new HashMap<>(4);
        //FIXME: just to avoid spam during testing
        configCache = new HashMap<>(100);
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

    public InputConfig fetchInputDeviceConfig(String deviceKey) throws IOException, InterruptedException {
        List<Config> deviceConfig = fetchDeviceConfiguration(ConfigType.INPUT_CONFIG, deviceKey);
        if (deviceConfig == null) {
            return null;
        }
        if (deviceConfig.size() != 1) {
            throw new RuntimeException(String.format("Expected exactly 1 configuration item for {}", deviceKey));
        }
        return (InputConfig) deviceConfig.getFirst();
    }

    public OutputConfig fetchOutputDeviceConfig(String deviceKey) throws IOException, InterruptedException {
        List<Config> deviceConfig = fetchDeviceConfiguration(ConfigType.OUTPUT_CONFIG, deviceKey);
        if (deviceConfig == null) {
            return null;
        }
        if (deviceConfig.size() != 1) {
            throw new RuntimeException(String.format("Expected exactly 1 configuration item for {}", deviceKey));
        }
        return (OutputConfig) deviceConfig.getFirst();
    }

    public List<OutputConfig> getLinkedOutputs(InputConfig inputConfig) throws IOException, InterruptedException {
        if (inputConfig == null) {
            return null;
        }
        List<Config> outputConfig = fetchDeviceConfiguration(ConfigType.OUTPUT_LINK, inputConfig.device_key);
        if (outputConfig == null || outputConfig.size() == 0) {
            return null;
        }
        List<OutputConfig> outputConfigs = new ArrayList<>();
        outputConfig.forEach(config -> {
            outputConfigs.add((OutputConfig) config);
        });
        return outputConfigs;
    }

    protected List<Config> fetchDeviceConfiguration(ConfigType api, String deviceKey) throws IOException, InterruptedException {
        final String apiName = api.toString().toLowerCase();
        final String cacheKey = String.format("%s:%s", apiName, deviceKey);
        if (configCache.containsKey(cacheKey)) {
            return configCache.get(cacheKey);
        }
        UriComponents uriComponents = UriComponentsBuilder.newInstance()
            .scheme("http")
            .host("192.168.0.5")
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
        configCache.put(cacheKey, configs);
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
