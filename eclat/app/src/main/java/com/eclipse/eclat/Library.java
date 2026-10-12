package com.eclipse.eclat;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.eclipse.eclat.share.UriSource;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** What the phone holds: photos and videos, music, and its folders. */
public final class Library {
    private Library() {
    }

    private static Uri filesUri() {
        return Build.VERSION.SDK_INT >= 29 ? MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL) : MediaStore.Files.getContentUri("external");
    }

    /**
     * Photos and/or videos, newest first.
     *
     * @param kind   "all", "image" or "video"
     * @param bucket an album id, or null for every album
     */
    public static JSONArray media(Context c, String kind, String bucket, int offset, int limit) throws JSONException {
        JSONArray out = new JSONArray();
        String type = MediaStore.Files.FileColumns.MEDIA_TYPE;
        StringBuilder sel = new StringBuilder();
        List<String> args = new ArrayList<>();
        if ("image".equals(kind)) {
            sel.append(type).append("=").append(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE);
        } else if ("video".equals(kind)) {
            sel.append(type).append("=").append(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO);
        } else {
            sel.append(type).append(" IN (").append(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE).append(",")
                    .append(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO).append(")");
        }
        if (bucket != null && !bucket.isEmpty()) {
            sel.append(" AND ").append(MediaStore.MediaColumns.BUCKET_ID).append("=?");
            args.add(bucket);
        }
        String[] proj = {
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT, MediaStore.Files.FileColumns.MEDIA_TYPE, "duration",
                MediaStore.MediaColumns.DATE_ADDED, dateTaken(),
        };
        String order = dateTaken() + " DESC, " + MediaStore.MediaColumns.DATE_MODIFIED + " DESC";
        try (Cursor cur = query(c, filesUri(), proj, sel.toString(), args.toArray(new String[0]), order, offset, limit)) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                long id = cur.getLong(0);
                boolean video = cur.getInt(7) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
                Uri u = ContentUris.withAppendedId(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                JSONObject o = new JSONObject();
                o.put("uri", u.toString());
                o.put("name", cur.getString(1));
                o.put("mime", cur.getString(2) == null ? UriSource.guessMime(String.valueOf(cur.getString(1))) : cur.getString(2));
                o.put("size", cur.getLong(3));
                long taken = cur.isNull(10) ? 0 : cur.getLong(10);
                o.put("date", taken > 0 ? taken : cur.getLong(4) * 1000);
                o.put("w", cur.getInt(5));
                o.put("h", cur.getInt(6));
                o.put("kind", video ? "video" : "image");
                if (video) o.put("duration", cur.getLong(8));
                out.put(o);
            }
        }
        return out;
    }

    @SuppressWarnings("InlinedApi")
    private static String dateTaken() {
        return MediaStore.MediaColumns.DATE_TAKEN;
    }

    /** Pages through a query (LIMIT/OFFSET, written the way each Android version accepts). */
    private static Cursor query(Context c, Uri uri, String[] proj, String sel, String[] args, String order, int offset, int limit) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.os.Bundle b = new android.os.Bundle();
            b.putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, sel);
            b.putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args);
            b.putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order);
            b.putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit);
            b.putInt(android.content.ContentResolver.QUERY_ARG_OFFSET, offset);
            return c.getContentResolver().query(uri, proj, b, null);
        }
        return c.getContentResolver().query(uri, proj, sel, args, order + " LIMIT " + limit + " OFFSET " + offset);
    }

    /** Albums with photos or videos, the biggest first; "Camera" leads. */
    public static JSONArray albums(Context c) throws JSONException {
        String[] proj = {MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns._ID};
        String sel = MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + ","
                + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ")";
        Map<String, Object[]> m = new LinkedHashMap<>();
        try (Cursor cur = c.getContentResolver().query(filesUri(), proj, sel, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (cur != null) {
                while (cur.moveToNext()) {
                    String id = cur.getString(0);
                    if (id == null) continue;
                    Object[] a = m.get(id);
                    if (a == null) m.put(id, new Object[]{cur.getString(1), 1});
                    else a[1] = (Integer) a[1] + 1;
                }
            }
        }
        List<Map.Entry<String, Object[]>> l = new ArrayList<>(m.entrySet());
        Collections.sort(l, new Comparator<Map.Entry<String, Object[]>>() {
            @Override
            public int compare(Map.Entry<String, Object[]> a, Map.Entry<String, Object[]> b) {
                boolean ca = "Camera".equalsIgnoreCase(String.valueOf(a.getValue()[0]));
                boolean cb = "Camera".equalsIgnoreCase(String.valueOf(b.getValue()[0]));
                if (ca != cb) return ca ? -1 : 1;
                return (Integer) b.getValue()[1] - (Integer) a.getValue()[1];
            }
        });
        JSONArray out = new JSONArray();
        for (Map.Entry<String, Object[]> e : l) {
            JSONObject o = new JSONObject();
            o.put("id", e.getKey());
            o.put("name", e.getValue()[0] == null ? "Sans nom" : e.getValue()[0]);
            o.put("count", e.getValue()[1]);
            out.put(o);
        }
        return out;
    }

    /** Songs, by title. */
    public static JSONArray music(Context c) throws JSONException {
        JSONArray out = new JSONArray();
        String[] proj = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.ALBUM_ID};
        String sel = MediaStore.Audio.Media.DURATION + " >= 15000";
        try (Cursor cur = c.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, null,
                MediaStore.Audio.Media.TITLE + " COLLATE LOCALIZED ASC")) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("uri", ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cur.getLong(0)).toString());
                o.put("title", cur.getString(1));
                String artist = cur.getString(2);
                o.put("artist", artist == null || artist.equals("<unknown>") ? "" : artist);
                o.put("album", cur.getString(3) == null ? "" : cur.getString(3));
                o.put("duration", cur.getLong(4));
                o.put("mime", cur.getString(5) == null ? "audio/*" : cur.getString(5));
                o.put("size", cur.getLong(6));
                o.put("name", cur.getString(7));
                o.put("kind", "audio");
                out.put(o);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Folders

    public static String home() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    public static String kindOf(String mime) {
        if (mime.startsWith("image/")) return "image";
        if (mime.startsWith("video/")) return "video";
        if (mime.startsWith("audio/")) return "audio";
        if (mime.equals("application/pdf")) return "pdf";
        if (mime.startsWith("text/")) return "text";
        if (mime.contains("zip") || mime.contains("rar") || mime.contains("7z") || mime.contains("tar")) return "archive";
        if (mime.equals("application/vnd.android.package-archive")) return "apk";
        return "file";
    }

    public static JSONObject list(String path, boolean showHidden) throws JSONException {
        JSONObject res = new JSONObject();
        File dir = new File(path == null || path.isEmpty() ? home() : path);
        res.put("path", dir.getAbsolutePath());
        res.put("home", home());
        File parent = dir.getParentFile();
        res.put("parent", dir.getAbsolutePath().equals(home()) || parent == null ? "" : parent.getAbsolutePath());
        JSONArray items = new JSONArray();
        File[] files = dir.listFiles();
        res.put("readable", files != null);
        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().toLowerCase(Locale.ROOT).compareTo(b.getName().toLowerCase(Locale.ROOT));
                }
            });
            for (File f : files) {
                if (!showHidden && f.getName().startsWith(".")) continue;
                JSONObject o = new JSONObject();
                o.put("name", f.getName());
                o.put("path", f.getAbsolutePath());
                o.put("date", f.lastModified());
                if (f.isDirectory()) {
                    o.put("dir", true);
                    String[] sub = f.list();
                    o.put("count", sub == null ? -1 : sub.length);
                } else {
                    String mime = UriSource.guessMime(f.getName());
                    o.put("uri", Uri.fromFile(f).toString());
                    o.put("mime", mime);
                    o.put("size", f.length());
                    o.put("kind", kindOf(mime));
                }
                items.put(o);
            }
        }
        res.put("items", items);
        return res;
    }

    /** Shortcuts shown above the folders. */
    public static JSONArray places() throws JSONException {
        JSONArray a = new JSONArray();
        String[][] p = {
                {"Stockage", ""},
                {"Téléchargements", Environment.DIRECTORY_DOWNLOADS},
                {"Appareil photo", Environment.DIRECTORY_DCIM},
                {"Documents", Environment.DIRECTORY_DOCUMENTS},
                {"Musique", Environment.DIRECTORY_MUSIC},
                {"Films", Environment.DIRECTORY_MOVIES},
        };
        for (String[] x : p) {
            JSONObject o = new JSONObject();
            o.put("name", x[0]);
            o.put("path", x[1].isEmpty() ? home() : home() + "/" + x[1]);
            a.put(o);
        }
        return a;
    }
}
