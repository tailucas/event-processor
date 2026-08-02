package tailucas.app.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tailucas.app.provider.DeviceConfig;

@WebMvcTest(DeviceConfigUpdate.class)
class DeviceConfigUpdateTest {

    @Autowired
    private MockMvc mvc;

    // deep stubs allow the SLF4J fluent API chain (atDebug().setMessage()...) on the mock
    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS)
    private Logger log;

    @Test
    void invalidateConfigEvictsAndEchoesDeviceKey() throws Exception {
        final DeviceConfig deviceConfig = mock(DeviceConfig.class);
        try (MockedStatic<DeviceConfig> mocked = mockStatic(DeviceConfig.class)) {
            mocked.when(DeviceConfig::getInstance).thenReturn(deviceConfig);
            mvc.perform(post("/invalidate_config").param("device_key", "Kitchen Smoke"))
                .andExpect(status().isOk())
                .andExpect(content().string("Kitchen Smoke"));
        }
        verify(deviceConfig).invalidateConfiguration("Kitchen Smoke");
    }

    @Test
    void missingDeviceKeyIsRejected() throws Exception {
        mvc.perform(post("/invalidate_config"))
            .andExpect(status().isBadRequest());
    }
}
