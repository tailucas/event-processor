package tailucas.app.device;

public class Meter extends Device {
    public Integer last_minute_metered;
    public Double last_metered_minute;
    public Integer last_sample_value;
    public Integer pulse_discards;
    public Integer register_reading;
    @Override
    public String toString() {
        return "MeterDevice [" + getDeviceKey() + " (" + device_id + ")]";
    }
}
