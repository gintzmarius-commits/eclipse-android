package com.eclipse.eclat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.eclipse.eclat.share.UriSource;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.IOException;
import java.util.ArrayList;

/**
 * The player: VLC's engine (libVLC: nearly every format, subtitles, audio
 * tracks) under Éclat's Liquid Glass controls (assets/ui/player.html).
 * VLC-like gestures: swipe up/down on the left for brightness, on the right
 * for volume, sideways to seek, double tap on a side to jump 10 s.
 */
public class PlayerActivity extends Activity {
    static PlayerActivity current;

    private LibVLC libVLC;
    private MediaPlayer player;
    private VLCVideoLayout videoLayout;
    private WebView web;
    private boolean pageReady, hasVideo, inPip, stopped;
    private ParcelFileDescriptor pfd;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private JSONObject item;
    private long resumeAt;
    private int scaleIndex;
    private static final MediaPlayer.ScaleType[] SCALES = {
            MediaPlayer.ScaleType.SURFACE_BEST_FIT, MediaPlayer.ScaleType.SURFACE_FILL,
            MediaPlayer.ScaleType.SURFACE_16_9, MediaPlayer.ScaleType.SURFACE_4_3, MediaPlayer.ScaleType.SURFACE_ORIGINAL,
    };
    private static final String[] SCALE_NAMES = {"Adapter", "Remplir", "16:9", "4:3", "Taille réelle"};

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            w.getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        immersive(true);

        takeViewIntent(getIntent());

        ArrayList<String> opts = new ArrayList<>();
        opts.add("--audio-time-stretch");
        opts.add("--subsdec-encoding=Windows-1252");
        opts.add("--network-caching=1500");
        libVLC = new LibVLC(this, opts);
        player = new MediaPlayer(libVLC);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        videoLayout = new VLCVideoLayout(this);
        root.addView(videoLayout, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        web.addJavascriptInterface(new Bridge(), "EclatPlayer");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return WebFiles.serve(PlayerActivity.this, request.getUrl());
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                // The page's engine stopped (memory): start again rather than close.
                android.util.Log.w("Eclat", "WebView renderer gone, crash=" + detail.didCrash());
                recreate();
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                pushInsets();
                sendItem();
            }
        });
        web.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                pushInsets();
                return insets;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // TextureView: the glass controls draw over the video like any view.
        player.attachViews(videoLayout, null, true, true);
        player.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event e) {
                onPlayerEvent(e);
            }
        });
        web.loadUrl(WebFiles.ORIGIN + "/ui/player.html");
        playCurrent();
    }

    /** Opened from another app ("Open with Éclat"): one item. */
    private void takeViewIntent(Intent in) {
        if (!Intent.ACTION_VIEW.equals(in.getAction()) || in.getData() == null) return;
        UriSource s = new UriSource(this, in.getData(), in.getStringExtra("title"), in.getType(), -1);
        JSONArray one = new JSONArray();
        JSONObject o = new JSONObject();
        try {
            o.put("uri", in.getData().toString());
            o.put("name", s.name());
            o.put("title", stripExt(s.name()));
            o.put("mime", s.mime());
            o.put("size", s.size());
        } catch (JSONException ignored) {
        }
        one.put(o);
        Playlist.set(one, 0);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Already open (music in the background): a new list, or the same one.
        if (Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getBooleanExtra("play", false)) {
            takeViewIntent(intent);
            playCurrent();
        }
    }

    private static String stripExt(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    @SuppressWarnings("deprecation")
    private void immersive(boolean on) {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                if (on) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            w.getDecorView().setSystemUiVisibility(on
                    ? View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    : View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private void pushInsets() {
        if (!pageReady) return;
        float d = getResources().getDisplayMetrics().density;
        int top = 0, left = 0, right = 0, bottom = 0;
        if (Build.VERSION.SDK_INT >= 28) {
            WindowInsets wi = web.getRootWindowInsets();
            if (wi != null && wi.getDisplayCutout() != null) {
                top = wi.getDisplayCutout().getSafeInsetTop();
                left = wi.getDisplayCutout().getSafeInsetLeft();
                right = wi.getDisplayCutout().getSafeInsetRight();
                bottom = wi.getDisplayCutout().getSafeInsetBottom();
            }
        }
        web.evaluateJavascript("player.insets(" + top / d + "," + bottom / d + "," + left / d + "," + right / d + ")", null);
    }

    // ------------------------------------------------------------------
    // Playing

    private SharedPreferences prefs() {
        return getSharedPreferences("positions", MODE_PRIVATE);
    }

    private void savePosition() {
        if (item == null || player == null) return;
        long len = player.getLength(), t = player.getTime();
        SharedPreferences.Editor e = prefs().edit();
        String key = item.optString("uri");
        // Remember where a long video stopped (like VLC), not the end.
        if (len > 3 * 60_000 && t > 10_000 && t < len - 15_000) e.putLong(key, t);
        else e.remove(key);
        e.apply();
    }

    private void playCurrent() {
        savePosition();
        JSONArray items = Playlist.items;
        if (items.length() == 0) {
            finish();
            return;
        }
        item = items.optJSONObject(Playlist.index);
        Uri u = Uri.parse(item.optString("uri"));
        hasVideo = false;
        Media m;
        try {
            ParcelFileDescriptor old = pfd;
            pfd = null;
            if ("file".equals(u.getScheme())) {
                m = new Media(libVLC, u.getPath());
            } else if ("content".equals(u.getScheme())) {
                pfd = getContentResolver().openFileDescriptor(u, "r");
                if (pfd == null) throw new IOException("illisible");
                m = new Media(libVLC, pfd.getFileDescriptor());
            } else {
                m = new Media(libVLC, u);
            }
            m.setHWDecoderEnabled(true, false);
            player.setMedia(m);
            m.release();
            if (old != null) {
                try {
                    old.close();
                } catch (IOException ignored) {
                }
            }
        } catch (Exception e) {
            js("player.error(" + JSONObject.quote(getString(R.string.player_error)) + ")");
            return;
        }
        resumeAt = prefs().getLong(item.optString("uri"), 0);
        requestFocus();
        player.play();
        sendItem();
    }

    private void sendItem() {
        if (!pageReady || item == null) return;
        JSONObject o = new JSONObject();
        try {
            o.put("item", item);
            o.put("index", Playlist.index);
            o.put("count", Playlist.items.length());
            o.put("resumeAt", resumeAt);
        } catch (JSONException ignored) {
        }
        js("player.load(" + o + ")");
    }

    private void js(final String code) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (pageReady && web != null) web.evaluateJavascript(code, null);
            }
        });
    }

    private long lastTimePush;

    private void onPlayerEvent(MediaPlayer.Event e) {
        switch (e.type) {
            case MediaPlayer.Event.Playing:
                if (resumeAt > 0) {
                    player.setTime(resumeAt);
                    js("player.resumed(" + resumeAt + ")");
                    resumeAt = 0;
                }
                pushState();
                break;
            case MediaPlayer.Event.Paused:
            case MediaPlayer.Event.Stopped:
                pushState();
                break;
            case MediaPlayer.Event.LengthChanged:
            case MediaPlayer.Event.ESAdded:
            case MediaPlayer.Event.ESSelected:
                pushState();
                break;
            case MediaPlayer.Event.Vout:
                boolean v = e.getVoutCount() > 0;
                if (v != hasVideo) {
                    hasVideo = v;
                    if (v) orientForVideo();
                    pushState();
                }
                break;
            case MediaPlayer.Event.TimeChanged:
                long now = System.currentTimeMillis();
                if (now - lastTimePush > 250) {
                    lastTimePush = now;
                    js("player.time(" + e.getTimeChanged() + "," + player.getLength() + ")");
                }
                break;
            case MediaPlayer.Event.EndReached:
                prefs().edit().remove(item.optString("uri")).apply();
                if (Playlist.index < Playlist.items.length() - 1) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            Playlist.index++;
                            playCurrent();
                        }
                    });
                } else {
                    pushState();
                    js("player.ended()");
                }
                break;
            case MediaPlayer.Event.EncounteredError:
                js("player.error(" + JSONObject.quote(getString(R.string.player_error)) + ")");
                break;
            default:
                break;
        }
        if (!hasVideo && player.isPlaying()) PlaybackService.show(this, item);
    }

    private void pushState() {
        JSONObject o = new JSONObject();
        try {
            o.put("playing", player.isPlaying());
            o.put("time", player.getTime());
            o.put("length", player.getLength());
            o.put("video", hasVideo);
            o.put("rate", player.getRate());
            o.put("scale", SCALE_NAMES[scaleIndex]);
            o.put("audioTracks", tracks(player.getAudioTracks()));
            o.put("audioTrack", player.getAudioTrack());
            o.put("spuTracks", tracks(player.getSpuTracks()));
            o.put("spuTrack", player.getSpuTrack());
            o.put("pip", Build.VERSION.SDK_INT >= 26 && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE));
        } catch (JSONException ignored) {
        }
        js("player.state(" + o + ")");
        if (!hasVideo) PlaybackService.update(this, item, player.isPlaying());
    }

    private static JSONArray tracks(MediaPlayer.TrackDescription[] t) throws JSONException {
        JSONArray a = new JSONArray();
        if (t == null) return a;
        for (MediaPlayer.TrackDescription d : t) {
            JSONObject o = new JSONObject();
            o.put("id", d.id);
            o.put("name", d.name);
            a.put(o);
        }
        return a;
    }

    private void orientForVideo() {
        IMedia.VideoTrack vt = player.getCurrentVideoTrack();
        if (vt == null) return;
        int w = vt.width, h = vt.height;
        if (vt.orientation >= IMedia.VideoTrack.Orientation.LeftTop) { // turned a quarter
            int t = w;
            w = h;
            h = t;
        }
        final boolean landscape = w > h;
        main.post(new Runnable() {
            @Override
            public void run() {
                setRequestedOrientation(landscape ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
        });
    }

    // ------------------------------------------------------------------
    // Audio focus: another app playing pauses us

    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int change) {
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                if (player.isPlaying()) player.pause();
            }
        }
    };

    @SuppressWarnings("deprecation")
    private void requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                        .setOnAudioFocusChangeListener(focusListener).build();
            }
            audio.requestAudioFocus(focusRequest);
        } else {
            audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Leaving during a video: it keeps playing in a small window.
        if (hasVideo && player.isPlaying()) enterPip();
    }

    private void enterPip() {
        if (Build.VERSION.SDK_INT < 26 || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return;
        try {
            Rational r = new Rational(16, 9);
            IMedia.VideoTrack vt = player.getCurrentVideoTrack();
            if (vt != null && vt.width > 0 && vt.height > 0) {
                float ratio = vt.width / (float) vt.height;
                ratio = Math.max(0.42f, Math.min(2.38f, ratio));
                r = new Rational(Math.round(ratio * 1000), 1000);
            }
            enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(r).build());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean pip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(pip, newConfig);
        inPip = pip;
        js("player.pip(" + pip + ")");
        if (!pip && stopped) {
            // the small window was closed
            player.pause();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopped = true;
        savePosition();
        // Music keeps playing in the background; a video pauses (unless in the small window).
        if (hasVideo && !inPip && player.isPlaying()) player.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive(true);
    }

    @Override
    protected void onDestroy() {
        savePosition();
        if (current == this) current = null;
        PlaybackService.hide(this);
        player.setEventListener(null);
        player.stop();
        player.detachViews();
        player.release();
        libVLC.release();
        if (pfd != null) {
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
        }
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!pageReady) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("window.playerBack ? window.playerBack() : false", value -> {
            if (!"true".equals(value)) finish();
        });
    }

    // Called by the notification of PlaybackService.
    void toggle() {
        if (player.isPlaying()) player.pause();
        else {
            requestFocus();
            player.play();
        }
    }

    void next() {
        if (Playlist.index < Playlist.items.length() - 1) {
            Playlist.index++;
            playCurrent();
        }
    }

    void previous() {
        if (player.getTime() > 4000 || Playlist.index == 0) {
            player.setTime(0);
        } else {
            Playlist.index--;
            playCurrent();
        }
    }

    // ------------------------------------------------------------------

    private class Bridge {
        private void ui(Runnable r) {
            main.post(r);
        }

        @JavascriptInterface
        public void toggle() {
            ui(new Runnable() {
                public void run() {
                    PlayerActivity.this.toggle();
                }
            });
        }

        @JavascriptInterface
        public void seek(final double ms) {
            ui(new Runnable() {
                public void run() {
                    player.setTime((long) ms);
                }
            });
        }

        @JavascriptInterface
        public void seekBy(final double ms) {
            ui(new Runnable() {
                public void run() {
                    long t = Math.max(0, player.getTime() + (long) ms);
                    if (player.getLength() > 0) t = Math.min(t, player.getLength() - 500);
                    player.setTime(t);
                    js("player.time(" + t + "," + player.getLength() + ")");
                }
            });
        }

        @JavascriptInterface
        public void next() {
            ui(new Runnable() {
                public void run() {
                    PlayerActivity.this.next();
                }
            });
        }

        @JavascriptInterface
        public void previous() {
            ui(new Runnable() {
                public void run() {
                    PlayerActivity.this.previous();
                }
            });
        }

        @JavascriptInterface
        public void rate(final double r) {
            ui(new Runnable() {
                public void run() {
                    player.setRate((float) r);
                    pushState();
                }
            });
        }

        @JavascriptInterface
        public void scale() {
            ui(new Runnable() {
                public void run() {
                    scaleIndex = (scaleIndex + 1) % SCALES.length;
                    player.setVideoScale(SCALES[scaleIndex]);
                    pushState();
                    js("player.toast(" + JSONObject.quote(SCALE_NAMES[scaleIndex]) + ")");
                }
            });
        }

        @JavascriptInterface
        public void audioTrack(final int id) {
            ui(new Runnable() {
                public void run() {
                    player.setAudioTrack(id);
                    pushState();
                }
            });
        }

        @JavascriptInterface
        public void spuTrack(final int id) {
            ui(new Runnable() {
                public void run() {
                    player.setSpuTrack(id);
                    pushState();
                }
            });
        }

        /** Brightness of this window, 0..1 (-1: the phone's setting). */
        @JavascriptInterface
        public void brightness(final double b) {
            ui(new Runnable() {
                public void run() {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = (float) b;
                    getWindow().setAttributes(lp);
                }
            });
        }

        @JavascriptInterface
        public double getBrightness() {
            float b = getWindow().getAttributes().screenBrightness;
            return b < 0 ? 0.5 : b;
        }

        /** Volume 0..1 of the media stream. */
        @JavascriptInterface
        public void volume(double v) {
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (int) Math.round(Math.max(0, Math.min(1, v)) * max), 0);
        }

        @JavascriptInterface
        public double getVolume() {
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            return max == 0 ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (double) max;
        }

        @JavascriptInterface
        public void rotate() {
            ui(new Runnable() {
                public void run() {
                    boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
                    setRequestedOrientation(land ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                }
            });
        }

        @JavascriptInterface
        public void pip() {
            ui(new Runnable() {
                public void run() {
                    enterPip();
                }
            });
        }

        @JavascriptInterface
        public void bars(final boolean show) {
            ui(new Runnable() {
                public void run() {
                    immersive(!show);
                }
            });
        }

        /** Sends the file playing to another phone (Éclat's NFC screen). */
        @JavascriptInterface
        public void nfc() {
            ui(new Runnable() {
                public void run() {
                    if (item == null) return;
                    if (player.isPlaying()) player.pause();
                    Intent i = new Intent(PlayerActivity.this, MainActivity.class).setAction(Intent.ACTION_SEND)
                            .setType(item.optString("mime", "*/*"))
                            .putExtra(Intent.EXTRA_STREAM, Uri.parse(item.optString("uri")))
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(i);
                }
            });
        }

        @JavascriptInterface
        public void close() {
            ui(new Runnable() {
                public void run() {
                    finish();
                }
            });
        }
    }
}
