package tailucas.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@ConfigurationProperties(prefix = "app")
@Primary
@Validated
public class AppProperties {
    @NotBlank
    private String projectName;
    @Min(30000)
    @Max(90000)
    private int messageControlExpiryMs;
    @NotBlank
    private String messageControlExchangeName;
    @NotBlank
    private String messageEventExchangeName;
    /** Seconds to wait for Ring device information after MQTT discovery; zero disables the check. */
    @Min(0)
    @Max(3600)
    private int ringDiscoveryTimeoutSeconds;
    /** When true, the application exits if the Ring discovery check fails. */
    private boolean ringDiscoveryRequired;

    public String getProjectName() {
        return projectName;
    }
    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }
    public int getMessageControlExpiryMs() {
        return messageControlExpiryMs;
    }
    public void setMessageControlExpiryMs(int messageControlExpiryMs) {
        this.messageControlExpiryMs = messageControlExpiryMs;
    }
    public String getMessageControlExchangeName() {
        return messageControlExchangeName;
    }
    public void setMessageControlExchangeName(String messageControlExchangeName) {
        this.messageControlExchangeName = messageControlExchangeName;
    }
    public String getMessageEventExchangeName() {
        return messageEventExchangeName;
    }
    public void setMessageEventExchangeName(String messageEventExchangeName) {
        this.messageEventExchangeName = messageEventExchangeName;
    }
    public int getRingDiscoveryTimeoutSeconds() {
        return ringDiscoveryTimeoutSeconds;
    }
    public void setRingDiscoveryTimeoutSeconds(int ringDiscoveryTimeoutSeconds) {
        this.ringDiscoveryTimeoutSeconds = ringDiscoveryTimeoutSeconds;
    }
    public boolean isRingDiscoveryRequired() {
        return ringDiscoveryRequired;
    }
    public void setRingDiscoveryRequired(boolean ringDiscoveryRequired) {
        this.ringDiscoveryRequired = ringDiscoveryRequired;
    }
}
