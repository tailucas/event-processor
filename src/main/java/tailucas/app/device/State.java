package tailucas.app.device;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class State {

    private static Logger log = null;
    public static DateTimeFormatter DATE_TIME_FORMATTER = null;

    @JsonProperty
    protected String timestamp;
    @JsonProperty
    protected List<Device> inputs;
    @JsonProperty
    protected List<Device> outputs;

    @JsonIgnore
    protected Instant createdTime;

    public State() {
        if (log == null) {
            log = LoggerFactory.getLogger(State.class);
            DATE_TIME_FORMATTER = new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSZ").toFormatter();
        }
        this.createdTime = null;
    }
    public State(List<Device> inputs) {
        this();
        this.inputs = inputs;
    }
    public List<Device> getInputs() {
        return inputs;
    }
    public List<Device> getOutputs() {
        return outputs;
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
        return "State [timestamp=" + timestamp + ", inputs=" + inputs + ", outputs=" + outputs + "]";
    }
    @JsonIgnore
    @JsonProperty
    private Object active_devices;
    @JsonIgnore
    @JsonProperty
    private Object outputs_triggered;
    @JsonIgnore
    @JsonProperty
    private Object device_info;
    @JsonIgnore
    @JsonProperty
    private Object samples;
    @JsonIgnore
    @JsonProperty
    private Object statistics;
    @JsonIgnore
    @JsonProperty
    private String storage_url;
    @JsonIgnore
    @JsonProperty
    private String storage_path;
}