package tailucas.app.device;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
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
import tailucas.app.device.config.Config.ConfigType;
import zmq.ZError.IOException;

public class Event implements Runnable {

    public enum DeviceType {
        SENSOR,
        METER
    }

    private static Logger log = LoggerFactory.getLogger(Event.class);
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    protected String source;
    protected Device device;
    protected State deviceUpdate;
    protected String deviceUpdateString;

    private ObjectMapper mapper = null;
    private Map<ConfigType, CollectionType> collectionTypes = null;

    public Event(String source, Device device, State deviceUpdate, String deviceUpdateString) {
        this.source = source;
        this.device = device;
        this.deviceUpdate = deviceUpdate;
        this.deviceUpdateString = deviceUpdateString;
        mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        this.collectionTypes = new HashMap<>(4);
    }

    public Event(String source, Device device, State deviceUpdate) {
        this(source, device, deviceUpdate, null);
    }

    public Event(String source, State deviceUpdate) {
        this(source, null, deviceUpdate, null);
    }

    public Event(String source, String deviceUpdate) {
        this(source, null, null, deviceUpdate);
    }

    private CollectionType getCollectionType(ConfigType api) {
        return collectionTypes.computeIfAbsent(api, s -> {
            return mapper.getTypeFactory().constructCollectionType(
                List.class,
                Config.getConfigClass(api));
        });
    }

    protected List<Config> fetchDeviceConfiguration(ConfigType api, String deviceKey) throws Exception {
        try {
            final String apiName = api.toString().toLowerCase();
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
            if (responseCode % 200 != 0) {
                String responseDetail = null;
                try {
                    Map<String, String> jsonResponse = mapper.readValue(responseBody, new TypeReference<Map<String,String>>() {});
                    responseDetail = jsonResponse.get("detail");
                } catch (JsonProcessingException e) {
                    log.debug("Ignoring JSON processing error with response body {}", responseBody);
                    responseDetail = responseBody;
                }
                log.error("HTTP {} from {} for {}: {}", responseCode, apiName, deviceKey, responseDetail);
                return null;
            }
            return mapper.readValue(responseBody, getCollectionType(api));
        }
        catch (IOException exp) {
            log.error("HTTP client problem", exp);
        }
        return null;
    }

    @Override
    public void run() {
        try {
            log.debug("{}", source);
            if (device != null) {
                log.info("{} {}", source, device);
            }
            if (deviceUpdate != null) {
                log.info("{} {}", source, deviceUpdate);
                if (deviceUpdate.inputs != null) {
                    deviceUpdate.inputs.forEach(device -> {
                        final String deviceKey = device.getDeviceKey();
                        try {
                            List<Config> inputConfig = fetchDeviceConfiguration(ConfigType.INPUT_CONFIG, deviceKey);
                            if (inputConfig != null) {
                                log.info("{} input: {}", source, inputConfig.getFirst());
                            }
                            List<Config> outputConfig = fetchDeviceConfiguration(ConfigType.OUTPUT_LINK, deviceKey);
                            if (outputConfig.size() > 0) {
                                log.info("{} linked outputs {}", deviceKey, outputConfig);
                            }
                        } catch (Exception e) {
                            log.error(e.getMessage(), e);
                        }
                    });
                }
                if (deviceUpdate.outputs != null) {
                    deviceUpdate.outputs.forEach(device -> {
                        try {
                            List<Config> outputConfig = fetchDeviceConfiguration(ConfigType.OUTPUT_CONFIG, device.getDeviceKey());
                            if (outputConfig != null) {
                                log.info("{} output: {}", source, outputConfig.getFirst());
                            }
                        } catch (Exception e) {
                            log.error(e.getMessage(), e);
                        }
                    });
                }
            }
            if (deviceUpdateString != null) {
                // mutually exclusive with device and device updates
                log.info("{} {}", source, deviceUpdateString);
            }
        } catch (Exception e) {
            log.error(String.format("Problem processing event data from %s.", source), e);
        }
    }
}
