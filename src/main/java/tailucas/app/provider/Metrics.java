package tailucas.app.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Metrics {

    private static Logger log = null;

    public Metrics() {
        if (log == null) {
            log = LoggerFactory.getLogger(Metrics.class);
        }
    }

    public void postMetric() {
        final String userId = System.getenv("GRAFANA_USER");
        final String apiKey = System.getenv("GRAFANA_TOKEN");
        String response = null;
        try {
            HttpURLConnection.setFollowRedirects(false);
            URL url = URI.create("https://influx-prod-24-prod-eu-west-2.grafana.net/api/v1/push/influx/write").toURL();
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Authorization", "Bearer " + userId + ":" + apiKey);
            con.setConnectTimeout(1000);
            con.setReadTimeout(1000);

            String plainText = "meh,meh_label=abc,source=event_processor metric=12.3";
            con.setDoOutput(true);
            OutputStream os = con.getOutputStream();
            os.write(plainText.getBytes());
            os.flush();
            os.close();

            int status = con.getResponseCode();
            log.info("Grafana response: " + status);
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
    }
}
