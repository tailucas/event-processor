package tailucas.app.device;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class State {

    private static Logger log = null;

    public static final DateTimeFormatter DATE_TIME_FORMATTER = new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSZ").toFormatter();

    public String timestamp;
    public List<Device> inputs;
    public List<Device> outputs;
    public List<Device> outputs_triggered;

    protected Instant createdTime;

    public State() {
        log = LoggerFactory.getLogger(State.class);
    }
    public State(List<Device> inputs, List<Device> outputs_triggered) {
        this();
        this.inputs = inputs;
        this.outputs_triggered = outputs_triggered;
        this.createdTime = null;
    }
    public Instant getTimestamp() {
        if (createdTime != null) {
            return createdTime;
        }
        Instant createdTime = Instant.now();
        if (timestamp != null) {
            try {
                createdTime = Instant.from(DATE_TIME_FORMATTER.parse(timestamp));
            } catch (DateTimeParseException e) {
                log.warn("Cannot parse timestamp {}, using now.", timestamp);
            }
        }
        return createdTime;
    }
    @Override
    public String toString() {
        return "State [timestamp=" + timestamp + ", inputs=" + inputs + ", outputs=" + outputs + ", outputs_triggered="
                + outputs_triggered + "]";
    }
    @JsonIgnore
    public Object active_devices;
    @JsonIgnore
    public Object device_info;
    @JsonIgnore
    public Object samples;
    @JsonIgnore
    public Object statistics;
    @JsonIgnore
    public String storage_url;
    @JsonIgnore
    public String storage_path;
}