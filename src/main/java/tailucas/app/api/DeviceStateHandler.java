package tailucas.app.api;

import java.util.List;

import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import tailucas.app.provider.DeviceState;

/**
 * Serves the latest reported state of every Ring device, merged across the topics
 * that carry it. Consumed by the web application's Telegram {@code /ring} command,
 * which cannot see the MQTT stream itself.
 */
@RestController
public class DeviceStateHandler {

    @Autowired
    private Logger log;

    @GetMapping("/devices")
    public List<DeviceState.DeviceSnapshot> devices() {
        final List<DeviceState.DeviceSnapshot> devices = DeviceState.getInstance().getDevices();
        log.atInfo().setMessage("Reporting device state")
            .addKeyValue("device_count", devices.size())
            .log();
        return devices;
    }
}
