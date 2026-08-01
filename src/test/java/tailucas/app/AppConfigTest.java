package tailucas.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Member;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.beans.factory.InjectionPoint;

class AppConfigTest {

    @Test
    void loggerIsNamedForTheInjectionPointClass() {
        final InjectionPoint injectionPoint = mock(InjectionPoint.class);
        final Member member = mock(Member.class);
        when(injectionPoint.getMember()).thenReturn(member);
        doReturn(AppProperties.class).when(member).getDeclaringClass();
        final Logger log = new AppConfig().produceLogger(injectionPoint);
        assertEquals(AppProperties.class.getName(), log.getName());
    }
}
