package com.eclipse.eclat.share;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds and reads the NDEF message the sending phone shows as an NFC tag.
 * Pure Java (no Android classes), so it is tested on the PC.
 *
 * The message holds, in this order:
 *  1. the link (URI record): what an iPhone opens, what Android dispatches on;
 *  2. optionally the Wi-Fi of the sender's hotspot (MIME record), when the
 *     sender is not on a Wi-Fi network;
 *  3. an Android Application Record: Android starts Éclat even when it is
 *     closed (and offers it on the Play Store when it is missing).
 */
public final class Ndef {
    public static final byte TNF_WELL_KNOWN = 1;
    public static final byte TNF_MIME = 2;
    public static final byte TNF_EXTERNAL = 4;
    public static final String WIFI_MIME = "application/vnd.eclat.wifi";
    public static final String AAR_TYPE = "android.com:pkg";

    private static final String[] URI_PREFIXES = {
            "", "http://www.", "https://www.", "http://", "https://",
    };

    public static final class Record {
        public final byte tnf;
        public final byte[] type;
        public final byte[] payload;

        public Record(byte tnf, byte[] type, byte[] payload) {
            this.tnf = tnf;
            this.type = type;
            this.payload = payload;
        }

        public String typeString() {
            return new String(type, StandardCharsets.US_ASCII);
        }

        /** The URI of a URI record, else null. */
        public String uri() {
            if (tnf != TNF_WELL_KNOWN || type.length != 1 || type[0] != 'U' || payload.length < 1) return null;
            int code = payload[0] & 0xFF;
            String prefix = code < URI_PREFIXES.length ? URI_PREFIXES[code] : "";
            return prefix + new String(payload, 1, payload.length - 1, StandardCharsets.UTF_8);
        }
    }

    public static Record uriRecord(String uri) {
        int code = 0;
        for (int i = URI_PREFIXES.length - 1; i > 0; i--) {
            if (uri.startsWith(URI_PREFIXES[i])) {
                code = i;
                break;
            }
        }
        byte[] rest = uri.substring(URI_PREFIXES[code].length()).getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[rest.length + 1];
        payload[0] = (byte) code;
        System.arraycopy(rest, 0, payload, 1, rest.length);
        return new Record(TNF_WELL_KNOWN, new byte[]{'U'}, payload);
    }

    public static Record mimeRecord(String mime, byte[] data) {
        return new Record(TNF_MIME, mime.getBytes(StandardCharsets.US_ASCII), data);
    }

    public static Record appRecord(String packageName) {
        return new Record(TNF_EXTERNAL, AAR_TYPE.getBytes(StandardCharsets.US_ASCII),
                packageName.getBytes(StandardCharsets.US_ASCII));
    }

    /** The whole message for one file to send. */
    public static byte[] shareMessage(ShareLink link, String wifiSsid, String wifiPass, String packageName) {
        List<Record> r = new ArrayList<>();
        r.add(uriRecord(link.toUri()));
        if (wifiSsid != null) {
            r.add(mimeRecord(WIFI_MIME, (wifiSsid + "\n" + (wifiPass == null ? "" : wifiPass))
                    .getBytes(StandardCharsets.UTF_8)));
        }
        r.add(appRecord(packageName));
        return encode(r);
    }

    public static byte[] encode(List<Record> records) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < records.size(); i++) {
            Record rec = records.get(i);
            boolean shortRecord = rec.payload.length < 256;
            int header = rec.tnf & 0x07;
            if (i == 0) header |= 0x80; // MB
            if (i == records.size() - 1) header |= 0x40; // ME
            if (shortRecord) header |= 0x10; // SR
            out.write(header);
            out.write(rec.type.length);
            if (shortRecord) {
                out.write(rec.payload.length);
            } else {
                int n = rec.payload.length;
                out.write(n >>> 24);
                out.write(n >>> 16);
                out.write(n >>> 8);
                out.write(n);
            }
            out.write(rec.type, 0, rec.type.length);
            out.write(rec.payload, 0, rec.payload.length);
        }
        return out.toByteArray();
    }

    public static List<Record> decode(byte[] msg) {
        List<Record> list = new ArrayList<>();
        int i = 0;
        while (i < msg.length) {
            int header = msg[i++] & 0xFF;
            boolean sr = (header & 0x10) != 0;
            boolean il = (header & 0x08) != 0;
            int typeLen = msg[i++] & 0xFF;
            int payloadLen;
            if (sr) {
                payloadLen = msg[i++] & 0xFF;
            } else {
                payloadLen = ((msg[i] & 0xFF) << 24) | ((msg[i + 1] & 0xFF) << 16) | ((msg[i + 2] & 0xFF) << 8) | (msg[i + 3] & 0xFF);
                i += 4;
            }
            int idLen = il ? msg[i++] & 0xFF : 0;
            byte[] type = new byte[typeLen];
            System.arraycopy(msg, i, type, 0, typeLen);
            i += typeLen + idLen;
            byte[] payload = new byte[payloadLen];
            System.arraycopy(msg, i, payload, 0, payloadLen);
            i += payloadLen;
            list.add(new Record((byte) (header & 0x07), type, payload));
            if ((header & 0x40) != 0) break;
        }
        return list;
    }
}
