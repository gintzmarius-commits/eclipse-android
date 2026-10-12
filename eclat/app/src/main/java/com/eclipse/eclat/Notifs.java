package com.eclipse.eclat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/** Éclat's notification channels. */
public final class Notifs {
    public static final String CHANNEL_SEND = "send";
    public static final String CHANNEL_RECEIVE = "receive";
    public static final String CHANNEL_PLAYBACK = "playback";

    private Notifs() {
    }

    public static void init(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_SEND, c.getString(R.string.channel_send), NotificationManager.IMPORTANCE_LOW));
        NotificationChannel r = new NotificationChannel(CHANNEL_RECEIVE, c.getString(R.string.channel_receive), NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(r);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_PLAYBACK, c.getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW));
    }

    @SuppressWarnings("deprecation")
    public static Notification.Builder builder(Context c, String channel) {
        init(c);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel) : new Notification.Builder(c);
        b.setColor(0xFFF6A51A);
        return b;
    }
}
