package tailucas.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OnePassword {

    private static Logger log = LoggerFactory.getLogger(OnePassword.class);

    public void OnePassword() {

    }

    public static String opRequest(String httpMethod) {
        final String opServerAddr = System.getenv("OP_CONNECT_SERVER");
        final String opToken = System.getenv("OP_CONNECT_TOKEN");
        log.info("OP server is " + opServerAddr);
        String response = null;
        try {
            HttpURLConnection.setFollowRedirects(false);
            URL url = URI.create(opServerAddr+httpMethod).toURL();
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty("Authorization", "Bearer " + opToken);
            con.setRequestProperty("Content-type", "application/json");
            con.setConnectTimeout(1000);
            con.setReadTimeout(1000);
            int status = con.getResponseCode();
            log.info("1p response: " + status);
            InputStreamReader sRx = null;
            if (status > 299) {
                sRx = new InputStreamReader(con.getErrorStream());
            } else {
                sRx = new InputStreamReader(con.getInputStream());
            }
            BufferedReader in = new BufferedReader(sRx);
            String inputLine;
            StringBuffer content = new StringBuffer();
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
            }
            response = content.toString();
            log.info(response);
            in.close();
            con.disconnect();
        } catch (MalformedURLException e) {
            System.err.println(e);
        } catch (IOException e) {
            System.err.println(e);
        }
        return response;
    }

    public void getItems() {
        final String opServerAddr = System.getenv("OP_CONNECT_SERVER");
        final String opToken = System.getenv("OP_CONNECT_TOKEN");

        String opHealth = opRequest("/health");
        JSONObject health = new JSONObject(opHealth);
        log.info(health.toString());
        String opVaults = opRequest("/v1/vaults");
        JSONArray vaults = new JSONArray(opVaults);
        for (int i=0; i<vaults.length(); i++) {
            JSONObject vault = vaults.getJSONObject(i);
            final String vaultId = vault.getString("id");
            log.info("Vault ID is {}", vaultId);
            final String listPath = "/v1/vaults/"+vaultId+"/items";
            String opVaultItems = opRequest(listPath);
            JSONArray items = new JSONArray(opVaultItems);
            for (int j=0; j<items.length(); j++) {
                JSONObject item = items.getJSONObject(j);
                //log.info("Vault item is {}", item);
                final String itemId = item.getString("id");
                final String itemTitle = item.getString("title");
                log.info("Item title is {}", itemTitle);
                if (itemTitle.equals("myitem")) {
                    final String itemPath = "/v1/vaults/"+vaultId+"/items/"+itemId;
                    log.info("Calling {}", itemPath);
                    String opItemDetails = opRequest(itemPath);
                    JSONObject itemDetails = new JSONObject(opItemDetails);
                    log.info("item detail {}", itemDetails.toString());
                }
            }
        }
    }
}
