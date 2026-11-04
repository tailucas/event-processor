package tailucas.app.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class PayloadTypeTest {

    private static byte[] bytes(int... values) {
        final byte[] payload = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            payload[index] = (byte) values[index];
        }
        return payload;
    }

    @Test
    void detectsTheFfmpegSnapshotFromTheBridge() {
        // exact prefix of a ring-mqtt camera snapshot: SOI, then a COM segment
        // carrying "Lavc61.19.101" before the quantisation tables
        final byte[] snapshot = bytes(0xFF, 0xD8, 0xFF, 0xFE, 0x00, 0x10,
            'L', 'a', 'v', 'c', '6', '1', '.', '1', '9', '.', '1', '0', '1', 0x00);
        assertEquals("image/jpeg", PayloadType.detect(snapshot));
    }

    @Test
    void detectsCommonImageTypes() {
        assertEquals("image/png", PayloadType.detect(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)));
        assertEquals("image/gif", PayloadType.detect("GIF89a".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("image/webp",
            PayloadType.detect("RIFFxxxxWEBPVP8 ".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void distinguishesVideoFromHeif() {
        assertEquals("video/mp4",
            PayloadType.detect("0000ftypisom".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("image/heif",
            PayloadType.detect("0000ftypmif1".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void detectsDocuments() {
        assertEquals("application/pdf", PayloadType.detect("%PDF-1.7".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("application/zip", PayloadType.detect(bytes(0x50, 0x4B, 0x03, 0x04)));
        assertEquals("application/gzip", PayloadType.detect(bytes(0x1F, 0x8B, 0x08, 0x00)));
    }

    @Test
    void reportsUnknownForMissingOrUnrecognisedPayloads() {
        assertEquals(PayloadType.UNKNOWN, PayloadType.detect(null));
        assertEquals(PayloadType.UNKNOWN, PayloadType.detect(new byte[0]));
        assertEquals(PayloadType.UNKNOWN, PayloadType.detect(new byte[] {1}));
        assertEquals(PayloadType.UNKNOWN, PayloadType.detect("ON".getBytes(StandardCharsets.US_ASCII)));
    }
}
