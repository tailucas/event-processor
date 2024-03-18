package tailucas.app.device.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class MeterConfig extends Config {
    @JsonIgnore
    @JsonProperty("input_device_id")
    protected String inputDeviceId;
    @JsonProperty("meter_value")
    protected Integer meterValue;
    @JsonProperty("register_value")
    protected Integer registerValue;
    @JsonProperty("meter_reading")
    protected String meterReading;
    @JsonProperty("meter_iot_topic")
    protected String meterIotTopic;
    @JsonProperty("meter_low_limit")
    protected Integer meterLowLimit;
    @JsonProperty("meter_high_limit")
    protected Integer meterHighLimit;
    @JsonProperty("meter_reset_value")
    protected Integer meterResetValue;
    @JsonProperty("meter_reset_additive")
    protected Boolean meterResetAdditive;
    @JsonProperty("meter_reading_unit")
    protected String meterReadingUnit;
    @JsonProperty("meter_reading_unit_factor")
    protected Integer meterReadingUnitFactor;
    @JsonProperty("meter_reading_unit_precision")
    protected Integer meterReadingUnitPrecision;
    @Override
    public String toString() {
        return "MeterConfig [meter_value=" + meterValue + ", register_value=" + registerValue + ", meter_reading="
                + meterReading + ", meter_iot_topic=" + meterIotTopic + ", meter_low_limit=" + meterLowLimit
                + ", meter_high_limit=" + meterHighLimit + ", meter_reset_value=" + meterResetValue
                + ", meter_reset_additive=" + meterResetAdditive + ", meter_reading_unit=" + meterReadingUnit
                + ", meter_reading_unit_factor=" + meterReadingUnitFactor + ", meter_reading_unit_precision="
                + meterReadingUnitPrecision + "]";
    }
}
