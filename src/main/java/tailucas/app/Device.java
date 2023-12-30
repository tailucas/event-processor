package tailucas.app;

public record Device(
    String device_key,
    String device_label,
    byte[] image,
    String location,
    String name,
    String type,
    String storage_url,
    String storage_path) {}
