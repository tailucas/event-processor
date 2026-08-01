package tailucas.app.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sanctionco.opconnect.OPConnectClient;
import com.sanctionco.opconnect.model.Field;
import com.sanctionco.opconnect.model.Item;
import com.sanctionco.opconnect.model.Section;
import com.sanctionco.opconnect.model.Vault;

class OnePasswordTest {

    private static final String VAULT_ID = "vault-1";

    private OPConnectClient client;
    private OnePassword onePassword;

    @BeforeEach
    void setUp() {
        client = mock(OPConnectClient.class);
        onePassword = new OnePassword(client, VAULT_ID);
    }

    private static Section section(String label) {
        final Section section = mock(Section.class);
        when(section.getLabel()).thenReturn(label);
        return section;
    }

    private static Field field(String label, Section section, String value) {
        final Field field = mock(Field.class);
        when(field.getLabel()).thenReturn(label);
        when(field.getSection()).thenReturn(section);
        when(field.getValue()).thenReturn(value);
        return field;
    }

    private Item item(String id, String title, List<Field> fields) {
        final Item item = mock(Item.class);
        when(item.getId()).thenReturn(id);
        when(item.getTitle()).thenReturn(title);
        when(item.getFields()).thenReturn(fields);
        return item;
    }

    private void stubVaultItems(Item... items) {
        when(client.listItems(VAULT_ID))
            .thenReturn(CompletableFuture.completedFuture(List.of(items)));
    }

    private void stubItem(Item item) {
        when(client.getItem(VAULT_ID, item.getId()))
            .thenReturn(CompletableFuture.completedFuture(item));
    }

    @Test
    void fieldMatchedByNameAndSection() {
        final Item item = item("item-1", "Sentry", List.of(
            field("dsn", section("java"), "secret-dsn")));
        stubVaultItems(item);
        stubItem(item);
        assertEquals("secret-dsn", onePassword.getField("Sentry", "dsn", "java"));
    }

    @Test
    void fieldInOtherSectionNotMatched() {
        final Item item = item("item-1", "Sentry", List.of(
            field("dsn", section("python"), "other-dsn")));
        stubVaultItems(item);
        stubItem(item);
        final AssertionError ex = assertThrows(AssertionError.class,
            () -> onePassword.getField("Sentry", "dsn", "java"));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void nullSectionNameMatchesAnySection() {
        final Item item = item("item-1", "Sentry", List.of(
            field("dsn", section("java"), "secret-dsn")));
        stubVaultItems(item);
        stubItem(item);
        assertEquals("secret-dsn", onePassword.getField("Sentry", "dsn"));
    }

    @Test
    void ambiguousFieldAcrossSectionsThrows() {
        final Item item = item("item-1", "flags", List.of(
            field("value", section("flags"), "a"),
            field("value", section("other"), "b")));
        stubVaultItems(item);
        stubItem(item);
        final AssertionError ex = assertThrows(AssertionError.class,
            () -> onePassword.getField("flags", "value"));
        assertTrue(ex.getMessage().contains("ambiguous across sections"));
    }

    @Test
    void missingFieldThrows() {
        final Item item = item("item-1", "Sentry", List.of(
            field("other", section("java"), "x")));
        stubVaultItems(item);
        stubItem(item);
        final AssertionError ex = assertThrows(AssertionError.class,
            () -> onePassword.getField("Sentry", "dsn", "java"));
        assertTrue(ex.getMessage().contains("Credential field Sentry/dsn not found."));
    }

    @Test
    void sectionlessFieldNeverMatches() {
        // documents the current behavior: fields without a section are skipped entirely
        final Item item = item("item-1", "Sentry", List.of(
            field("dsn", null, "secret-dsn")));
        stubVaultItems(item);
        stubItem(item);
        assertThrows(AssertionError.class, () -> onePassword.getField("Sentry", "dsn"));
    }

    @Test
    void titleAndFieldNameAreRequired() {
        assertThrows(AssertionError.class, () -> onePassword.getField(null, "dsn"));
        assertThrows(AssertionError.class, () -> onePassword.getField("Sentry", null));
    }

    @Test
    void unknownItemTitleReturnsNull() {
        stubVaultItems(item("item-1", "Other", List.of()));
        assertNull(onePassword.getField("Sentry", "dsn"));
        verify(client, never()).getItem(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void itemIdLookupIsCached() {
        final Item item = item("item-1", "Sentry", List.of(
            field("dsn", section("java"), "v1"),
            field("traces", section("java"), "v2")));
        stubVaultItems(item);
        stubItem(item);
        onePassword.getField("Sentry", "dsn", "java");
        onePassword.getField("Sentry", "traces", "java");
        // the vault item listing happens only once
        verify(client, times(1)).listItems(VAULT_ID);
        verify(client, times(2)).getItem(VAULT_ID, "item-1");
    }

    @Test
    void vaultIdAndListing() {
        assertEquals(VAULT_ID, onePassword.getVaultId());
        final Vault vault = mock(Vault.class);
        final List<Vault> vaults = List.of(vault);
        when(client.listVaults()).thenReturn(CompletableFuture.completedFuture(vaults));
        assertSame(vaults, onePassword.listVaults());
    }

    @Test
    void closeDelegatesToClient() {
        onePassword.close();
        verify(client).close();
    }
}
