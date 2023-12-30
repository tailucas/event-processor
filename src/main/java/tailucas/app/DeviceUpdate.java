package tailucas.app;

import java.util.List;

public record DeviceUpdate(
    String timestamp,
    List<Device> inputs,
    List<Device> outputs,
    List<Device> outputs_triggered) {}
