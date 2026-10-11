package com.limelight;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

/**
 * The name this phone gives to a PC when pairing (Moonlight always sends
 * "roth"). Eclipse sends "Eclipse.<token>.<phone name>": the PC shows the
 * phone's real name, and recognizes the token of its QR code.
 * NvHTTP reads it through deviceName() (patched by scripts/apply_eclipse.py).
 */
public final class EclipsePairing {
    private static final ThreadLocal<String> TOKEN = new ThreadLocal<>();
    private static volatile String phoneName;

    private EclipsePairing() {
    }

    public static void init(Context context) {
        String n = null;
        try {
            if (Build.VERSION.SDK_INT >= 25) {
                n = Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME);
            }
        } catch (Exception ignored) {
        }
        if (n == null || EclipseQr.cleanName(n, 40).isEmpty()) {
            n = Build.MODEL;
        }
        phoneName = n;
    }

    /** Token of the QR code being used by this thread's pairing ("" for none). */
    public static void setToken(String token) {
        TOKEN.set(token);
    }

    public static void clearToken() {
        TOKEN.remove();
    }

    public static String deviceName() {
        String t = TOKEN.get();
        String n = phoneName;
        return EclipseQr.deviceName(t == null ? "" : t, n != null ? n : Build.MODEL);
    }
}
