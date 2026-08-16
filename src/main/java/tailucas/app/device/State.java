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

    private static final Logger log = LoggerFactory.getLogger(State.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSZ").toFormatter();

    @JsonProperty
    protected String timestamp;
    @JsonProperty
    protected List<Device> inputs;
    @JsonProperty
    protected List<Device> outputs;
    @JsonProperty("traceparent")
    protected String traceparent;
    @JsonProperty("baggage")
    protected String baggage;

    @JsonIgnore
    protected Instant createdTime;

    public State() {
        this.createdTime = null;
    }
    public State(List<Device> inputs) {
        this();
        if (inputs != null) {
            this.inputs = List.copyOf(inputs);
        }
    }
    public List<Device> getInputs() {
        return inputs == null ? null : List.copyOf(inputs);
    }
    public List<Device> getOutputs() {
        return outputs == null ? null : List.copyOf(outputs);
    }
    public String getTraceparent() {
        return traceparent;
    }
    public String getBaggage() {
        return baggage;
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
                log.atWarn().setMessage("Cannot parse timestamp, using now").addKeyValue("timestamp", timestamp).log();
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