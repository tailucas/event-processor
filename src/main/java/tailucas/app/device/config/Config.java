package tailucas.app.device.config;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class Config {
    public enum ConfigType {
        INPUT_CONFIG,
        OUTPUT_CONFIG,
        METER_CONFIG,
        OUTPUT_LINK
    }
    @JsonIgnore
    public Integer id;
    public Config() { }
    public static Class<? extends Config> getConfigClass(ConfigType configType) {
        var clazz = switch (configType) {
            case ConfigType.INPUT_CONFIG -> InputConfig.class;
            case ConfigType.OUTPUT_CONFIG -> OutputConfig.class;
            case ConfigType.METER_CONFIG -> MeterConfig.class;
            case ConfigType.OUTPUT_LINK -> OutputConfig.class;
            default -> null;
        };
        return clazz;
    }
}
