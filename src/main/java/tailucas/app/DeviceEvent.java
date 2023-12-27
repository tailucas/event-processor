package tailucas.app;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeviceEvent implements Runnable {

    private static Logger log = LoggerFactory.getLogger(DeviceEvent.class);

    private static final String FIELD_TIMESTAMP = "timestamp";
    private static final String FIELD_DATA = "data";
    private static final String FIELD_DATA_DEVICE_INFO = "device_info";
    private static final String FIELD_DATA_ACTIVE_DEVICES = "active_devices";
    private static final String FIELD_DATA_SAMPLES = "samples";
    private static final String FIELD_DATA_STATISTICS = "statistics";
    private static final String FIELD_MQTT = "mqtt";

    private static final String[] FIELD_LIST = new String[] { FIELD_TIMESTAMP, FIELD_DATA, FIELD_MQTT, FIELD_DATA_SAMPLES };
    private static final Set<String> FIELD_SET = new HashSet<>(Arrays.asList(FIELD_LIST));

    private static final String[] FIELD_DATA_LIST = new String[] { FIELD_DATA_DEVICE_INFO, FIELD_DATA_SAMPLES, FIELD_DATA_STATISTICS, FIELD_DATA_ACTIVE_DEVICES };
    private static final Set<String> FIELD_DATA_SET = new HashSet<>(Arrays.asList(FIELD_DATA_LIST));

    protected String source;
    protected Map<?, ?> event;
    protected DateTimeFormatter dtf;

    public DeviceEvent(String source, Map<?, ?> event) {
        this.source = source;
        this.event = event;
        this.dtf = new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSZ").toFormatter();
    }

    @Override
    public void run() {
        try {
            event.keySet().forEach(k -> {
                if (!FIELD_SET.contains(k)) {
                    log.warn("Unknown event key {} from {} which has no processing logic.", k, source);
                }
            });
            Instant time = Instant.now();
            var t = event.get(FIELD_TIMESTAMP);
            if (t != null) {
                try {
                    time = Instant.from(dtf.parse(t.toString()));
                } catch (DateTimeParseException e) {
                    log.warn("Cannot parse timestamp {} from {}, using now.", t, source);
                }
            }
            var d = event.get(FIELD_DATA);
            if (d != null && d instanceof LinkedHashMap<?, ?>) {
                var dataMap = (LinkedHashMap<?, ?>) d;
                dataMap.keySet().forEach(k -> {
                    if (!FIELD_DATA_SET.contains(k)) {
                        log.warn("Unknown data event key {} which has no processing logic.", k);
                    }
                });
                ArrayList<?> activeDevices = (ArrayList<?>) dataMap.get(FIELD_DATA_ACTIVE_DEVICES);
                if (activeDevices != null) {
                    /*
                    * {samples={Patio Motion Detector (House,Front)=78}, active_devices=[{name=Patio, type=Motion Detector, location=House,Front, device_key=Patio Motion Detector (House,Front), device_label=Patio Motion Detector, sample_value=78}]}
                    */
                    log.info("{} {}: {} {}", time, source, FIELD_DATA_ACTIVE_DEVICES, activeDevices);
                }
                LinkedHashMap<?,?> deviceInfo = (LinkedHashMap<?, ?>) dataMap.get(FIELD_DATA_DEVICE_INFO);
                if (deviceInfo != null) {
                    /*
                    * device_info: 2023-12-27T16:39:58.093517Z {inputs=[{type=Camera, location=Garage, device_key=Garage Camera}, {type=Camera, location=Patio, device_key=Patio Camera}, {type=Camera, location=Back Door, device_key=Back Door Camera}, {type=Camera, location=Back Gate, device_key=Back Gate Camera}, {type=Camera, location=Pool, device_key=Pool Camera}, {type=Camera, location=Home, device_key=Home Camera}, {type=Camera, location=Yard, device_key=Yard Camera}, {type=Camera, location=Driveway, device_key=Driveway Camera}], outputs=[{location=Garage, type=Camera, device_key=Garage Camera}, {location=Patio, type=Camera, device_key=Patio Camera}, {location=Back Door, type=Camera, device_key=Back Door Camera}, {location=Back Gate, type=Camera, device_key=Back Gate Camera}, {location=Pool, type=Camera, device_key=Pool Camera}, {location=Home, type=Camera, device_key=Home Camera}, {location=Yard, type=Camera, device_key=Yard Camera}, {location=Driveway, type=Camera, device_key=Driveway Camera}]}
                    */
                    log.debug("{} {}: {} {}", time, source, FIELD_DATA_DEVICE_INFO, deviceInfo);
                }
                LinkedHashMap<?,?> samples = (LinkedHashMap<?, ?>) dataMap.get(FIELD_DATA_SAMPLES);
                if (samples != null) {
                    log.debug("{} {}: {} {}", time, source, FIELD_DATA_SAMPLES, samples);
                }
                LinkedHashMap<?,?> statistics = (LinkedHashMap<?, ?>) dataMap.get(FIELD_DATA_STATISTICS);
                if (statistics != null) {
                    log.debug("{} {}: {} {}", time, source, FIELD_DATA_STATISTICS, statistics);
                }
            }
            Map<?, ?> mqttMap;
            Object mqtt = event.get(FIELD_MQTT);
            if (mqtt != null && mqtt instanceof Map<?,?>) {
                mqttMap = (Map<?,?>) mqtt;
                log.debug("{} {} {}", source, time, mqttMap);
            }
        } catch (Exception e) {
            log.error(String.format("Problem processing event data from %1.", source), e);
        }
    }
}
