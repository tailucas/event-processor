package tailucas.app.provider;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sanctionco.opconnect.OPConnectClient;
import com.sanctionco.opconnect.OPConnectClientBuilder;
import com.sanctionco.opconnect.model.Vault;

public class OnePassword {

    private static Logger log = null;
    private OPConnectClient client = null;

    public OnePassword() {
        if (log == null) {
            log = LoggerFactory.getLogger(OnePassword.class);
        }
        final String opServerAddr = System.getenv("OP_CONNECT_SERVER");
        final String opToken = System.getenv("OP_CONNECT_TOKEN");
        client = OPConnectClientBuilder.builder()
            .withEndpoint(opServerAddr)
            .withAccessToken(opToken)
            .build();
    }

    public void close() {
        client.close();
    }

    public void listVaults() {
        List<Vault> vaults = client.listVaults().join();
        log.info("1P vaults are {}", vaults);
    }
}
