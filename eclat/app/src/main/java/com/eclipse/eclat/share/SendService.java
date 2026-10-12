package com.eclipse.eclat.share;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import com.eclipse.eclat.MainActivity;
import com.eclipse.eclat.Notifs;
import com.eclipse.eclat.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends one file: the phone becomes an NFC tag holding a link to the file
 * ({@link NfcTagService}) and serves the file on its local network
 * ({@link ShareServer}). Without Wi-Fi, it opens its own local hotspot and
 * puts its name and password in the tag too.
 *
 * The interface follows along through {@link #setWatcher}.
 */
public class SendService extends Service {
    public static final String ACTION_START = "com.eclipse.eclat.SEND";
    public static final String ACTION_STOP = "com.eclipse.eclat.SEND_STOP";
    private static final int NOTIF_ID = 11;
    private static final long TIMEOUT_MS = 10 * 60 * 1000;

    public interface Watcher {
        void onSendState(JSONObject state);
    }

    // Read by NfcTagService (another thread, same process).
    static volatile byte[] ndefMessage;
    private static volatile Watcher watcher;
    private static volatile JSONObject state = phase("idle");
    private static SendService running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ShareServer server;
    private WifiManager.LocalOnlyHotspotReservation hotspot;
    private UriSource source;
    private int generation;

    public static void setWatcher(Watcher w) {
        watcher = w;
        if (w != null) w.onSendState(state);
    }

    public static JSONObject state() {
        return state;
    }

    public static void start(Context c, Uri uri, String name, String mime, long size) {
        Intent i = new Intent(c, SendService.class).setAction(ACTION_START).setData(uri)
                .putExtra("name", name).putExtra("mime", mime).putExtra("size", size)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        ndefMessage = null;
        if (running != null) {
            c.startService(new Intent(c, SendService.class).setAction(ACTION_STOP));
        } else {
            publish(phase("idle"));
        }
    }

    private static JSONObject phase(String p) {
        JSONObject o = new JSONObject();
        try {
            o.put("phase", p);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private static void publish(JSONObject s) {
        state = s;
        Watcher w = watcher;
        if (w != null) w.onSendState(s);
    }

    private void update(String phase, Object... kv) {
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
        publish(s);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = this;
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            publish(phase("idle"));
            stopSelf();
            return START_NOT_STICKY;
        }
        foreground(getString(R.string.send_ready_title), intent.getStringExtra("name"));
        shutdown();
        final int gen = ++generation;
        source = new UriSource(this, intent.getData(), intent.getStringExtra("name"),
                intent.getStringExtra("mime"), intent.getLongExtra("size", -1));
        JSONObject s = phase("starting");
        try {
            s.put("name", source.name());
            s.put("mime", source.mime());
            s.put("size", source.size());
            s.put("uri", source.uri().toString());
        } catch (JSONException ignored) {
        }
        publish(s);
        new Thread(new Runnable() {
            @Override
            public void run() {
                begin(gen);
            }
        }, "eclat-send").start();
        main.removeCallbacksAndMessages(null);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                update("expired");
                shutdown();
                stopSelf();
            }
        }, TIMEOUT_MS);
        return START_NOT_STICKY;
    }

    private void begin(final int gen) {
        final String token = randomToken();
        server = new ShareServer(source, token, new ShareServer.Listener() {
            @Override
            public void onAsked(String who, boolean eclatPhone) {
                if (gen != generation) return;
                update("asked", "who", who, "eclat", eclatPhone);
                foreground(getString(R.string.send_asked_title, who), source.name());
            }

            @Override
            public void onDeclined(String who) {
                if (gen != generation) return;
                update("declined", "who", who);
            }

            @Override
            public void onProgress(long sent, long total) {
                if (gen != generation) return;
                update("sending", "sent", sent, "total", total);
            }

            @Override
            public void onSent(String who) {
                if (gen != generation) return;
                update("sent", "who", who);
                buzz(new long[]{0, 40, 80, 40});
            }
        });
        final int port;
        try {
            port = server.start();
        } catch (Exception e) {
            update("error", "error", getString(R.string.send_error_server));
            return;
        }
        source.thumbnail(); // ready before anyone asks
        if (Net.onWifi(this) || !canHotspot()) {
            publishTag(gen, token, port, null, null);
            return;
        }
        // No Wi-Fi: the phone opens its own small network for the other one.
        update("hotspot");
        main.post(new Runnable() {
            @Override
            public void run() {
                startHotspot(gen, token, port);
            }
        });
    }

    private boolean canHotspot() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings({"deprecation", "MissingPermission"})
    private void startHotspot(final int gen, final String token, final int port) {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        try {
            wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                    if (gen != generation) {
                        r.close();
                        return;
                    }
                    hotspot = r;
                    String ssid = null, pass = null;
                    if (Build.VERSION.SDK_INT >= 30) {
                        SoftApConfiguration c = r.getSoftApConfiguration();
                        ssid = c.getSsid();
                        pass = c.getPassphrase();
                    } else {
                        WifiConfiguration c = r.getWifiConfiguration();
                        if (c != null) {
                            ssid = c.SSID;
                            pass = c.preSharedKey;
                        }
                    }
                    if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() > 1) {
                        ssid = ssid.substring(1, ssid.length() - 1);
                    }
                    final String fs = ssid, fp = pass;
                    // the hotspot's address appears a moment later
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    publishTag(gen, token, port, fs, fp);
                                }
                            }).start();
                        }
                    }, 800);
                }

                @Override
                public void onFailed(int reason) {
                    publishTag(gen, token, port, null, null);
                }
            }, main);
        } catch (Exception e) {
            publishTag(gen, token, port, null, null);
        }
    }

    private void publishTag(int gen, String token, int port, String ssid, String pass) {
        if (gen != generation) return;
        List<String> addrs = Net.localAddresses();
        if (addrs.isEmpty()) {
            update("error", "error", getString(R.string.send_error_network));
            return;
        }
        List<String> others = new ArrayList<>(addrs.subList(1, Math.min(addrs.size(), 4)));
        ShareLink link = new ShareLink(addrs.get(0), port, token, source.name(), source.size(), source.mime(),
                Net.deviceName(this), others);
        ndefMessage = Ndef.shareMessage(link, ssid, pass, getPackageName());
        update("ready", "url", link.toUri(), "hotspot", ssid != null);
        foreground(getString(R.string.send_ready_title), getString(R.string.send_ready_text, source.name()));
    }

    /** Called by NfcTagService when a phone has read the tag. */
    static void onTagRead(Context c) {
        SendService s = running;
        if (s == null) return;
        String p = state.optString("phase");
        if (p.equals("ready") || p.equals("declined") || p.equals("sent")) {
            s.update("tapped");
        }
        s.buzz(new long[]{0, 30});
    }

    @SuppressWarnings("deprecation")
    void buzz(long[] pattern) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            else v.vibrate(pattern, -1);
        } catch (Exception ignored) {
        }
    }

    private void foreground(String title, String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, SendService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = Notifs.builder(this, Notifs.CHANNEL_SEND)
                .setSmallIcon(R.drawable.ic_nfc)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, getString(R.string.cancel), stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void shutdown() {
        generation++;
        ndefMessage = null;
        if (server != null) {
            server.stop();
            server = null;
        }
        if (hotspot != null) {
            try {
                hotspot.close();
            } catch (Exception ignored) {
            }
            hotspot = null;
        }
    }

    @Override
    public void onDestroy() {
        shutdown();
        main.removeCallbacksAndMessages(null);
        running = null;
        String p = state.optString("phase");
        if (!p.equals("expired")) publish(phase("idle"));
        super.onDestroy();
    }

    private static String randomToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
    }
}
