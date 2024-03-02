package tailucas.app.api;

import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import tailucas.app.provider.DeviceConfig;

@RestController
public class DeviceConfigUpdate {

    @Autowired
    private Logger log;

    @PostMapping("/invalidate_config")
    @ResponseBody
	public String invalidateConfig(@RequestParam("device_key") String deviceKey) {
        log.info("Invalidating cached configuration for {}", deviceKey);
        DeviceConfig.getInstance().invalidateConfiguration(deviceKey);
        return deviceKey;
	}
}
