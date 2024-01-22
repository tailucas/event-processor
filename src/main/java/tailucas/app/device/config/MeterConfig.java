package tailucas.app.device.config;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class MeterConfig extends Config {
    @JsonIgnore
    public String input_device_id;
    public Integer meter_value;
    public Integer register_value;
    public String meter_reading;
    public String meter_iot_topic;
    public Integer meter_low_limit;
    public Integer meter_high_limit;
    public Integer meter_reset_value;
    public Boolean meter_reset_additive;
    public String meter_reading_unit;
    public Integer meter_reading_unit_factor;
    public Integer meter_reading_unit_precision;
    @Override
    public String toString() {
        return "MeterConfig [meter_value=" + meter_value + ", register_value=" + register_value + ", meter_reading="
                + meter_reading + ", meter_iot_topic=" + meter_iot_topic + ", meter_low_limit=" + meter_low_limit
                + ", meter_high_limit=" + meter_high_limit + ", meter_reset_value=" + meter_reset_value
                + ", meter_reset_additive=" + meter_reset_additive + ", meter_reading_unit=" + meter_reading_unit
                + ", meter_reading_unit_factor=" + meter_reading_unit_factor + ", meter_reading_unit_precision="
                + meter_reading_unit_precision + "]";
    }
}
