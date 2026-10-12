package com.eclipse.eclat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.eclipse.eclat.share.Inbox;
import com.eclipse.eclat.share.Net;
import com.eclipse.eclat.share.NfcTagService;
import com.eclipse.eclat.share.ReceiveActivity;
import com.eclipse.eclat.share.SendService;
import com.eclipse.eclat.share.UriSource;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Éclat: photos, videos, music and files, in the Liquid Glass look of
 * Eclipse (HTML/CSS in assets/ui). Videos and music play with VLC's engine
 * ({@link PlayerActivity}); any file can be sent to another phone by NFC.
 */
public class MainActivity extends Activity implements SendService.Watcher {
    public static final String ACTION_SHOW = "com.eclipse.eclat.SHOW";
    private static final int REQ_MEDIA = 1, REQ_HOTSPOT = 2, REQ_FILES = 3;

    private WebView web;
    private boolean pageReady, sendScreen, resumed;
    private final List<String> queued = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newFixedThreadPool(2);
    private final Map<Integer, String> waitingPermission = new HashMap<>();
    private JSONObject pendingSend;
    private int insetTop, insetBottom, insetLeft, insetRight;

    // ------------------------------------------------------------------
    // Lifecycle

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // An NFC tag read while Éclat was the app on screen: it is a file for us.
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(getIntent().getAction())) {
            forwardNfc(getIntent());
        }
        Notifs.init(this);
        setupWindow();
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#EEF0F7"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "EclatNative");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // the page never navigates away
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return WebFiles.serve(MainActivity.this, request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                pushInsets();
                for (String js : queued) web.evaluateJavascript(js, null);
                queued.clear();
                handleIntent(getIntent());
            }
        });
        web.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                insetTop = insets.getSystemWindowInsetTop();
                insetBottom = insets.getSystemWindowInsetBottom();
                insetLeft = insets.getSystemWindowInsetLeft();
                insetRight = insets.getSystemWindowInsetRight();
                pushInsets();
                return insets;
            }
        });
        setContentView(web);
        web.loadUrl(WebFiles.ORIGIN + "/ui/index.html");
    }

    @SuppressWarnings("deprecation")
    private void setupWindow() {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light, light);
            }
        }
    }

    private void pushInsets() {
        if (!pageReady) return;
        float d = getResources().getDisplayMetrics().density;
        web.evaluateJavascript("eclat.insets(" + insetTop / d + "," + insetBottom / d + "," + insetLeft / d + "," + insetRight / d + ")", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        SendService.setWatcher(this);
        emit("resume", status().toString());
        applyNfcMode();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        SendService.setWatcher(null);
        releaseNfcMode();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction())) {
            forwardNfc(intent);
            return;
        }
        if (pageReady) handleIntent(intent);
    }

    private void forwardNfc(Intent intent) {
        if (Inbox.fromIntent(intent) == null) return;
        Intent i = new Intent(intent).setClass(this, ReceiveActivity.class);
        startActivity(i);
    }

    @SuppressWarnings("deprecation")
    private void handleIntent(Intent intent) {
        if (intent == null || intent.getBooleanExtra("eclat_done", false)) return;
        String action = intent.getAction();
        try {
            if (Intent.ACTION_SEND.equals(action)) {
                // Shared from another app: ready to send it by NFC.
                Uri u = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                        : (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (u != null) {
                    UriSource src = new UriSource(this, u, null, intent.getType(), -1);
                    JSONObject o = new JSONObject();
                    o.put("uri", u.toString());
                    o.put("name", src.name());
                    o.put("mime", src.mime());
                    o.put("size", src.size());
                    o.put("kind", Library.kindOf(src.mime()));
                    emit("sendFromOutside", o.toString());
                }
            } else if ((ACTION_SHOW.equals(action) || Intent.ACTION_VIEW.equals(action)) && intent.getData() != null) {
                JSONObject o = new JSONObject();
                o.put("uri", intent.getData().toString());
                o.put("mime", intent.getType() == null ? "image/*" : intent.getType());
                String n = intent.getStringExtra("name");
                o.put("name", n == null ? new UriSource(this, intent.getData(), null, intent.getType(), -1).name() : n);
                o.put("kind", "image");
                emit("show", o.toString());
            }
        } catch (JSONException ignored) {
        }
        intent.putExtra("eclat_done", true);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!pageReady) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("window.eclatBack ? window.eclatBack() : false", value -> {
            if (!"true".equals(value)) finish();
        });
    }

    @Override
    protected void onDestroy() {
        work.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Talking to the page

    private void emit(String event, String json) {
        final String js = "eclat.on(" + JSONObject.quote(event) + "," + json + ")";
        main.post(new Runnable() {
            @Override
            public void run() {
                if (pageReady) web.evaluateJavascript(js, null);
                else queued.add(js);
            }
        });
    }

    private void resolve(String id, Object result) {
        final String js = "eclat.resolve(" + JSONObject.quote(id) + "," + (result == null ? "null" : result.toString()) + ")";
        main.post(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(js, null);
            }
        });
    }

    @Override
    public void onSendState(JSONObject state) {
        emit("send", state.toString());
    }

    // ------------------------------------------------------------------
    // Permissions and state

    private boolean granted(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private String[] mediaPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS};
        }
        if (Build.VERSION.SDK_INT >= 29) return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};
    }

    private boolean hasMedia() {
        if (Build.VERSION.SDK_INT >= 33) {
            return granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)
                    || (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED));
        }
        return granted(Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private boolean hasAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return granted(Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            NfcAdapter nfc = NfcAdapter.getDefaultAdapter(this);
            boolean hce = getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION);
            o.put("media", hasMedia());
            o.put("partial", Build.VERSION.SDK_INT >= 34 && !granted(Manifest.permission.READ_MEDIA_IMAGES)
                    && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED));
            o.put("music", Build.VERSION.SDK_INT >= 33 ? granted(Manifest.permission.READ_MEDIA_AUDIO) : hasMedia());
            o.put("allFiles", hasAllFiles());
            o.put("nfc", nfc == null ? "none" : nfc.isEnabled() ? "on" : "off");
            o.put("hce", hce);
            o.put("wifi", Net.onWifi(this));
            o.put("device", Net.deviceName(this));
            o.put("version", BuildConfig.VERSION_NAME);
            o.put("sdk", Build.VERSION.SDK_INT);
        } catch (JSONException ignored) {
        }
        return o;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        String id = waitingPermission.remove(requestCode);
        if (requestCode == REQ_HOTSPOT && pendingSend != null) {
            startSend(pendingSend);
            pendingSend = null;
        }
        if (id != null) resolve(id, status());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        String id = waitingPermission.remove(requestCode);
        if (id != null) resolve(id, status());
    }

    // ------------------------------------------------------------------
    // NFC while the "ready to send" screen is shown

    private void applyNfcMode() {
        if (!sendScreen || !resumed) return;
        NfcAdapter nfc = NfcAdapter.getDefaultAdapter(this);
        if (nfc == null) return;
        try {
            // Our tag answers first, even if another app listens for the same thing.
            CardEmulation.getInstance(nfc).setPreferredService(this, new ComponentName(this, NfcTagService.class));
        } catch (Exception ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 35) {
                // Only be a tag: don't also read the other phone.
                nfc.setDiscoveryTechnology(this, NfcAdapter.FLAG_READER_DISABLE, NfcAdapter.FLAG_LISTEN_KEEP);
            } else {
                // Tags this phone reads meanwhile go nowhere (no "unsupported tag" message).
                PendingIntent pi = PendingIntent.getActivity(this, 9, new Intent(this, getClass())
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_MUTABLE);
                nfc.enableForegroundDispatch(this, pi, null, null);
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseNfcMode() {
        NfcAdapter nfc = NfcAdapter.getDefaultAdapter(this);
        if (nfc == null) return;
        try {
            CardEmulation.getInstance(nfc).unsetPreferredService(this);
        } catch (Exception ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 35) nfc.resetDiscoveryTechnology(this);
            else nfc.disableForegroundDispatch(this);
        } catch (Exception ignored) {
        }
    }

    private void startSend(JSONObject a) {
        SendService.start(this, Uri.parse(a.optString("uri")), a.optString("name", null),
                a.optString("mime", null), a.optLong("size", -1));
    }

    // ------------------------------------------------------------------

    private class Bridge {
        /** The page asks something; the answer comes back with eclat.resolve(id, result). */
        @JavascriptInterface
        public void call(final String id, final String method, final String args) {
            work.execute(new Runnable() {
                @Override
                public void run() {
                    Object r;
                    try {
                        JSONObject a = args == null || args.isEmpty() ? new JSONObject() : new JSONObject(args);
                        r = MainActivity.this.run(id, method, a);
                    } catch (Exception e) {
                        JSONObject err = new JSONObject();
                        try {
                            err.put("error", String.valueOf(e.getMessage()));
                        } catch (JSONException ignored) {
                        }
                        r = err;
                    }
                    if (r != WAIT) resolve(id, r);
                }
            });
        }
    }

    private static final Object WAIT = new Object();

    private Object run(final String id, String method, final JSONObject a) throws Exception {
        switch (method) {
            case "status":
                return status();
            case "askMedia":
                if (hasMedia() && !a.optBoolean("again")) return status();
                waitingPermission.put(REQ_MEDIA, id);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        String[] p = mediaPermissions();
                        if (Build.VERSION.SDK_INT >= 34) {
                            String[] q = new String[p.length + 1];
                            System.arraycopy(p, 0, q, 0, p.length);
                            q[p.length] = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED;
                            p = q;
                        }
                        requestPermissions(p, REQ_MEDIA);
                    }
                });
                return WAIT;
            case "askAllFiles":
                waitingPermission.put(REQ_FILES, id);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (Build.VERSION.SDK_INT >= 30) {
                            try {
                                startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:" + getPackageName())), REQ_FILES);
                            } catch (Exception e) {
                                startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_FILES);
                            }
                        } else {
                            requestPermissions(mediaPermissions(), REQ_FILES);
                        }
                    }
                });
                return WAIT;
            case "media":
                return Library.media(this, a.optString("kind", "all"), a.optString("bucket", null),
                        a.optInt("offset", 0), a.optInt("limit", 240));
            case "albums":
                return Library.albums(this);
            case "music":
                return Library.music(this);
            case "list":
                return Library.list(a.optString("path", null), a.optBoolean("hidden"));
            case "places":
                return Library.places();
            case "play": {
                JSONArray items = a.getJSONArray("items");
                Playlist.set(items, a.optInt("index", 0));
                startActivity(new Intent(this, PlayerActivity.class).putExtra("play", true));
                return true;
            }
            case "open": {
                Uri u = Uri.parse(a.getString("uri"));
                startActivity(FileAccess.openIntent(this, u, a.optString("mime", null), a.optString("name", null))
                        .putExtra("from_eclat", true));
                return true;
            }
            case "openWith": {
                Uri u = FileAccess.shareable(this, Uri.parse(a.getString("uri")));
                Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(u, a.optString("mime", "*/*"))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(view, getString(R.string.open_with)));
                return true;
            }
            case "shareAndroid": {
                Uri u = FileAccess.shareable(this, Uri.parse(a.getString("uri")));
                Intent send = new Intent(Intent.ACTION_SEND).setType(a.optString("mime", "*/*"))
                        .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(send, getString(R.string.share));
                // Éclat itself is not offered in its own share sheet.
                if (Build.VERSION.SDK_INT >= 24) {
                    chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[]{new ComponentName(this, MainActivity.class)});
                }
                startActivity(chooser);
                return true;
            }
            case "details":
                return details(Uri.parse(a.getString("uri")));
            case "sendScreen":
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        sendScreen = a.optBoolean("open");
                        if (sendScreen) applyNfcMode();
                        else releaseNfcMode();
                    }
                });
                return true;
            case "nfcSend": {
                boolean needHotspotPermission = !Net.onWifi(this) && (Build.VERSION.SDK_INT >= 33
                        ? !granted(Manifest.permission.NEARBY_WIFI_DEVICES)
                        : !granted(Manifest.permission.ACCESS_FINE_LOCATION));
                if (needHotspotPermission) {
                    pendingSend = a;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            requestPermissions(new String[]{Build.VERSION.SDK_INT >= 33
                                    ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION}, REQ_HOTSPOT);
                        }
                    });
                } else {
                    startSend(a);
                }
                return true;
            }
            case "nfcCancel":
                SendService.stop(this);
                return true;
            case "nfcSettings":
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
                        } catch (Exception e) {
                            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                        }
                    }
                });
                return true;
            case "appSettings":
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                return true;
            default:
                throw new IllegalArgumentException("inconnu : " + method);
        }
    }

    private JSONObject details(Uri u) throws JSONException {
        JSONObject o = new JSONObject();
        if ("file".equals(u.getScheme())) {
            java.io.File f = new java.io.File(u.getPath());
            o.put("path", f.getAbsolutePath());
            o.put("size", f.length());
            o.put("date", f.lastModified());
            return o;
        }
        String[] proj = {MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, "_data",
                Build.VERSION.SDK_INT >= 29 ? MediaStore.MediaColumns.RELATIVE_PATH : "_data"};
        try (Cursor c = getContentResolver().query(u, proj, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                o.put("size", c.getLong(0));
                o.put("date", c.getLong(1) * 1000);
                if (c.getInt(2) > 0) o.put("res", c.getInt(2) + " × " + c.getInt(3));
                String path = c.getString(4);
                if (path == null) path = c.getString(5);
                if (path != null) o.put("path", path);
            }
        } catch (Exception ignored) {
        }
        return o;
    }
}
