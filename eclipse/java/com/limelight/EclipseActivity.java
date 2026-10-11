package com.limelight;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.StreamSettings;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.UiHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Eclipse home screen: the same Liquid Glass interface as Eclipse for PC
 * (HTML/CSS in assets/eclipse), driving Moonlight's engine underneath for
 * discovery, pairing and streaming.
 */
public class EclipseActivity extends Activity {
    private WebView web;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean polling, inForeground, pageReady;
    private float density = 1f;
    private int insetTop, insetBottom, insetLeft, insetRight;

    private final Map<String, ComputerDetails> computers = new ConcurrentHashMap<>();
    private final Map<String, List<NvApp>> apps = new ConcurrentHashMap<>();
    private final Map<String, String> boxArt = new ConcurrentHashMap<>();
    private final ExecutorService artLoader = Executors.newFixedThreadPool(2);

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder) binder);
            new Thread() {
                @Override
                public void run() {
                    localBinder.waitForReady();
                    managerBinder = localBinder;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            startUpdates();
                        }
                    });
                    // Generating the key pair early avoids delays when pairing.
                    new AndroidCryptoProvider(EclipseActivity.this).getClientCertificate();
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        inForeground = true;

        // Like Moonlight's home screen: remember the GPU name once, the video
        // decoder settings depend on it.
        final GlPreferences glPrefs = GlPreferences.readPreferences(this);
        if (!glPrefs.savedFingerprint.equals(Build.FINGERPRINT) || glPrefs.glRenderer.isEmpty()) {
            GLSurfaceView surfaceView = new GLSurfaceView(this);
            surfaceView.setRenderer(new GLSurfaceView.Renderer() {
                @Override
                public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                    glPrefs.savedFingerprint = Build.FINGERPRINT;
                    glPrefs.writePreferences();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            completeOnCreate();
                        }
                    });
                }

                @Override
                public void onSurfaceChanged(GL10 gl10, int i, int i1) {
                }

                @Override
                public void onDrawFrame(GL10 gl10) {
                }
            });
            setContentView(surfaceView);
        } else {
            completeOnCreate();
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void completeOnCreate() {
        UiHelper.setLocale(this);
        density = getResources().getDisplayMetrics().density;
        setupWindow();

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#EEF0F7"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.addJavascriptInterface(new Bridge(), "EclipseNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                }
                return true; // the page itself never navigates away
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                pushInsets();
                pushComputers();
            }
        });
        web.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
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
        web.loadUrl("file:///android_asset/eclipse/index.html");

        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);
    }

    /** Draw behind the status and navigation bars, with dark icons. */
    @SuppressWarnings("deprecation")
    private void setupWindow() {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light, light);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            setupWindow();
        }
        UiHelper.showDecoderCrashDialog(this);
        inForeground = true;
        startUpdates();
        emit("resume", "{}");
    }

    @Override
    protected void onPause() {
        super.onPause();
        inForeground = false;
        stopUpdates(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        artLoader.shutdownNow();
        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web == null || !pageReady) {
            super.onBackPressed();
            return;
        }
        // The page closes a sheet or goes back to the PC list if it can.
        web.evaluateJavascript("window.eclipseBack ? window.eclipseBack() : false", value -> {
            if (!"true".equals(value)) {
                finish();
            }
        });
    }

    // ------------------------------------------------------------------
    // Computers
    // ------------------------------------------------------------------

    private void startUpdates() {
        if (managerBinder == null || polling || !inForeground) {
            return;
        }
        polling = true;
        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details) {
                if (details.uuid == null) {
                    return;
                }
                computers.put(details.uuid, details);
                pushComputers();
            }
        });
    }

    private void stopUpdates(boolean wait) {
        if (managerBinder == null || !polling) {
            return;
        }
        managerBinder.stopPolling();
        if (wait) {
            managerBinder.waitForPollingStopped();
        }
        polling = false;
    }

    private static String addressOf(ComputerDetails d) {
        ComputerDetails.AddressTuple a = d.activeAddress;
        if (a == null) a = d.manualAddress;
        if (a == null) a = d.localAddress;
        if (a == null) a = d.remoteAddress;
        return a == null ? "" : a.address;
    }

    private JSONObject computerJson(ComputerDetails d) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("uuid", d.uuid);
        o.put("name", d.name == null ? "PC" : d.name);
        o.put("state", d.state == null ? "UNKNOWN" : d.state.name());
        o.put("paired", d.pairState == PairState.PAIRED);
        o.put("address", addressOf(d));
        o.put("running", d.runningGameId);
        o.put("canWake", d.macAddress != null);
        return o;
    }

    private void pushComputers() {
        try {
            JSONArray arr = new JSONArray();
            for (ComputerDetails d : computers.values()) {
                arr.put(computerJson(d));
            }
            emit("computers", arr.toString());
        } catch (JSONException e) {
            LimeLog.warning("Eclipse: " + e);
        }
    }

    private NvHTTP http(ComputerDetails c) throws java.io.IOException {
        return new NvHTTP(ServerHelper.getCurrentAddressFromComputer(c), c.httpsPort,
                managerBinder.getUniqueId(), c.serverCert, PlatformBinding.getCryptoProvider(this));
    }

    private void loadApps(final String uuid) {
        new Thread() {
            @Override
            public void run() {
                JSONObject ev = new JSONObject();
                try {
                    ev.put("uuid", uuid);
                    ComputerDetails c = computers.get(uuid);
                    if (c == null || managerBinder == null) {
                        ev.put("error", "PC introuvable");
                    } else if (c.state != ComputerDetails.State.ONLINE || c.activeAddress == null) {
                        ev.put("error", "offline");
                    } else {
                        List<NvApp> list = http(c).getAppList();
                        apps.put(uuid, list);
                        JSONArray arr = new JSONArray();
                        for (NvApp a : list) {
                            JSONObject j = new JSONObject();
                            j.put("id", a.getAppId());
                            j.put("name", a.getAppName());
                            String art = boxArt.get(uuid + "/" + a.getAppId());
                            if (art != null) j.put("art", art);
                            arr.put(j);
                        }
                        ev.put("apps", arr);
                        ev.put("running", c.runningGameId);
                        for (NvApp a : list) {
                            if (!boxArt.containsKey(uuid + "/" + a.getAppId())) {
                                loadArt(c, a);
                            }
                        }
                    }
                } catch (Exception e) {
                    try {
                        ev.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
                    } catch (JSONException ignored) {
                    }
                }
                emit("apps", ev.toString());
            }
        }.start();
    }

    private void loadArt(final ComputerDetails c, final NvApp app) {
        artLoader.submit(new Runnable() {
            @Override
            public void run() {
                try (InputStream in = http(c).getBoxArt(app)) {
                    if (in == null) return;
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    String url = "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                    boxArt.put(c.uuid + "/" + app.getAppId(), url);
                    JSONObject ev = new JSONObject();
                    ev.put("uuid", c.uuid);
                    ev.put("id", app.getAppId());
                    ev.put("art", url);
                    emit("art", ev.toString());
                } catch (Exception e) {
                    LimeLog.info("Eclipse: no box art for " + app.getAppName() + ": " + e);
                }
            }
        });
    }

    private NvApp findApp(String uuid, int appId) {
        List<NvApp> list = apps.get(uuid);
        if (list != null) {
            for (NvApp a : list) {
                if (a.getAppId() == appId) return a;
            }
        }
        return new NvApp("app", appId, false);
    }

    // ------------------------------------------------------------------
    // Talking to the page
    // ------------------------------------------------------------------

    private void emit(final String event, final String json) {
        if (web == null) return;
        final String js = "window.ec && window.ec.on(" + JSONObject.quote(event) + "," + json + ")";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null && pageReady) {
                    web.evaluateJavascript(js, null);
                }
            }
        });
    }

    private void pushInsets() {
        if (web == null) return;
        final String js = String.format(java.util.Locale.ROOT,
                "window.ec && window.ec.insets(%f,%f,%f,%f)",
                insetTop / density, insetRight / density, insetBottom / density, insetLeft / density);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null && pageReady) web.evaluateJavascript(js, null);
            }
        });
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(EclipseActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Methods the page can call (window.EclipseNative.xxx). */
    private class Bridge {
        @JavascriptInterface
        public String version() {
            return BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public void refresh() {
            pushComputers();
        }

        @JavascriptInterface
        public void apps(String uuid) {
            loadApps(uuid);
        }

        @JavascriptInterface
        public void addComputer(final String raw) {
            new Thread() {
                @Override
                public void run() {
                    JSONObject ev = new JSONObject();
                    try {
                        String input = raw.trim();
                        URI uri = new URI(input.contains("://") ? input : "moonlight://" + input);
                        String host = uri.getHost();
                        if (host == null || host.isEmpty() || managerBinder == null) {
                            ev.put("ok", false);
                            ev.put("error", "Adresse invalide");
                        } else {
                            int port = uri.getPort() == -1 ? NvHTTP.DEFAULT_HTTP_PORT : uri.getPort();
                            ComputerDetails d = new ComputerDetails();
                            d.manualAddress = new ComputerDetails.AddressTuple(host, port);
                            boolean ok = managerBinder.addComputerBlocking(d);
                            ev.put("ok", ok);
                            if (!ok) ev.put("error", "Aucun PC ne répond à cette adresse. Vérifie qu'Eclipse (ou Sunshine) tourne dessus.");
                        }
                    } catch (Exception e) {
                        try {
                            ev.put("ok", false);
                            ev.put("error", "Adresse invalide");
                        } catch (JSONException ignored) {
                        }
                    }
                    emit("added", ev.toString());
                }
            }.start();
        }

        /** Starts pairing and returns the PIN to type on the PC. */
        @JavascriptInterface
        public String pair(final String uuid) {
            final String pin = PairingManager.generatePinString();
            new Thread() {
                @Override
                public void run() {
                    JSONObject ev = new JSONObject();
                    try {
                        ev.put("uuid", uuid);
                        ComputerDetails c = computers.get(uuid);
                        if (c == null || managerBinder == null || c.activeAddress == null) {
                            ev.put("ok", false);
                            ev.put("error", "Ce PC n'est pas joignable. Vérifie qu'il est allumé.");
                        } else {
                            stopUpdates(true);
                            NvHTTP h = http(c);
                            if (h.getPairState() == PairState.PAIRED) {
                                ev.put("ok", true);
                            } else {
                                PairingManager pm = h.getPairingManager();
                                PairState st = pm.pair(h.getServerInfo(true), pin);
                                if (st == PairState.PAIRED) {
                                    ComputerDetails live = managerBinder.getComputer(c.uuid);
                                    if (live != null) live.serverCert = pm.getPairedCert();
                                    managerBinder.invalidateStateForComputer(c.uuid);
                                    ev.put("ok", true);
                                } else {
                                    ev.put("ok", false);
                                    ev.put("error", st == PairState.PIN_WRONG ? "Code incorrect."
                                            : st == PairState.ALREADY_IN_PROGRESS ? "Une connexion est déjà en cours sur ce PC."
                                            : c.runningGameId != 0 ? "Quitte le jeu en cours sur le PC, puis réessaie."
                                            : "La connexion a échoué.");
                                }
                            }
                        }
                    } catch (Exception e) {
                        try {
                            ev.put("ok", false);
                            ev.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
                        } catch (JSONException ignored) {
                        }
                    }
                    emit("paired", ev.toString());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            startUpdates();
                        }
                    });
                }
            }.start();
            return pin;
        }

        @JavascriptInterface
        public void play(final String uuid, final int appId) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ComputerDetails c = computers.get(uuid);
                    if (c == null || managerBinder == null) {
                        Toast.makeText(EclipseActivity.this, "PC introuvable", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ServerHelper.doStart(EclipseActivity.this, findApp(uuid, appId), c, managerBinder);
                }
            });
        }

        @JavascriptInterface
        public void quit(final String uuid) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ComputerDetails c = computers.get(uuid);
                    if (c == null || managerBinder == null) return;
                    ServerHelper.doQuit(EclipseActivity.this, c, findApp(uuid, c.runningGameId), managerBinder,
                            new Runnable() {
                                @Override
                                public void run() {
                                    managerBinder.invalidateStateForComputer(uuid);
                                    loadApps(uuid);
                                }
                            });
                }
            });
        }

        @JavascriptInterface
        public void wake(final String uuid) {
            new Thread() {
                @Override
                public void run() {
                    ComputerDetails c = computers.get(uuid);
                    if (c == null || c.macAddress == null) {
                        toast("Impossible d'allumer ce PC à distance.");
                        return;
                    }
                    try {
                        WakeOnLanSender.sendWolPacket(c);
                        toast("Réveil envoyé à " + c.name + ". Il peut mettre une minute à démarrer.");
                    } catch (Exception e) {
                        toast("Le réveil a échoué.");
                    }
                }
            }.start();
        }

        @JavascriptInterface
        public void remove(final String uuid) {
            ComputerDetails c = computers.remove(uuid);
            if (c != null && managerBinder != null) {
                managerBinder.removeComputer(c);
            }
            pushComputers();
        }

        @JavascriptInterface
        public void openSettings() {
            startActivity(new Intent(EclipseActivity.this, StreamSettings.class));
        }

        @JavascriptInterface
        public void openClassic() {
            startActivity(new Intent(EclipseActivity.this, PcView.class));
        }

        @JavascriptInterface
        public void openUrl(String url) {
            if (url.startsWith("https://")) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            }
        }

        @JavascriptInterface
        public void toast(String msg) {
            EclipseActivity.this.toast(msg);
        }
    }
}
