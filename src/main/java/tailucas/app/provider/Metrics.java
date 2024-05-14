package tailucas.app.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;

public class Metrics {

    private static Logger log = null;
    private static Metrics singleton = null;

    private String appName = null;
    private String deviceName = null;

    private InfluxDBClient influxDBClient = null;
    private WriteApi writeApi = null;

    private Metrics() {
        log = LoggerFactory.getLogger(Metrics.class);
        final var env = System.getenv();
        appName = env.get("APP_NAME");
        deviceName = env.get("DEVICE_NAME");
        final var creds = OnePassword.getInstance();
        final char[] token = creds.getField("InfluxDB", "token", appName).toCharArray();
        final String bucket = creds.getField("InfluxDB", "bucket", appName);
        final String org = creds.getField("InfluxDB", "org", "local");
        final String url = creds.getField("InfluxDB", "url", "local");
        log.info("Metrics client using org {}, bucket {} at URL {}", org, bucket, url);
        influxDBClient = InfluxDBClientFactory.create(url, token, org, bucket);
        writeApi = influxDBClient.makeWriteApi();
    }

    private static String normalize(String value) {
        return value.toLowerCase().replace(' ', '-');
    }

    public static synchronized Metrics getInstance() {
        if (singleton == null) {
            singleton = new Metrics();
        }
        return singleton;
    }

    public void close() {
        if (writeApi != null) {
            writeApi.close();
        }
        if (influxDBClient != null) {
            influxDBClient.close();
        }
    }

    public Map<String, String> getNormalizedMetricTags(Map<String, String> tags) {
        final Map<String, String> metricTags = new HashMap<>();
        metricTags.put("application", normalize(appName));
        metricTags.put("device", normalize(deviceName));
        if (tags != null) {
            tags.forEach((k,v) -> {
                metricTags.put(k, normalize(v));
            });
        }
        return metricTags;
    }

    public void postMetric(String name, float value) {
        postMetric(name, value, null);
    }

    public Map<String, String> postMetric(String name, float value, Map<String,String> tags) {
        if (name == null) {
            throw new IllegalArgumentException("No metric name specified.");
        }
        final var metricTags = getNormalizedMetricTags(tags);
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
        log.debug("Metric: {}", metricString);
        writeApi.writeRecord(WritePrecision.NS, metricString);
        return metricTags;
    }
}
