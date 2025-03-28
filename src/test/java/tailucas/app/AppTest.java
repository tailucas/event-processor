package tailucas.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AppTest {

    @Autowired
    private AppProperties appProperties;

    @Value("${spring.threads.virtual.enabled}")
    private Boolean virtualThreadsEnabled;

    @Value("${management.endpoints.web.exposure.include}")
    private String managementSettings;

    @Test
    void addition() {
        assertEquals(2, 1+1);
    }

    @Test
    void testPropertyValue() {
        assertTrue(virtualThreadsEnabled);
    }

    @Test
    void testManagementSettings() {
        assertEquals("health,info,loggers", managementSettings);
    }

    @Test
    void testAppProperties() {
        assertEquals("test_control_exchange", appProperties.getMessageControlExchangeName());
        assertEquals(30000, appProperties.getMessageControlExpiryMs());
        assertEquals("test_project", appProperties.getProjectName());
    }
}
