package com.eclipse.eclat;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import com.eclipse.eclat.share.UriSource;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Gives other apps one file of the phone (to open it, or share it), like
 * AndroidX's FileProvider: content://com.eclipse.eclat.files/<path>.
 * Only the files Éclat hands out, read-only.
 */
public class FileAccess extends ContentProvider {

    public static String authority(Context c) {
        return c.getPackageName() + ".files";
    }

    public static Uri uriFor(Context c, File f) {
        return new Uri.Builder().scheme("content").authority(authority(c)).encodedPath(Uri.encode(f.getAbsolutePath(), "/")).build();
    }

    /** A content:// uri other apps may read (file:// uris become ours). */
    public static Uri shareable(Context c, Uri u) {
        return "file".equals(u.getScheme()) ? uriFor(c, new File(u.getPath())) : u;
    }

    /** Opens a file: videos and music in Éclat's player, photos in its viewer, the rest in another app. */
    public static Intent openIntent(Context c, Uri uri, String mime, String name) {
        if (mime == null) mime = UriSource.guessMime(name == null ? uri.getLastPathSegment() : name);
        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
            return new Intent(c, PlayerActivity.class).setAction(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .putExtra("title", name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        if (mime.startsWith("image/")) {
            return new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_SHOW).setDataAndType(uri, mime)
                    .putExtra("name", name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(shareable(c, uri), mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(view, c.getString(R.string.open_with)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private File fileOf(Uri uri) throws FileNotFoundException {
        String path = uri.getPath();
        if (path == null) throw new FileNotFoundException();
        File f = new File(path);
        try {
            f = f.getCanonicalFile();
        } catch (java.io.IOException e) {
            throw new FileNotFoundException();
        }
        if (!f.isFile()) throw new FileNotFoundException(path);
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("lecture seule");
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fileOf(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        if (projection == null) projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(projection, 1);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(projection[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String last = uri.getLastPathSegment();
        return UriSource.guessMime(last == null ? "" : last);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
