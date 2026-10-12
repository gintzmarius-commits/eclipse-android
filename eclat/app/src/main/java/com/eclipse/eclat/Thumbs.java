package com.eclipse.eclat;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Size;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Small JPEG previews of photos, videos and songs (album art). */
public final class Thumbs {
    private Thumbs() {
    }

    public static byte[] jpeg(Context c, Uri uri, String mime, int max) {
        Bitmap b = bitmap(c, uri, mime, max);
        if (b == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 82, out);
        b.recycle();
        return out.toByteArray();
    }

    public static Bitmap bitmap(Context c, Uri uri, String mime, int max) {
        if (mime == null) mime = "";
        boolean isFile = "file".equals(uri.getScheme());
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Size s = new Size(max, max);
                if (!isFile) {
                    try {
                        return c.getContentResolver().loadThumbnail(uri, s, null);
                    } catch (IOException | RuntimeException ignored) {
                        // not a media file: decoded below
                    }
                } else if (mime.startsWith("image/")) {
                    return ThumbnailUtils.createImageThumbnail(new File(uri.getPath()), s, null);
                } else if (mime.startsWith("video/")) {
                    return ThumbnailUtils.createVideoThumbnail(new File(uri.getPath()), s, null);
                } else if (mime.startsWith("audio/")) {
                    return ThumbnailUtils.createAudioThumbnail(new File(uri.getPath()), s, null);
                }
            }
            if (mime.startsWith("image/")) return decodeImage(c, uri, max);
            if (mime.startsWith("video/") || mime.startsWith("audio/")) return fromRetriever(c, uri, mime, max);
        } catch (Exception | OutOfMemoryError ignored) {
        }
        return null;
    }

    private static InputStream open(Context c, Uri uri) throws IOException {
        if ("file".equals(uri.getScheme())) return new FileInputStream(uri.getPath());
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("illisible");
        return in;
    }

    /** A photo decoded to at most max pixels on its long side, upright. */
    public static Bitmap decodeImage(Context c, Uri uri, int max) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = open(c, uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b;
        try (InputStream in = open(c, uri)) {
            b = BitmapFactory.decodeStream(in, null, o);
        }
        if (b == null) return null;
        int rot = 0;
        try (InputStream in = open(c, uri)) {
            int or = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            rot = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (Exception ignored) {
        }
        float scale = Math.min(1f, max / (float) Math.max(b.getWidth(), b.getHeight()));
        if (rot != 0 || scale < 1f) {
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postRotate(rot);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            b = r;
        }
        return b;
    }

    private static Bitmap fromRetriever(Context c, Uri uri, String mime, int max) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        ParcelFileDescriptor pfd = null;
        try {
            if ("file".equals(uri.getScheme())) {
                r.setDataSource(uri.getPath());
            } else {
                ContentResolver cr = c.getContentResolver();
                pfd = cr.openFileDescriptor(uri, "r");
                if (pfd == null) return null;
                r.setDataSource(pfd.getFileDescriptor());
            }
            Bitmap b = null;
            byte[] art = r.getEmbeddedPicture();
            if (art != null) {
                b = BitmapFactory.decodeByteArray(art, 0, art.length);
            } else if (mime.startsWith("video/")) {
                b = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (b == null) return null;
            float scale = max / (float) Math.max(b.getWidth(), b.getHeight());
            if (scale < 1f) {
                Bitmap s = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale), Math.round(b.getHeight() * scale), true);
                if (s != b) b.recycle();
                b = s;
            }
            return b;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** The album art of a song, as JPEG (null if none). */
    public static byte[] albumArt(Context c, Uri uri, int max) {
        Bitmap b = fromRetriever(c, uri, "audio/", max);
        if (b == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        b.recycle();
        return out.toByteArray();
    }
}
