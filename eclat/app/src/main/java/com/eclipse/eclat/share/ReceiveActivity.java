package com.eclipse.eclat.share;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.eclipse.eclat.FileAccess;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The "Dynamic Island" that drops down when another phone wants to give a
 * file, like AirDrop: who sends what, with Accept / Decline. Started by
 * Android itself when the phone touches a sending phone, even when Éclat
 * is closed. Only the top of the screen is used: the app below stays usable.
 */
public class ReceiveActivity extends Activity implements Inbox.Watcher {
    private WebView web;
    private boolean pageReady;
    private String pendingJs;
    private Inbox.Offer offer;
    private volatile String host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static String lastToken;
    private static long lastTokenTime;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.gravity = Gravity.TOP;
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = (int) (300 * getResources().getDisplayMetrics().density);
        lp.dimAmount = 0f;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        w.setAttributes(lp);
        // touches outside the island go to the app below
        w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "EclatIsland");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                int top = 0;
                int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) top = getResources().getDimensionPixelSize(id);
                js("island.setTop(" + (top / getResources().getDisplayMetrics().density) + ")");
                if (pendingJs != null) {
                    web.evaluateJavascript(pendingJs, null);
                    pendingJs = null;
                }
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/ui/island.html");
        take(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        take(intent);
    }

    private void take(Intent intent) {
        Inbox.Offer o = Inbox.fromIntent(intent);
        if (o == null) {
            if (offer == null) finish();
            return;
        }
        // While the phones touch, the tag is read again and again.
        long now = System.currentTimeMillis();
        if (o.link.token.equals(lastToken) && now - lastTokenTime < 120_000 && offer != null) return;
        String phase = Inbox.state().optString("phase");
        if (o.link.token.equals(lastToken) && now - lastTokenTime < 120_000
                && (phase.equals("receiving") || phase.equals("connecting") || phase.equals("joining"))) {
            offer = o;
            Inbox.setWatcher(this);
            return;
        }
        lastToken = o.link.token;
        lastTokenTime = now;
        offer = o;
        host = null;
        Inbox.reset(o);
        showOffer(o, null, false, true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                probe(offer);
            }
        }, "eclat-probe").start();
    }

    /** Says hello to the sender (it shows "asking...") and gets a preview. */
    private void probe(final Inbox.Offer o) {
        String h0 = Fetch.findHost(Fetch.DEFAULT, o.link, Net.deviceName(this), 3000);
        // a slow network: once more, with more patience
        if (h0 == null) h0 = Fetch.findHost(Fetch.DEFAULT, o.link, Net.deviceName(this), 8000);
        final String h = h0;
        String thumb = null;
        if (h != null && (o.link.mime.startsWith("image/") || o.link.mime.startsWith("video/") || o.link.mime.startsWith("audio/"))) {
            byte[] t = Fetch.get(Fetch.DEFAULT, o.link.base(h) + "/thumb", 3000, 2 << 20);
            if (t != null) thumb = "data:image/jpeg;base64," + Base64.encodeToString(t, Base64.NO_WRAP);
        }
        final String th = thumb;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (offer != o) return;
                host = h;
                showOffer(o, th, h != null || o.ssid != null, false);
            }
        });
    }

    private void showOffer(Inbox.Offer o, String thumb, boolean reachable, boolean checking) {
        JSONObject j = new JSONObject();
        try {
            j.put("name", o.link.name);
            j.put("size", o.link.size);
            j.put("mime", o.link.mime);
            j.put("device", o.link.device);
            j.put("thumb", thumb == null ? "" : thumb);
            j.put("reachable", reachable);
            j.put("checking", checking);
            j.put("hotspot", o.ssid != null);
        } catch (JSONException ignored) {
        }
        js("island.offer(" + j + ")");
    }

    private void js(String code) {
        if (!pageReady) {
            pendingJs = pendingJs == null ? code : pendingJs + ";" + code;
            return;
        }
        web.evaluateJavascript(code, null);
    }

    @Override
    public void onReceiveState(final JSONObject state) {
        main.post(new Runnable() {
            @Override
            public void run() {
                js("island.state(" + state + ")");
            }
        });
    }

    @Override
    protected void onDestroy() {
        Inbox.setWatcher(null);
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    private class Bridge {
        @JavascriptInterface
        public void accept() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (offer == null) return;
                    Inbox.setWatcher(ReceiveActivity.this);
                    ReceiveService.accept(ReceiveActivity.this, host);
                }
            });
        }

        @JavascriptInterface
        public void decline() {
            final Inbox.Offer o = offer;
            final String h = host;
            if (o != null) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String me = Net.deviceName(ReceiveActivity.this);
                        if (h != null) {
                            Fetch.post(Fetch.DEFAULT, o.link.base(h) + "/decline", me, 2000);
                        } else {
                            for (String x : o.link.hosts()) {
                                if (Fetch.post(Fetch.DEFAULT, o.link.base(x) + "/decline", me, 1200)) break;
                            }
                        }
                    }
                }).start();
            }
            close();
        }

        @JavascriptInterface
        public void cancel() {
            startService(new Intent(ReceiveActivity.this, ReceiveService.class).setAction(ReceiveService.ACTION_CANCEL));
        }

        @JavascriptInterface
        public void open() {
            final JSONObject s = Inbox.state();
            main.post(new Runnable() {
                @Override
                public void run() {
                    String u = s.optString("uri", "");
                    if (!u.isEmpty()) {
                        try {
                            startActivity(FileAccess.openIntent(ReceiveActivity.this, Uri.parse(u), s.optString("mime"), s.optString("name")));
                        } catch (Exception ignored) {
                        }
                    }
                    finish();
                }
            });
        }

        @JavascriptInterface
        public void close() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
        }
    }
}
