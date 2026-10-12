package com.eclipse.eclat.share;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import com.eclipse.eclat.Thumbs;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** A file of the phone (content:// or a path), served by {@link ShareServer}. */
public final class UriSource implements ShareServer.Source {
    private final Context context;
    private final Uri uri;
    private final String name;
    private final String mime;
    private final long size;
    private byte[] thumb;
    private boolean thumbDone;

    public UriSource(Context context, Uri uri, String name, String mime, long size) {
        this.context = context.getApplicationContext();
        this.uri = uri;
        String n = name, m = mime;
        long s = size;
        if ("file".equals(uri.getScheme())) {
            File f = new File(uri.getPath());
            if (n == null) n = f.getName();
            if (s <= 0) s = f.length();
        } else if (n == null || s <= 0) {
            try (Cursor c = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    if (n == null) n = c.getString(0);
                    if (s <= 0 && !c.isNull(1)) s = c.getLong(1);
                }
            } catch (Exception ignored) {
            }
        }
        if (n == null) n = "fichier";
        if (m == null || m.isEmpty() || m.equals("*/*")) {
            if ("content".equals(uri.getScheme())) m = context.getContentResolver().getType(uri);
            if (m == null) m = guessMime(n);
        }
        this.name = n;
        this.mime = m;
        this.size = s <= 0 ? -1 : s;
    }

    public static String guessMime(String name) {
        int dot = name.lastIndexOf('.');
        String m = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        return m == null ? "application/octet-stream" : m;
    }

    public Uri uri() {
        return uri;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String mime() {
        return mime;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public InputStream open() throws IOException {
        if ("file".equals(uri.getScheme())) return new FileInputStream(uri.getPath());
        ContentResolver cr = context.getContentResolver();
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("illisible");
        return in;
    }

    @Override
    public synchronized byte[] thumbnail() {
        if (!thumbDone) {
            thumbDone = true;
            thumb = Thumbs.jpeg(context, uri, mime, 480);
        }
        return thumb;
    }
}
