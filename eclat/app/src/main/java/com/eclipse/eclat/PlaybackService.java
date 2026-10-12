package com.eclipse.eclat;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

/**
 * Keeps music playing when the screen is off or another app is open, with
 * play/pause and next in the notification.
 */
public class PlaybackService extends Service {
    private static final int NOTIF_ID = 31;
    private static final String TOGGLE = "toggle", NEXT = "next", PREV = "prev", CLOSE = "close";
    private static boolean running;
    private static String shownUri;
    private static boolean shownPlaying;
    private static Bitmap art;

    static void show(Context c, JSONObject item) {
        if (running || item == null) return;
        running = true;
        try {
            Intent i = new Intent(c, PlaybackService.class).putExtra("item", item.toString());
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            running = false; // not allowed from the background: no notification, music goes on
        }
    }

    static void update(Context c, JSONObject item, boolean playing) {
        if (!running || item == null) return;
        String uri = item.optString("uri");
        if (uri.equals(shownUri) && playing == shownPlaying) return;
        if (!uri.equals(shownUri)) art = Thumbs.bitmap(c, Uri.parse(uri), item.optString("mime", "audio/*"), 256);
        shownUri = uri;
        shownPlaying = playing;
        ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, build(c, item, playing));
    }

    static void hide(Context c) {
        if (!running) return;
        running = false;
        shownUri = null;
        c.stopService(new Intent(c, PlaybackService.class));
    }

    private static PendingIntent action(Context c, String a, int code) {
        return PendingIntent.getService(c, code, new Intent(c, PlaybackService.class).setAction(a),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static Notification build(Context c, JSONObject item, boolean playing) {
        PendingIntent open = PendingIntent.getActivity(c, 30, new Intent(c, PlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String title = item.optString("title", item.optString("name"));
        Notification.Builder b = Notifs.builder(c, Notifs.CHANNEL_PLAYBACK)
                .setSmallIcon(R.drawable.ic_play)
                .setContentTitle(title)
                .setContentText(item.optString("artist", ""))
                .setContentIntent(open)
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_prev), c.getString(R.string.previous), action(c, PREV, 33)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, playing ? R.drawable.ic_pause : R.drawable.ic_play),
                        c.getString(playing ? R.string.pause : R.string.play), action(c, TOGGLE, 32)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_next), c.getString(R.string.next), action(c, NEXT, 34)).build())
                .setDeleteIntent(action(c, CLOSE, 35));
        if (art != null) b.setLargeIcon(art);
        return b.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        PlayerActivity p = PlayerActivity.current;
        if (a != null) {
            if (p != null) {
                if (TOGGLE.equals(a)) p.toggle();
                else if (NEXT.equals(a)) p.next();
                else if (PREV.equals(a)) p.previous();
                else if (CLOSE.equals(a)) p.finish();
            }
            return START_NOT_STICKY;
        }
        JSONObject item;
        try {
            item = new JSONObject(intent.getStringExtra("item"));
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = build(this, item, true);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIF_ID, n);
        shownUri = null;
        update(this, item, true);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }
}
