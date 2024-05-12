package tailucas.app.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Metrics {

    private static Logger log = null;
    private static Metrics singleton = null;

    private String appName = null;
    private String deviceName = null;

    private String userId = null;
    private String apiKey = null;
    private String metricUrl = null;

    private Metrics() {
        log = LoggerFactory.getLogger(Metrics.class);
        final var env = System.getenv();
        appName = env.get("APP_NAME");
        deviceName = env.get("DEVICE_NAME");
        final var creds = OnePassword.getInstance();
        userId = creds.getField("Grafana", "user_id", appName);
        apiKey = creds.getField("Grafana", "token", appName);
        metricUrl = creds.getField("Grafana", "url", appName);
    }

    public static synchronized Metrics getInstance() {
        if (singleton == null) {
            singleton = new Metrics();
        }
        return singleton;
    }

    public void postMetric(String name, float value) {
        postMetric(name, value, null);
    }

    public void postMetric(String name, float value, Map<String,String> tags) {
        if (name == null) {
            throw new IllegalArgumentException("No metric name specified.");
        }
        final var metricTags = Map.of("application", appName, "device", deviceName);
        if (tags != null) {
            metricTags.putAll(tags);
        }
        final StringBuilder metricBuilder = new StringBuilder();
        metricBuilder.append(name);
        metricBuilder.append(",");
        metricBuilder.append(metricTags
            .entrySet()
            .stream()
            .map(tag -> tag.getKey() + "=" + tag.getValue())
            .reduce((pair1, pair2) -> pair1 + "," + pair2)
            .orElse(""));
        metricBuilder.append(" metric=");
        metricBuilder.append(value);
        final String metricString = metricBuilder.toString();
        HttpURLConnection con = null;
        OutputStream os = null;
        boolean osClosed = false;
        BufferedReader in = null;
        try {
            HttpURLConnection.setFollowRedirects(false);
            URL url = URI.create(metricUrl).toURL();
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Authorization", "Bearer " + userId + ":" + apiKey);
            con.setConnectTimeout(1000);
            con.setReadTimeout(1000);

            con.setDoOutput(true);
            os = con.getOutputStream();
            log.debug("Metric: {}", metricString);
            os.write(metricString.getBytes());
            os.flush();
            os.close();
            osClosed = true;

            int status = con.getResponseCode();
            InputStreamReader sRx = null;
            boolean httpError = false;
            if (status > 299) {
                httpError = true;
                sRx = new InputStreamReader(con.getErrorStream());
            } else {
                sRx = new InputStreamReader(con.getInputStream());
            }
            in = new BufferedReader(sRx);
            String inputLine;
            StringBuffer content = new StringBuffer();
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
            }
            String response = content.toString();
            if (httpError) {
                log.warn("Metrics issue ({}) response code {}: {}", metricString, status, response);
            }
        } catch (MalformedURLException e) {
            log.error("Metrics URL issue: {}", metricUrl, e);
        } catch (IOException e) {
            log.error("Metrics I/O issue ({})", metricString, e);
        } finally {
            if (os != null && !osClosed) {
                try {
                    os.close();
                } catch (IOException e) {
                    log.debug("Output stream close problem.", e);
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    log.debug("Reader close issue", e);
                }
            }
            if (con != null) {
                con.disconnect();
            }
        }
    }
}
