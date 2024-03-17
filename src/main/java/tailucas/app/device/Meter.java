package tailucas.app.device;

import com.fasterxml.jackson.annotation.JsonProperty;

import tailucas.app.device.config.Config;

public class Meter extends Device {
    @JsonProperty("last_minute_metered")
    protected Integer lastMinuteMetered;
    @JsonProperty("last_metered_minute")
    protected Double lastMeteredMinute;
    @JsonProperty("last_sample_value")
    protected Integer lastSampleValue;
    @JsonProperty("pulse_discards")
    protected Integer pulseDiscards;
    @JsonProperty("register_reading")
    protected Integer registerReading;
    @Override
    public boolean mustTriggerOutput(Config deviceConfig) {
        return true;
    }
    @Override
    public String toString() {
        return "Meter [lastMinuteMetered=" + lastMinuteMetered + ", lastMeteredMinute=" + lastMeteredMinute
                + ", lastSampleValue=" + lastSampleValue + ", pulseDiscards=" + pulseDiscards + ", registerReading="
                + registerReading + "]: " + super.toString();
    }
}
