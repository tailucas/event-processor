package tailucas.app;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@Configuration
@ConfigurationProperties(prefix = "app")
@Primary
@Validated
public class AppProperties {

    public static final String PROPERTIES_FILE_NAME = "application.properties";

    @NotBlank
    private String projectName;
    @Min(30000)
    @Max(90000)
    private int messageControlExpiryMs;
    @NotBlank
    private String messageControlExchangeName;
    @NotBlank
    private String messageEventExchangeName;

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

    public static String getProperty(String key) {
        Properties props = new Properties();
        InputStream propsInput = Thread.currentThread().getContextClassLoader().getResourceAsStream(PROPERTIES_FILE_NAME);
        try {
            props.load(propsInput);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return props.getProperty(key);
    }
}
