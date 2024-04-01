package tailucas.app.device;

import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonProperty;

import tailucas.app.device.config.InputConfig;
import tailucas.app.device.config.MeterConfig;
import tailucas.app.provider.DeviceConfig;

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
    public String getDeviceType() {
        return Type.METER.name().toLowerCase();
    }
    @Override
    public boolean mustTriggerOutput(InputConfig deviceConfig) {
        if (!deviceConfig.isDeviceEnabled()) {
            return false;
        }
        MeterConfig meterConfig = null;
        try {
            meterConfig = DeviceConfig.getInstance().fetchMeterConfig(deviceKey);
        } catch (IOException | InterruptedException e) {
            log.error("{} cannot fetch meter configuration.", e);
            return false;
        }
        final Integer meterLowLimit = meterConfig.getMeterLowLimit();
        if (meterLowLimit != null && registerReading < meterLowLimit) {
            triggerStateDescription = String.format("register value %s is below the configured limit of %s.", registerReading, meterLowLimit);
            return true;
        }
        final Integer meterHighLimit = meterConfig.getMeterHighLimit();
        if (meterHighLimit != null && registerReading > meterHighLimit) {
            triggerStateDescription = String.format("register value %s is above the configured limit of %s.", registerReading, meterHighLimit);
            return true;
        }
        return false;
    }
    @Override
    public String toString() {
        return "Meter [lastMinuteMetered=" + lastMinuteMetered + ", lastMeteredMinute=" + lastMeteredMinute
                + ", lastSampleValue=" + lastSampleValue + ", pulseDiscards=" + pulseDiscards + ", registerReading="
                + registerReading + "]: " + super.toString();
    }
}
