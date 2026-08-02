package tailucas.app.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tailucas.app.AppProperties;

@WebMvcTest(EventHandler.class)
class EventHandlerTest {

    @Autowired
    private MockMvc mvc;

    // deep stubs allow the SLF4J fluent API chain (atDebug().setMessage()...) on the mock
    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS)
    private Logger log;

    @MockitoBean
    private AppProperties props;

    @Test
    void indexReportsRuntimeConfiguration() throws Exception {
        when(props.getProjectName()).thenReturn("test_project");
        when(props.getMessageControlExchangeName()).thenReturn("test_control_exchange");
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            // from src/test/resources/application.properties
            .andExpect(content().string(containsString("spring.threads.virtual.enabled true")))
            .andExpect(content().string(containsString("test_project")))
            .andExpect(content().string(containsString("test_control_exchange")));
    }
}
