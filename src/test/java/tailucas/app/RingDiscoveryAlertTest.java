package tailucas.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.github.dikhan.pagerduty.client.events.PagerDutyEventsClient;
import com.github.dikhan.pagerduty.client.events.domain.EventResult;
import com.github.dikhan.pagerduty.client.events.domain.ResolveIncident;
import com.github.dikhan.pagerduty.client.events.domain.TriggerIncident;
import com.github.dikhan.pagerduty.client.events.exceptions.NotifyEventException;

import tailucas.app.provider.OnePassword;
import tailucas.app.provider.RingDiscovery;

/**
 * Covers the silent-failure guard for the Ring bridge. A bridge that cannot reach
 * the Ring API publishes no MQTT traffic at all, so the only symptom it produces
 * is the absence of Ring events; startup therefore raises a PagerDuty incident
 * when no Ring device information arrives after discovery has been requested.
 */
class RingDiscoveryAlertTest {

    private static final String RING_TOPIC = "ring/location/alarm/device-123/status";

    private OnePassword previousCreds;
    private PagerDutyEventsClient previousPagerDuty;
    private String previousRoutingKey;
    private String previousAppName;
    private String previousDeviceName;
    private PagerDutyEventsClient pagerDuty;

    @BeforeEach
    void setUp() throws NotifyEventException {
        previousCreds = (OnePassword) TestStatics.getStaticField(EventProcessor.class, "creds");
        previousPagerDuty = EventProcessor.getPagerDuty();
        previousRoutingKey = EventProcessor.getPagerDutyRoutingKey();
        previousAppName = EventProcessor.getAppName();
        previousDeviceName = EventProcessor.getDeviceName();
        pagerDuty = installStatics("true");
        resetIncidentState();
        TestStatics.configureTestMetrics();
    }

    @AfterEach
    void tearDown() {
        TestStatics.setStaticField(EventProcessor.class, "creds", previousCreds);
        TestStatics.setStaticField(EventProcessor.class, "pagerDuty", previousPagerDuty);
        TestStatics.setStaticField(EventProcessor.class, "pagerDutyRoutingKey", previousRoutingKey);
        TestStatics.setStaticField(EventProcessor.class, "appName", previousAppName);
        TestStatics.setStaticField(EventProcessor.class, "deviceName", previousDeviceName);
        resetFeatureFlagCache();
        resetIncidentState();
    }

    @Test
    void missingRingInformationRaisesOneIncidentWithAStableDedupKey() throws NotifyEventException {
        EventProcessor.raiseRingDiscoveryIncident(120);
        // a restart, or a repeated check, must update the incident rather than open another
        EventProcessor.raiseRingDiscoveryIncident(120);

        final ArgumentCaptor<TriggerIncident> captor = ArgumentCaptor.forClass(TriggerIncident.class);
        verify(pagerDuty, times(1)).trigger(captor.capture());
        assertEquals("test_app-ring-discovery", captor.getValue().getDedupKey());
        assertEquals("test-routing-key", captor.getValue().getRoutingKey());
        assertTrue(captor.getValue().getPayload().getSummary().contains("no Ring device information"));
    }

    @Test
    void incidentIsResolvedExactlyOnce() throws NotifyEventException {
        EventProcessor.raiseRingDiscoveryIncident(120);
        EventProcessor.resolveRingDiscoveryIncident();
        EventProcessor.resolveRingDiscoveryIncident();

        final ArgumentCaptor<ResolveIncident> captor = ArgumentCaptor.forClass(ResolveIncident.class);
        verify(pagerDuty, times(1)).resolve(captor.capture());
        assertEquals("test_app-ring-discovery", captor.getValue().getDedupKey());
    }

    @Test
    void incidentIsSkippedWhenTheFeatureFlagIsDisabled() throws NotifyEventException {
        pagerDuty = installStatics("false");

        EventProcessor.raiseRingDiscoveryIncident(120);

        verifyNoInteractions(pagerDuty);
    }

    @Test
    void checkPassesWhenRingInformationHasArrived() throws NotifyEventException {
        final RingDiscovery ringDiscovery = RingDiscovery.getInstance();
        ringDiscovery.markDiscoveryRequested();
        ringDiscovery.markRingInformation(RING_TOPIC, false);

        assertTrue(EventProcessor.awaitRingDeviceInformation(appProperties(1)));
        verifyNoInteractions(pagerDuty);
    }

    @Test
    void checkIsSkippedWhenDisabledByConfiguration() throws NotifyEventException {
        assertTrue(EventProcessor.awaitRingDeviceInformation(appProperties(0)));
        verifyNoInteractions(pagerDuty);
    }

    private static AppProperties appProperties(int timeoutSeconds) {
        final AppProperties appProperties = new AppProperties();
        appProperties.setRingDiscoveryTimeoutSeconds(timeoutSeconds);
        return appProperties;
    }

    /** Installs the credential and PagerDuty statics, returning the PagerDuty mock. */
    private static PagerDutyEventsClient installStatics(String flagValue) throws NotifyEventException {
        final OnePassword creds = mock(OnePassword.class);
        when(creds.getField(anyString(), anyString(), anyString())).thenReturn(flagValue);
        final EventResult result = mock(EventResult.class);
        when(result.getStatus()).thenReturn("success");
        when(result.getDedupKey()).thenReturn("test_app-ring-discovery");
        final PagerDutyEventsClient client = mock(PagerDutyEventsClient.class);
        when(client.trigger(any(TriggerIncident.class))).thenReturn(result);
        when(client.resolve(any(ResolveIncident.class))).thenReturn(result);
        TestStatics.setStaticField(EventProcessor.class, "creds", creds);
        TestStatics.setStaticField(EventProcessor.class, "pagerDuty", client);
        TestStatics.setStaticField(EventProcessor.class, "pagerDutyRoutingKey", "test-routing-key");
        TestStatics.setStaticField(EventProcessor.class, "appName", "test_app");
        TestStatics.setStaticField(EventProcessor.class, "deviceName", "test_device");
        resetFeatureFlagCache();
        return client;
    }

    @SuppressWarnings("unchecked")
    private static void resetFeatureFlagCache() {
        ((Map<String, Boolean>) TestStatics.getStaticField(EventProcessor.class, "featureFlagCache")).clear();
    }

    private static void resetIncidentState() {
        ((AtomicBoolean) TestStatics.getStaticField(EventProcessor.class, "ringDiscoveryIncidentRaised")).set(false);
    }
}
