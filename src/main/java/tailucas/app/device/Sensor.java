package tailucas.app.device;

import org.apache.commons.lang3.StringUtils;

public class Sensor extends Device {
    public Integer normal_value;
    public Double sample_value;
    public Boolean active;
    public String input_label;
    public void updateFrom(Device device) {
        this.device_id = device.device_id;
        this.input_location = device.input_location;
        this.timestamp = device.timestamp;
        this.uptime = device.uptime;
    }
    @Override
    public String getDeviceKey() {
        return StringUtils.capitalize(String.format("%s %s", input_location, input_label));
    }
    @Override
    public String toString() {
        return "Sensor [" + getDeviceKey() + " (" + device_id + ")]";
    }
}