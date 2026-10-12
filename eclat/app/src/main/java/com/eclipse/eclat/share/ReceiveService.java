package com.eclipse.eclat.share;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;

import com.eclipse.eclat.FileAccess;
import com.eclipse.eclat.Notifs;
import com.eclipse.eclat.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Receives the file the user accepted: from the sender over the shared
 * Wi-Fi, or by joining the sender's own hotspot. The file goes to
 * Pictures/Éclat, Movies/Éclat, Music/Éclat or Download/Éclat.
 */
public class ReceiveService extends Service {
    public static final String ACTION_ACCEPT = "com.eclipse.eclat.ACCEPT";
    public static final String ACTION_CANCEL = "com.eclipse.eclat.RECEIVE_CANCEL";
    private static final int NOTIF_ID = 21;
    public static final String FOLDER = "Éclat";

    private volatile boolean cancelled;
    private ConnectivityManager.NetworkCallback netCallback;
    private Thread worker;

    public static void accept(Context c, String host) {
        Intent i = new Intent(c, ReceiveService.class).setAction(ACTION_ACCEPT).putExtra("host", host);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final Inbox.Offer offer = Inbox.current;
        if (intent == null || ACTION_CANCEL.equals(intent.getAction()) || offer == null) {
            cancelled = true;
            if (offer == null || intent == null) stopSelf();
            return START_NOT_STICKY;
        }
        if (worker != null && worker.isAlive()) return START_NOT_STICKY;
        foreground(progressNotif(offer, -1, -1));
        cancelled = false;
        final String host = intent.getStringExtra("host");
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                receive(offer, host);
                releaseNetwork();
                stopForeground(true);
                stopSelf();
            }
        }, "eclat-receive");
        worker.start();
        return START_NOT_STICKY;
    }

    private void receive(Inbox.Offer offer, String host) {
        ShareLink link = offer.link;
        String me = Net.deviceName(this);
        Fetch.Opener opener = Fetch.DEFAULT;
        Inbox.publish("connecting");
        if (host == null) host = Fetch.findHost(opener, link, me, 2500);
        if (host == null && offer.ssid != null) {
            Inbox.publish("joining");
            final Network net = joinHotspot(offer.ssid, offer.pass);
            if (net != null) {
                opener = new Fetch.Opener() {
                    @Override
                    public HttpURLConnection open(URL url) throws IOException {
                        return (HttpURLConnection) net.openConnection(url);
                    }
                };
                for (int i = 0; i < 5 && host == null && !cancelled; i++) {
                    host = Fetch.findHost(opener, link, me, 2500);
                }
            }
        }
        if (host == null) {
            Inbox.publish("error", "error", getString(R.string.receive_error_unreachable, link.device.isEmpty() ? getString(R.string.the_other_phone) : link.device));
            return;
        }
        Saved out;
        try {
            out = create(link.name, link.mime);
        } catch (IOException e) {
            Inbox.publish("error", "error", getString(R.string.receive_error_storage));
            return;
        }
        final NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        final long[] last = {0};
        try {
            Inbox.publish("receiving", "got", 0, "total", link.size);
            Fetch.download(opener, link, host, me, out.stream, new Fetch.Progress() {
                @Override
                public boolean onProgress(long got, long total) {
                    long now = System.currentTimeMillis();
                    if (now - last[0] > 150) {
                        last[0] = now;
                        Inbox.publish("receiving", "got", got, "total", total);
                        nm.notify(NOTIF_ID, progressNotif(offer, got, total));
                    }
                    return !cancelled;
                }
            });
            out.stream.close();
            Uri uri = out.finish(this);
            Inbox.publish("done", "uri", uri.toString(), "mime", link.mime, "name", link.name);
            doneNotif(link, uri);
        } catch (IOException e) {
            out.abort(this);
            Inbox.publish(cancelled ? "cancelled" : "error", "error", getString(R.string.receive_error_broken));
        }
    }

    // ------------------------------------------------------------------
    // Joining the sender's hotspot

    @SuppressWarnings({"deprecation", "MissingPermission"})
    private Network joinHotspot(String ssid, String pass) {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (Build.VERSION.SDK_INT >= 29) {
            Network n = request(cm, new WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(pass).build());
            if (n == null && !cancelled && Build.VERSION.SDK_INT >= 29) {
                n = request(cm, new WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa3Passphrase(pass).build());
            }
            return n;
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiConfiguration conf = new WifiConfiguration();
        conf.SSID = "\"" + ssid + "\"";
        conf.preSharedKey = "\"" + pass + "\"";
        int id = wm.addNetwork(conf);
        if (id < 0) return null;
        wm.enableNetwork(id, true);
        return request(cm, null);
    }

    private Network request(ConnectivityManager cm, WifiNetworkSpecifier spec) {
        releaseNetwork();
        NetworkRequest.Builder b = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        if (spec != null && Build.VERSION.SDK_INT >= 29) b.setNetworkSpecifier(spec);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Network> got = new AtomicReference<>();
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                got.set(network);
                done.countDown();
            }

            @Override
            public void onUnavailable() {
                done.countDown();
            }
        };
        try {
            cm.requestNetwork(b.build(), netCallback);
            done.await(60, TimeUnit.SECONDS); // the user approves the network
        } catch (Exception e) {
            return null;
        }
        return got.get();
    }

    private void releaseNetwork() {
        if (netCallback != null) {
            try {
                ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCallback);
            } catch (Exception ignored) {
            }
            netCallback = null;
        }
    }

    // ------------------------------------------------------------------
    // Where the file goes

    static final class Saved {
        OutputStream stream;
        Uri pending; // Android 10+
        File file;   // before

        Uri finish(Context c) {
            if (pending != null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                c.getContentResolver().update(pending, v, null, null);
                return pending;
            }
            MediaScannerConnection.scanFile(c, new String[]{file.getAbsolutePath()}, null, null);
            return FileAccess.uriFor(c, file);
        }

        void abort(Context c) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
            if (pending != null) {
                c.getContentResolver().delete(pending, null, null);
            } else if (file != null) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private Saved create(String name, String mime) throws IOException {
        Saved s = new Saved();
        String dir = mime.startsWith("image/") ? Environment.DIRECTORY_PICTURES
                : mime.startsWith("video/") ? Environment.DIRECTORY_MOVIES
                : mime.startsWith("audio/") ? Environment.DIRECTORY_MUSIC
                : Environment.DIRECTORY_DOWNLOADS;
        name = name.replaceAll("[/\\\\:*?\"<>|]", "_");
        if (Build.VERSION.SDK_INT >= 29) {
            Uri coll = mime.startsWith("image/") ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : mime.startsWith("video/") ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : mime.startsWith("audio/") ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, dir + "/" + FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver cr = getContentResolver();
            Uri u = cr.insert(coll, v);
            if (u == null) throw new IOException("insert");
            s.pending = u;
            s.stream = cr.openOutputStream(u);
            if (s.stream == null) throw new IOException("open");
            return s;
        }
        File folder = new File(Environment.getExternalStoragePublicDirectory(dir), FOLDER);
        //noinspection ResultOfMethodCallIgnored
        folder.mkdirs();
        File f = new File(folder, name);
        int dot = name.lastIndexOf('.');
        for (int i = 1; f.exists(); i++) {
            f = new File(folder, dot > 0 ? name.substring(0, dot) + " (" + i + ")" + name.substring(dot) : name + " (" + i + ")");
        }
        s.file = f;
        s.stream = new FileOutputStream(f);
        return s;
    }

    // ------------------------------------------------------------------

    private Notification progressNotif(Inbox.Offer offer, long got, long total) {
        Notification.Builder b = Notifs.builder(this, Notifs.CHANNEL_SEND)
                .setSmallIcon(R.drawable.ic_nfc)
                .setContentTitle(getString(R.string.receive_progress_title, offer.link.name))
                .setContentText(offer.link.device)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (total > 0) b.setProgress(1000, (int) (got * 1000 / total), false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private void doneNotif(ShareLink link, Uri uri) {
        Intent open = FileAccess.openIntent(this, uri, link.mime, link.name);
        PendingIntent pi = PendingIntent.getActivity(this, 3, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = Notifs.builder(this, Notifs.CHANNEL_RECEIVE)
                .setSmallIcon(R.drawable.ic_nfc)
                .setContentTitle(getString(R.string.receive_done_title))
                .setContentText(link.name + (link.device.isEmpty() ? "" : " · " + link.device))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(22, n);
    }

    private void foreground(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    @Override
    public void onDestroy() {
        cancelled = true;
        releaseNetwork();
        super.onDestroy();
    }
}
