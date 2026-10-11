package com.limelight;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;

/**
 * Home of Eclipse's built-in Tailscale (Go library "eclipsenet"). It runs
 * in its own process (":tunnel" in the manifest): if the network engine
 * ever fails, only this process stops — Eclipse stays open, and Android
 * starts the engine again at the next request. The app talks to it through
 * {@link EclipseTunnel}; nothing else may load the Go library.
 */
public final class EclipseTunnelProvider extends ContentProvider {
    /** Report of the last fatal error of the engine (read by the app). */
    static final String CRASH_FILE = "tunnel-crash.txt";

    private static boolean prepared;

    @Override
    public boolean onCreate() {
        return true; // the Go library is loaded at the first request only
    }

    private synchronized void prepare() {
        if (prepared) return;
        prepared = true;
        try {
            eclipsenet.Eclipsenet.setCrashFile(new File(getContext().getFilesDir(), CRASH_FILE).getAbsolutePath());
        } catch (Throwable t) {
            LimeLog.warning("Eclipse: tunnel crash file: " + t);
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if (extras == null) extras = new Bundle();
        // Only Eclipse itself (same app) may ask.
        if (android.os.Binder.getCallingUid() != android.os.Process.myUid()) {
            out.putString("error", "refusé");
            return out;
        }
        try {
            prepare();
            switch (method) {
                case "status":
                    out.putString("json", eclipsenet.Eclipsenet.status());
                    break;
                case "start":
                    eclipsenet.Eclipsenet.start(extras.getString("dir", ""), extras.getString("hostname", "eclipse"));
                    break;
                case "login":
                    eclipsenet.Eclipsenet.login();
                    break;
                case "logout":
                    eclipsenet.Eclipsenet.logout();
                    break;
                case "forward":
                    eclipsenet.Eclipsenet.forward(arg, extras.getString("tcp", ""), extras.getString("udp", ""));
                    break;
                case "forwardAt":
                    eclipsenet.Eclipsenet.forwardAt(extras.getString("local", ""), arg,
                            extras.getString("tcp", ""), extras.getString("udp", ""));
                    break;
                case "stopForward":
                    eclipsenet.Eclipsenet.stopForward(arg);
                    break;
                case "discover":
                    out.putString("json", eclipsenet.Eclipsenet.discover(extras.getString("ports", "47989"),
                            extras.getInt("timeout", 4000)));
                    break;
                case "lastLog":
                    out.putString("text", eclipsenet.Eclipsenet.lastLog());
                    break;
                default:
                    out.putString("error", "unknown request " + method);
            }
        } catch (Throwable t) {
            out.putString("error", t.getMessage() == null ? t.toString() : t.getMessage());
        }
        return out;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
