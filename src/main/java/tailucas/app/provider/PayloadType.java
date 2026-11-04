package tailucas.app.provider;

/**
 * Identifies binary payloads by their magic bytes so a log record can say what a
 * blob <em>is</em> (a camera snapshot, a video clip) instead of only that it was
 * withheld. Deliberately a small, dependency-free sniffer: the payloads this
 * application sees come from device bridges, and the types below cover what they
 * publish.
 */
public final class PayloadType {

    public static final String UNKNOWN = "application/octet-stream";

    private PayloadType() { }

    /**
     * MIME type of the payload, or {@link #UNKNOWN} when the signature is not
     * recognised.
     */
    public static String detect(byte[] payload) {
        if (payload == null || payload.length < 4) {
            return UNKNOWN;
        }
        if (matches(payload, 0, 0xFF, 0xD8, 0xFF)) {
            // SOI followed by any marker: FFmpeg-written snapshots put a COM
            // segment first, so only the SOI can be relied on
            return "image/jpeg";
        }
        if (matches(payload, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (matches(payload, 0, 'G', 'I', 'F', '8')) {
            return "image/gif";
        }
        if (matches(payload, 0, 'R', 'I', 'F', 'F') && matches(payload, 8, 'W', 'E', 'B', 'P')) {
            return "image/webp";
        }
        if (matches(payload, 4, 'f', 't', 'y', 'p')) {
            // ISO base media: Ring publishes camera recordings as MP4
            return matches(payload, 8, 'h', 'e', 'i', 'c', 'm', 'i', 'f', '1')
                    || matches(payload, 8, 'h', 'e', 'i', 'c')
                    || matches(payload, 8, 'm', 'i', 'f', '1')
                ? "image/heif"
                : "video/mp4";
        }
        if (matches(payload, 0, '%', 'P', 'D', 'F')) {
            return "application/pdf";
        }
        if (matches(payload, 0, 'P', 'K', 0x03, 0x04)) {
            return "application/zip";
        }
        if (matches(payload, 0, 0x1F, 0x8B)) {
            return "application/gzip";
        }
        return UNKNOWN;
    }

    private static boolean matches(byte[] payload, int offset, int... signature) {
        if (payload.length < offset + signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if ((payload[offset + index] & 0xFF) != signature[index]) {
                return false;
            }
        }
        return true;
    }
}
