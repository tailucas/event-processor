package tailucas.app.provider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sanctionco.opconnect.OPConnectClient;
import com.sanctionco.opconnect.OPConnectClientBuilder;
import com.sanctionco.opconnect.model.Field;
import com.sanctionco.opconnect.model.Item;
import com.sanctionco.opconnect.model.Vault;

public class OnePassword {

    private static Logger log = null;
    private static OnePassword singleton = null;
    private OPConnectClient client = null;
    private String vaultId = null;
    private Map<String, String> itemNameIdMap = null;

    public static synchronized OnePassword getInstance() {
        if (singleton == null) {
            singleton = new OnePassword();
        }
        return singleton;
    }

    private OnePassword() {
        if (log == null) {
            log = LoggerFactory.getLogger(OnePassword.class);
        }
        final String opServerAddr = System.getenv("OP_CONNECT_HOST");
        log.info("Attempting to connect to 1Password at {}...", opServerAddr);
        final String opToken = System.getenv("OP_CONNECT_TOKEN");
        client = OPConnectClientBuilder.builder()
            .withEndpoint(opServerAddr)
            .withAccessToken(opToken)
            .build();
        this.vaultId = System.getenv("OP_VAULT");
        this.itemNameIdMap = new HashMap<>(100);
    }

    public void close() {
        client.close();
    }

    public String getVaultId() {
        return vaultId;
    }

    private String getItemIdfromTitle(String itemTitle) {
        if (itemNameIdMap.isEmpty()) {
            var items = client.listItems(vaultId).join();
            log.info("Vault {} contains {} items.", vaultId, items.size());
            items.forEach(i -> {
                itemNameIdMap.put(i.getTitle(), i.getId());
            });
        }
        return itemNameIdMap.get(itemTitle);
    }

    public String getField(String itemTitle, String fieldName, String sectionName) {
        if (itemTitle == null || fieldName == null) {
            throw new AssertionError("Credential title and field name are required.");
        }
        final String itemId = getItemIdfromTitle(itemTitle);
        if (itemId == null) {
            return null;
        }
        final Item item = client.getItem(vaultId, itemId).join();
        var fields = item.getFields();
        final List<Field> matchedFields = new ArrayList<>();
        final Map<String, List<Field>> fieldsForSection = new HashMap<>();
        fields.forEach(f -> {
            if (f.getLabel().equals(fieldName)) {
                if (f.getSection() != null) {
                    final String sectionLabel = f.getSection().getLabel();
                    if (sectionLabel.equals(sectionName)) {
                        matchedFields.add(f);
                    } else if (sectionName == null) {
                        matchedFields.add(f);
                    }
                    fieldsForSection.putIfAbsent(sectionLabel, new ArrayList<>());
                    fieldsForSection.get(sectionLabel).add(f);
                }
            }
        });
        if (matchedFields.size() > 1) {
            if (fieldsForSection.size() > 0) {
                throw new AssertionError(String.format("Credential field %s/%s is ambiguous across sections: %s", itemTitle, fieldName, fieldsForSection.keySet()));
            } else {
                throw new AssertionError(String.format("Credential field %s/%s is ambiguous across {} fields.", itemTitle, fieldName, matchedFields.size()));
            }
        } else if (matchedFields.isEmpty()) {
            throw new AssertionError(String.format("Credential field %s/%s not found.", itemTitle, fieldName));
        }
        return matchedFields.getFirst().getValue();
    }

    public String getField(String itemTitle, String fieldName) {
        return getField(itemTitle, fieldName, null);
    }

    public List<Vault> listVaults() {
        return client.listVaults().join();
    }
}
