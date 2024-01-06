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
import tailucas.app.device.config.Input;
import zmq.ZError.IOException;

public class Event implements Runnable {

    public enum DeviceType {
        SENSOR,
        METER
    }

    public enum ConfigApi {
        INPUT_CONFIG,
        OUTPUT_CONFIG
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

    protected Config fetchDeviceConfiguration(ConfigApi api, String deviceKey) throws Exception {
        ObjectReader objectReader = switch (api) {
            case ConfigApi.INPUT_CONFIG -> objectReader = mapper.readerFor(Input.class);
            default -> null;
        };
        if (objectReader == null) {
            return null;
        }
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
            // FIXME
            final String responseString = response.body().replaceAll("\\\\","").replaceAll("^\"|\"$", "");
            return objectReader.readValue(responseString);
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
                            Config config = fetchDeviceConfiguration(ConfigApi.INPUT_CONFIG, device.getDeviceKey());
                            log.info("Config: {}", config);
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
