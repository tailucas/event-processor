package tailucas.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AppPropertiesValidationTest {

    private static final String[] VALID_PROPERTIES = {
        "app.project-name=test_project",
        "app.message-control-exchange-name=control",
        "app.message-event-exchange-name=events",
        "app.message-control-expiry-ms=60000"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class);

    @EnableConfigurationProperties(AppProperties.class)
    static class PropertiesConfiguration { }

    @Test
    void validPropertiesBind() {
        contextRunner.withPropertyValues(VALID_PROPERTIES).run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            final AppProperties props = context.getBean(AppProperties.class);
            assertEquals("test_project", props.getProjectName());
            assertEquals("control", props.getMessageControlExchangeName());
            assertEquals("events", props.getMessageEventExchangeName());
            assertEquals(60000, props.getMessageControlExpiryMs());
        });
    }

    @Test
    void blankProjectNameFailsValidation() {
        contextRunner.withPropertyValues(VALID_PROPERTIES)
            .withPropertyValues("app.project-name= ")
            .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void missingExchangeNamesFailValidation() {
        contextRunner.withPropertyValues(
                "app.project-name=test_project",
                "app.message-control-expiry-ms=60000")
            .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void expiryBelowMinimumFailsValidation() {
        contextRunner.withPropertyValues(VALID_PROPERTIES)
            .withPropertyValues("app.message-control-expiry-ms=29999")
            .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void expiryAboveMaximumFailsValidation() {
        contextRunner.withPropertyValues(VALID_PROPERTIES)
            .withPropertyValues("app.message-control-expiry-ms=90001")
            .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void expiryBoundariesAreAccepted() {
        contextRunner.withPropertyValues(VALID_PROPERTIES)
            .withPropertyValues("app.message-control-expiry-ms=30000")
            .run(context -> assertThat(context.getStartupFailure()).isNull());
        contextRunner.withPropertyValues(VALID_PROPERTIES)
            .withPropertyValues("app.message-control-expiry-ms=90000")
            .run(context -> assertThat(context.getStartupFailure()).isNull());
    }
}
