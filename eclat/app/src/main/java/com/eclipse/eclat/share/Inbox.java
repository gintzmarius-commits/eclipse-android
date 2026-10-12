package com.eclipse.eclat.share;

import android.content.Intent;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Parcelable;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/** The file being received: what the NFC tag said, and how it goes. */
public final class Inbox {
    public interface Watcher {
        void onReceiveState(JSONObject state);
    }

    /** What an NFC read gave: the link, and the sender's hotspot if any. */
    public static final class Offer {
        public final ShareLink link;
        public final String ssid;
        public final String pass;

        Offer(ShareLink link, String ssid, String pass) {
            this.link = link;
            this.ssid = ssid;
            this.pass = pass;
        }
    }

    private static volatile Watcher watcher;
    private static volatile JSONObject state = new JSONObject();
    static volatile Offer current;

    private Inbox() {
    }

    public static void setWatcher(Watcher w) {
        watcher = w;
        if (w != null) w.onReceiveState(state);
    }

    public static JSONObject state() {
        return state;
    }

    static void publish(String phase, Object... kv) {
        JSONObject s = new JSONObject();
        try {
            for (java.util.Iterator<String> it = state.keys(); it.hasNext(); ) {
                String k = it.next();
                s.put(k, state.get(k));
            }
            s.put("phase", phase);
            for (int i = 0; i + 1 < kv.length; i += 2) s.put((String) kv[i], kv[i + 1]);
        } catch (JSONException ignored) {
        }
        state = s;
        Watcher w = watcher;
        if (w != null) w.onReceiveState(s);
    }

    static void reset(Offer o) {
        current = o;
        state = new JSONObject();
    }

    /** Reads an NFC (or link) intent; null if it is not an Éclat transfer. */
    @SuppressWarnings("deprecation")
    public static Offer fromIntent(Intent intent) {
        if (intent == null) return null;
        String uri = null, ssid = null, pass = null;
        Parcelable[] raw = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage.class)
                : intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
        if (raw != null) {
            for (Parcelable p : raw) {
                for (NdefRecord r : ((NdefMessage) p).getRecords()) {
                    if (r.getTnf() == NdefRecord.TNF_WELL_KNOWN && uri == null) {
                        Uri u = r.toUri();
                        if (u != null) uri = u.toString();
                    } else if (r.getTnf() == NdefRecord.TNF_MIME_MEDIA
                            && Ndef.WIFI_MIME.equals(new String(r.getType(), StandardCharsets.US_ASCII))) {
                        String w = new String(r.getPayload(), StandardCharsets.UTF_8);
                        int nl = w.indexOf('\n');
                        ssid = nl < 0 ? w : w.substring(0, nl);
                        pass = nl < 0 ? "" : w.substring(nl + 1);
                    }
                }
            }
        }
        if (uri == null && intent.getData() != null) uri = intent.getDataString();
        ShareLink link = ShareLink.parse(uri);
        return link == null ? null : new Offer(link, ssid, pass);
    }
}
