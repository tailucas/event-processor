package tailucas.app.device;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

import org.msgpack.jackson.dataformat.MessagePackMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;

import tailucas.app.device.config.Config;
import tailucas.app.device.config.InputConfig;
import tailucas.app.device.config.MeterConfig;
import tailucas.app.device.config.OutputConfig;
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

    public Event(String source, Device device, State deviceUpdate, String deviceUpdateString) {
        this.source = source;
        this.device = device;
        this.deviceUpdate = deviceUpdate;
        this.deviceUpdateString = deviceUpdateString;
        mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS);
        mapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
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

    protected Config fetchDeviceConfiguration(ConfigType api, String deviceKey) throws Exception {
        var clazz = Config.getConfigClass(api);
        if (clazz == null) {
            return null;
        }
        ObjectReader objectReader = mapper.readerFor(clazz);
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
                log.error("HTTP {} from {} for {}: {}", responseCode, apiName, deviceKey, responseBody);
                return null;
            }
            return objectReader.readValue(responseBody);
        }
        catch (IOException exp) {
            log.error("HTTP client problem", exp);
        }
        return null;
    }

    @Override
    public void run() {
        try {
            log.debug("{} {}", Thread.currentThread(), source);
            if (device != null) {
                log.info("{} {} {}", Thread.currentThread(), source, device);
            } else if (deviceUpdateString != null) {
                log.info("{} {} {}", Thread.currentThread(), source, deviceUpdateString);
            } else if (deviceUpdate != null) {
                log.info("{} {} {}", Thread.currentThread(), source, deviceUpdate);
                if (deviceUpdate.inputs != null) {
                    deviceUpdate.inputs.forEach(device -> {
                        try {
                            Config config = fetchDeviceConfiguration(ConfigType.INPUT_CONFIG, device.getDeviceKey());
                            log.info("{} input: {}", source, config);
                        } catch (Exception e) {
                            log.error(e.getMessage(), e);
                        }
                    });
                }
                if (deviceUpdate.outputs != null) {
                    deviceUpdate.outputs.forEach(device -> {
                        try {
                            Config config = fetchDeviceConfiguration(ConfigType.OUTPUT_CONFIG, device.getDeviceKey());
                            log.info("{} output: {}", source, config);
                        } catch (Exception e) {
                            log.error(e.getMessage(), e);
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.error(String.format("Problem processing event data from %s.", source), e);
        }
    }
}
