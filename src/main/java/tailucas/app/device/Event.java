package tailucas.app.device;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

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

    public Event(String source, Device device, State deviceUpdate, String deviceUpdateString) {
        this.source = source;
        this.device = device;
        this.deviceUpdate = deviceUpdate;
        this.deviceUpdateString = deviceUpdateString;
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

    protected void fetchDeviceConfiguration(String deviceKey) throws Exception {
        try {

            UriComponents uriComponents = UriComponentsBuilder.newInstance()
                .scheme("http")
                .host("192.168.0.5")
                .path("/{scope}/{function}")
                .queryParam("device_key", deviceKey)
                .build()
                .expand("api", "input_config")
                .encode();

            log.debug("HTTP request for {} is: {}", deviceKey, uriComponents.toUriString());
            HttpRequest request = HttpRequest.newBuilder().GET().uri(uriComponents.toUri()).build();

            /* Will throw InterruptedException when interrupted */
            HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
            log.debug("{} config {}", deviceKey, response.body());
            //return response.body();
        }
        catch (IOException exp) {
            log.error("HTTP client problem", exp);
        }
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
                            fetchDeviceConfiguration(device.getDeviceKey());
                        } catch (Exception e) {
                            log.error(e.getMessage());
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.error(String.format("Problem processing event data from %s.", source), e);
        }
    }
}
