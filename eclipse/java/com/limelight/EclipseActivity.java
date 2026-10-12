package com.limelight;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.Manifest;
import android.content.ClipData;
import android.provider.Settings;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
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
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.moduleinstall.ModuleInstall;
import com.google.android.gms.common.moduleinstall.ModuleInstallClient;
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;
import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.HostHttpResponseException;
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
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Eclipse home screen: the same Liquid Glass interface as Eclipse for PC
 * (HTML/CSS in assets/eclipse), driving Moonlight's engine underneath for
 * discovery, pairing and streaming.
 */
public class EclipseActivity extends Activity {
    private WebView web;
    private volatile ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean polling, inForeground, pageReady;
    private float density = 1f;
    private int insetTop, insetBottom, insetLeft, insetRight;

    private final Map<String, ComputerDetails> computers = new ConcurrentHashMap<>();
    private final Map<String, List<NvApp>> apps = new ConcurrentHashMap<>();
    private final Map<String, String> boxArt = new ConcurrentHashMap<>();
    private final ExecutorService artLoader = Executors.newFixedThreadPool(2);

    // Pairing (QR code or 4-digit code): one at a time; a newer one, or
    // "Annuler", makes the older one stop quietly.
    private final ExecutorService pairExec = Executors.newSingleThreadExecutor();
    private final AtomicInteger pairJob = new AtomicInteger();
    private volatile NvHTTP pairHttp;
    private String pendingQr; // QR code opened before Eclipse was ready (eclipse://pair link)
    private int scanTries;
    private boolean scanWanted; // the user asked for the scanner and did not cancel

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
                            runPendingQr();
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
        EclipsePairing.init(this);
        EclipseTunnel.init(this);
        if (savedInstanceState == null) {
            openFilesTab = "files".equals(getIntent().getStringExtra("tab"));
            takeQrLink(getIntent());
            checkUpdate(false);
        }

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
        files = new EclipseFiles(this, this::emit);
        EclipseShare.apply(this);
        UiHelper.setLocale(this);
        density = getResources().getDisplayMetrics().density;
        setupWindow();

        // Emulator tests only: a file only root can create lets the test
        // drive the page (Chrome DevTools protocol, over adb).
        if (new java.io.File(getFilesDir(), "test-webview-debug").exists()) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#EEF0F7"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.addJavascriptInterface(new Bridge(), "EclipseNative");
        web.setWebChromeClient(new WebChromeClient()); // confirm() dialogs
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
                runPendingQr();
                tunnelLast = "";
                emitShare();
                if (openFilesTab) {
                    openFilesTab = false;
                    emit("openTab", "{\"tab\":\"files\"}");
                }
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
        prepareScanner();
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
            web.onResume();
        }
        UiHelper.showDecoderCrashDialog(this);
        inForeground = true;
        startUpdates();
        emit("resume", "{}");
        emitTailscale();
        if (shareWanted && EclipseShare.hasAccess(this)) {
            shareWanted = false;
            EclipseShare.set(this, true);
        }
        emitShare();
        tunnelLast = "";
        tunnelWatch();
    }

    @Override
    protected void onPause() {
        super.onPause();
        inForeground = false;
        // Off screen (or while a game streams): the page stops drawing.
        if (web != null) web.onPause();
        stopUpdates(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        takeQrLink(intent);
        if ("files".equals(intent.getStringExtra("tab"))) emit("openTab", "{\"tab\":\"files\"}");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        artLoader.shutdownNow();
        if (files != null) files.shutdown();
        pairJob.incrementAndGet();
        cancelPairHttp();
        pairExec.shutdownNow();
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
                applyRemote(details);
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
        if (storedRemote(d.uuid) != null) {
            o.put("remote", true);
            o.put("account", prefs().getString("account:" + d.uuid, ""));
        }
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
        emitTailscale();
    }

    // ------------------------------------------------------------------
    // Playing away from home (Tailscale)
    // ------------------------------------------------------------------

    static final String TAILSCALE_PACKAGE = "com.tailscale.ipn";

    private SharedPreferences prefs() {
        return getSharedPreferences("eclipse", MODE_PRIVATE);
    }

    /** Tailscale address of a PC, from its QR code (null if none). */
    private ComputerDetails.AddressTuple storedRemote(String uuid) {
        if (uuid == null) return null;
        String v = prefs().getString("remote:" + uuid, null);
        if (v == null) return null;
        int bar = v.lastIndexOf('|');
        try {
            return new ComputerDetails.AddressTuple(v.substring(0, bar), Integer.parseInt(v.substring(bar + 1)));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The PC's Tailscale address goes in the "manual address" slot, the
     * only one Moonlight never overwrites (the home address is found again
     * on the Wi-Fi each time): both are tried, the one that answers wins.
     * With the built-in Tailscale, each PC is relayed on this phone at its
     * own local address (127.0.0.2, .3, …).
     */
    private void applyRemote(ComputerDetails d) {
        ComputerDetails.AddressTuple r = storedRemote(d.uuid);
        if (r == null) return;
        if (EclipseTunnel.available()) {
            r = new ComputerDetails.AddressTuple(loopFor(d.uuid), r.port);
        }
        if (d.manualAddress == null || !r.address.equals(d.manualAddress.address) || r.port != d.manualAddress.port) {
            d.manualAddress = r;
        }
    }

    /** Local address of a PC's relay on this phone (127.0.0.2 to .250), kept for good. */
    private String loopFor(String id) {
        return EclipsePcFiles.loopFor(this, id);
    }

    // ---- built-in Tailscale ----

    private final Object tunnelLock = new Object();
    private final Object syncLock = new Object();
    private volatile String tunnelLast = "";
    private volatile boolean tunnelWatching;
    private volatile long lastDiscover;
    /** The engine was started during this session: keep it running. */
    private volatile boolean tunnelWanted;

    private boolean hasRemote() {
        for (String k : prefs().getAll().keySet()) {
            if (k.startsWith("remote:")) return true;
        }
        return false;
    }

    /** Eclipse uses its Tailscale: a PC has a Tailscale address, or it was started once. */
    private boolean tunnelNeeded() {
        return tunnelWanted || hasRemote() || prefs().getBoolean("tunnel_used", false);
    }

    /** Starts the built-in Tailscale (background thread; nothing if already started). */
    private void tunnelStart() {
        if (!EclipseTunnel.available()) return;
        tunnelWanted = true;
        if (!prefs().getBoolean("tunnel_used", false)) {
            prefs().edit().putBoolean("tunnel_used", true).apply();
        }
        synchronized (tunnelLock) {
            try {
                EclipseTunnel.start(this);
            } catch (Exception e) {
                LimeLog.warning("Eclipse: tunnel start: " + e);
            }
        }
    }

    /** Waits (at most ms) until the engine knows whether it is signed in. */
    private String tunnelWait(long ms) {
        long end = System.currentTimeMillis() + ms;
        String st = EclipseTunnel.state();
        while (("starting".equals(st) || "stopped".equals(st)) && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                break;
            }
            st = EclipseTunnel.state();
        }
        return st;
    }

    /** This phone's Tailscale sign-in page (asked for if needed), or "" (at most ms). */
    private String tunnelAuthUrl(long ms) {
        long end = System.currentTimeMillis() + ms;
        boolean asked = false;
        while (System.currentTimeMillis() < end) {
            JSONObject st = EclipseTunnel.status();
            if ("running".equals(st.optString("state"))) return "";
            String url = st.optString("authUrl", "");
            if (!url.isEmpty()) return url;
            if (!asked) {
                asked = true;
                try {
                    EclipseTunnel.login();
                } catch (Exception e) {
                    LimeLog.warning("Eclipse: tunnel login: " + e);
                }
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                break;
            }
        }
        return "";
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException ignored) {
        }
    }

    /**
     * Relays every PC that has a Tailscale address, tells the page, and
     * looks for the PCs of the Tailscale network (every 15 seconds).
     */
    private void tunnelSync() {
        if (!EclipseTunnel.available()) return;
        synchronized (syncLock) {
            JSONObject st;
            if (!tunnelNeeded()) {
                // Never used: the engine is not even started.
                st = new JSONObject();
                put(st, "state", "stopped");
            } else {
                st = EclipseTunnel.status();
            }
            String state = st.optString("state");
            boolean running = "running".equals(state);
            boolean nowRunning = running && !tunnelLast.contains("\"state\":\"running\"");
            if (running) {
                String err = relayAll();
                if (err != null) put(st, "error", err);
            }
            put(st, "available", true);
            String s = st.toString();
            if (!s.equals(tunnelLast)) {
                tunnelLast = s;
                emit("tunnel", s);
                if (nowRunning && managerBinder != null) {
                    // Reach remote PCs through the relays at once.
                    for (ComputerDetails c : computers.values()) {
                        if (storedRemote(c.uuid) != null) managerBinder.invalidateStateForComputer(c.uuid);
                    }
                }
            }
            if (running && (nowRunning || System.currentTimeMillis() - lastDiscover > 15000)) {
                discoverPcs();
            }
        }
    }

    /** Relays each PC that has a Tailscale address; returns the last error, or null. */
    private String relayAll() {
        String err = null;
        for (String k : new ArrayList<>(prefs().getAll().keySet())) {
            if (!k.startsWith("remote:")) continue;
            String uuid = k.substring(7);
            ComputerDetails.AddressTuple r = storedRemote(uuid);
            if (r == null) continue;
            try {
                EclipseTunnel.forwardAt(loopFor(uuid), r.address, r.port);
            } catch (Exception e) {
                err = "Relais impossible : " + e.getMessage();
            }
        }
        return err;
    }

    /**
     * Finds the PCs of the Tailscale network, like Moonlight finds those of
     * the Wi-Fi: a known PC gets its Tailscale address without a new QR
     * code, a new one appears in the list (a PC removed by the person is
     * left out).
     */
    private void discoverPcs() {
        lastDiscover = System.currentTimeMillis();
        java.util.TreeSet<Integer> ports = new java.util.TreeSet<>();
        ports.add(EclipseQr.DEFAULT_PORT);
        for (String k : new ArrayList<>(prefs().getAll().keySet())) {
            if (!k.startsWith("remote:")) continue;
            ComputerDetails.AddressTuple r = storedRemote(k.substring(7));
            if (r != null) ports.add(r.port);
        }
        JSONArray found = EclipseTunnel.discover(android.text.TextUtils.join(",", ports), 5000);
        lastDiscover = System.currentTimeMillis();
        for (int i = 0; i < found.length(); i++) {
            JSONObject o = found.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id"), ip = o.optString("ip");
            int port = o.optInt("port", EclipseQr.DEFAULT_PORT);
            if (id.isEmpty() || ip.isEmpty() || prefs().getBoolean("hidden:" + id, false)) continue;
            String v = ip + "|" + port;
            boolean changed = !v.equals(prefs().getString("remote:" + id, null));
            if (changed) prefs().edit().putString("remote:" + id, v).apply();
            String local = loopFor(id);
            try {
                EclipseTunnel.forwardAt(local, ip, port);
            } catch (Exception e) {
                LimeLog.warning("Eclipse: relay " + local + ": " + e);
                continue;
            }
            ComputerManagerService.ComputerManagerBinder mb = managerBinder;
            if (mb == null) continue;
            ComputerDetails live = mb.getComputer(id);
            if (live != null) {
                if (changed) {
                    applyRemote(live);
                    ComputerDetails shown = computers.get(id);
                    if (shown != null) applyRemote(shown);
                    mb.invalidateStateForComputer(id);
                }
                continue;
            }
            LimeLog.info("Eclipse: PC found on Tailscale: " + o.optString("name") + " (" + local + ")");
            ComputerDetails d = new ComputerDetails();
            d.manualAddress = new ComputerDetails.AddressTuple(local, port);
            try {
                mb.addComputerBlocking(d);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Keeps the page informed while Eclipse is on screen, and starts the
     * engine (again, if its process was restarted) when it is needed.
     */
    private void tunnelWatch() {
        if (!EclipseTunnel.available() || tunnelWatching) return;
        tunnelWatching = true;
        new Thread("eclipse-tunnel-watch") {
            @Override
            public void run() {
                try {
                    while (inForeground && !isFinishing() && EclipseTunnel.available()) {
                        if (tunnelNeeded() && "stopped".equals(EclipseTunnel.state())) {
                            tunnelStart();
                        }
                        tunnelSync();
                        Thread.sleep(2000);
                    }
                } catch (Throwable e) {
                    LimeLog.warning("Eclipse: tunnel watch: " + e);
                } finally {
                    tunnelWatching = false;
                }
                if (!EclipseTunnel.available()) emitTunnelOff();
            }
        }.start();
    }

    /** Built-in Tailscale missing, or switched off because it kept failing here. */
    private void emitTunnelOff() {
        JSONObject o = new JSONObject();
        put(o, "available", false);
        put(o, "failed", EclipseTunnel.failedHere());
        tunnelLast = "";
        emit("tunnel", o.toString());
    }

    /** Sign-in with the Tailscale account (once): opens the page in this phone's browser. */
    private void tunnelLogin() {
        tunnelWanted = true;
        new Thread() {
            @Override
            public void run() {
                try {
                    tunnelStart();
                    if ("running".equals(tunnelWait(10000))) {
                        tunnelSync();
                        return;
                    }
                    String url = tunnelAuthUrl(15000);
                    if (url.isEmpty()) {
                        if ("running".equals(EclipseTunnel.state())) {
                            tunnelSync();
                            return;
                        }
                        emitTunnelError("La page de connexion Tailscale ne vient pas. Vérifie la connexion internet du téléphone. "
                                + EclipseTunnel.lastLog());
                        return;
                    }
                    openSignIn(url);
                    tunnelWatch();
                } catch (Exception e) {
                    emitTunnelError("Connexion à Tailscale impossible : " + e.getMessage());
                }
            }
        }.start();
    }

    private void openSignIn(final String u) {
        android.util.Log.i("Eclipse", "Tailscale sign-in page ready: " + Uri.parse(u).getHost());
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                } catch (Exception e) {
                    Toast.makeText(EclipseActivity.this, "Ouvre cette page : " + u, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void emitTunnelError(String msg) {
        JSONObject o = EclipseTunnel.status();
        put(o, "available", true);
        put(o, "error", msg);
        tunnelLast = "";
        emit("tunnel", o.toString());
    }

    private void saveRemote(ComputerDetails target, EclipseQr q) {
        SharedPreferences.Editor e = prefs().edit().remove("hidden:" + target.uuid);
        if (!q.remote.isEmpty()) {
            e.putString("remote_last", target.uuid)
                    .putString("remote:" + target.uuid, q.remote + "|" + q.port)
                    .putString("account:" + target.uuid, q.remoteAccount);
        }
        e.commit();
        if (q.remote.isEmpty()) return;
        if (managerBinder != null) {
            ComputerDetails live = managerBinder.getComputer(target.uuid);
            if (live != null) applyRemote(live);
        }
        applyRemote(target);
        if (EclipseTunnel.available() && "running".equals(EclipseTunnel.state())) {
            try {
                EclipseTunnel.forwardAt(loopFor(target.uuid), q.remote, q.port);
            } catch (Exception ex) {
                LimeLog.warning("Eclipse: relay: " + ex);
            }
        }
    }

    private boolean tailscaleInstalled() {
        try {
            getPackageManager().getPackageInfo(TAILSCALE_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** A VPN (Tailscale) is connected on this phone. */
    @SuppressWarnings("deprecation")
    private boolean vpnActive() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void emitTailscale() {
        try {
            JSONObject o = new JSONObject();
            o.put("installed", tailscaleInstalled());
            o.put("vpn", vpnActive());
            emit("tailscale", o.toString());
        } catch (JSONException ignored) {
        }
    }

    private void openTailscale() {
        Intent i = getPackageManager().getLaunchIntentForPackage(TAILSCALE_PACKAGE);
        if (i == null) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + TAILSCALE_PACKAGE));
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + TAILSCALE_PACKAGE)));
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
    // QR code pairing (Eclipse 2.0)
    // ------------------------------------------------------------------

    /** A QR code read by the phone's camera app opens eclipse://pair?... */
    private void takeQrLink(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null
                || !"eclipse".equalsIgnoreCase(intent.getData().getScheme())) {
            return;
        }
        pendingQr = intent.getData().toString();
        setIntent(new Intent(this, EclipseActivity.class)); // not again after a rotation
        runPendingQr();
    }

    private void runPendingQr() {
        if (pendingQr != null && pageReady && managerBinder != null) {
            String raw = pendingQr;
            pendingQr = null;
            handleQrText(raw);
        }
    }

    private boolean playServicesOk() {
        try {
            return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Downloads Google's scanner in advance (it is not inside the app). */
    private void prepareScanner() {
        try {
            if (!playServicesOk()) return;
            ModuleInstallClient installer = ModuleInstall.getClient(this);
            installer.installModules(ModuleInstallRequest.newBuilder()
                            .addApi(GmsBarcodeScanning.getClient(this)).build())
                    .addOnFailureListener(e -> LimeLog.warning("Eclipse: scanner not installed yet: " + e));
        } catch (Throwable t) {
            LimeLog.warning("Eclipse: scanner preparation failed: " + t);
        }
    }

    /** Opens Google's QR code scanner (no camera permission needed). UI thread. */
    private void scanQr() {
        if (!playServicesOk()) {
            emitQr("error", null, null, "Le scanner a besoin des services Google Play, absents de ce téléphone. "
                    + "Vise plutôt le QR code avec l'appareil photo du téléphone, ou utilise « Connecter » sur la carte de ton PC.");
            return;
        }
        try {
            GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .enableAutoZoom()
                    .build();
            GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(this, options);
            scanner.startScan()
                    .addOnSuccessListener(barcode -> {
                        scanTries = 0;
                        scanWanted = false;
                        handleQrText(barcode.getRawValue());
                    })
                    .addOnCanceledListener(() -> {
                        scanTries = 0;
                        scanWanted = false;
                        emitQr("cancelled", null, null, null);
                    })
                    .addOnFailureListener(this::scanFailed);
        } catch (Throwable t) {
            scanFailed(t instanceof Exception ? (Exception) t : new Exception(t));
        }
    }

    /** Short technical description of an error (shown under the message). */
    private static String describe(Throwable e) {
        StringBuilder b = new StringBuilder(e.getClass().getSimpleName());
        if (e.getMessage() != null) b.append(": ").append(e.getMessage());
        StackTraceElement[] st = e.getStackTrace();
        for (int i = 0; i < Math.min(3, st.length); i++) {
            b.append(" @ ").append(st[i].getClassName().replaceAll("^.*\\.", "")).append('.').append(st[i].getMethodName());
        }
        Throwable c = e.getCause();
        if (c != null && c != e) b.append(" ← ").append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
        return b.toString();
    }

    /** The scanner is downloaded by Google Play the first time: wait for it. */
    private void scanFailed(Exception e) {
        LimeLog.warning("Eclipse: scanner: " + android.util.Log.getStackTraceString(e));
        String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        boolean downloading = m.contains("download") || m.contains("module") || m.contains("waiting")
                || m.contains("unavailable") || m.contains("télécharg");
        if (scanTries < (downloading ? 12 : 2)) {
            if (scanTries == 0) prepareScanner();
            scanTries++;
            emitQr("preparing", null, null, null);
            web.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (inForeground && scanWanted) scanQr();
                    else scanTries = 0;
                }
            }, 2500);
            return;
        }
        scanTries = 0;
        scanWanted = false;
        emitQr("error", null, null, "Le scanner ne s'ouvre pas. Vérifie la connexion internet du téléphone, "
                + "ou vise le QR code avec l'appareil photo.\n\nDétail : " + describe(e));
    }

    private void handleQrText(String raw) {
        final EclipseQr q;
        try {
            q = EclipseQr.parse(raw);
        } catch (EclipseQr.Invalid e) {
            String msg;
            switch (e.reason) {
                case "app-link":
                    msg = "Ce QR code sert à télécharger Eclipse. Sur le PC, clique plutôt « Connecter un appareil » et scanne le code affiché.";
                    break;
                case "newer":
                    msg = "Ce QR code vient d'une version plus récente d'Eclipse : mets à jour l'appli Eclipse de ce téléphone.";
                    break;
                case "damaged":
                    msg = "Ce QR code est incomplet. Rescanne-le, bien en face de l'écran du PC.";
                    break;
                default:
                    msg = "Ce QR code ne vient pas d'Eclipse. Sur le PC, ouvre Eclipse et clique « Connecter un appareil ».";
            }
            emitQr("error", null, null, msg);
            return;
        }
        final int job = pairJob.incrementAndGet();
        cancelPairHttp();
        emitQr("search", q.name, null, null);
        pairExec.submit(new Runnable() {
            @Override
            public void run() {
                runQrPairing(q, job);
            }
        });
    }

    private boolean superseded(int job) {
        return job != pairJob.get();
    }

    /**
     * Stops the requests of the pairing in progress. Repeated for a few
     * seconds, in case the pairing was just about to send its next request.
     */
    private void cancelPairHttp() {
        final NvHTTP h = pairHttp;
        if (h == null) {
            return;
        }
        h.eclipseCancel();
        new Thread() {
            @Override
            public void run() {
                for (int i = 0; i < 10 && pairHttp == h; i++) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (pairHttp == h) {
                        h.eclipseCancel();
                    }
                }
            }
        }.start();
    }

    private ComputerManagerService.ComputerManagerBinder waitBinder() throws InterruptedException {
        for (int i = 0; i < 100 && managerBinder == null; i++) {
            Thread.sleep(100);
        }
        return managerBinder;
    }

    private void runQrPairing(EclipseQr q, int job) {
        String name = q.name.isEmpty() ? "ton PC" : q.name;
        boolean stopped = false;
        try {
            ComputerManagerService.ComputerManagerBinder mb = waitBinder();
            if (mb == null) {
                emitQr("error", name, null, "Eclipse démarre encore. Réessaie dans un instant.");
                return;
            }

            // 0. Play away from home: the built-in Tailscale starts now, while
            //    the PC is looked for on the Wi-Fi.
            boolean tunnel = !q.remote.isEmpty() && EclipseTunnel.available();
            if (tunnel) tunnelStart();
            boolean lan = EclipseNet.hasLan(this);
            String tunnelState = "";

            // 1. Find the PC: already known, or at one of its addresses.
            ComputerDetails target = null;
            if (!q.id.isEmpty()) {
                for (ComputerDetails c : computers.values()) {
                    if (q.id.equalsIgnoreCase(c.uuid) && c.state == ComputerDetails.State.ONLINE && c.activeAddress != null) {
                        target = c;
                    }
                }
            }
            boolean otherPc = false;
            if (target == null) {
                List<String> order = new ArrayList<>();
                for (String a : q.addresses) {
                    // On 4G, the PC's home addresses cannot answer: not tried.
                    if (lan || !EclipseNet.isPrivate(a)) order.add(a);
                }
                if (!q.remote.isEmpty() && !tunnel && !order.contains(q.remote)) {
                    // The Tailscale app (VPN) on this phone
                    if (vpnActive()) order.add(0, q.remote);
                    else order.add(q.remote);
                }
                for (String addr : order) {
                    if (superseded(job)) return;
                    ComputerDetails d = probePc(mb, addr, q.port);
                    if (d == null) continue;
                    if (!q.id.isEmpty() && !q.id.equalsIgnoreCase(d.uuid)) {
                        otherPc = true;
                        continue;
                    }
                    target = d;
                    break;
                }
                // Not on this Wi-Fi: through the built-in Tailscale.
                if (target == null && tunnel && !superseded(job)) {
                    emitQr("search", name, null, null);
                    tunnelState = tunnelWait(12000);
                    if ("running".equals(tunnelState)) {
                        String local = loopFor(q.id.isEmpty() ? "qr:" + q.remote : q.id);
                        try {
                            EclipseTunnel.forwardAt(local, q.remote, q.port);
                            // The first connection through Tailscale can take a few seconds.
                            for (int i = 0; i < 3 && target == null && !superseded(job); i++) {
                                ComputerDetails d = probePc(mb, local, q.port);
                                if (d != null && (q.id.isEmpty() || q.id.equalsIgnoreCase(d.uuid))) target = d;
                                else if (d != null) otherPc = true;
                            }
                        } catch (Exception e) {
                            LimeLog.warning("Eclipse: relay: " + e);
                        }
                    }
                }
            }
            if (superseded(job)) return;
            if (target == null) {
                if (tunnel && "needslogin".equals(tunnelState)) {
                    // Away from the PC's Wi-Fi, and this phone is not signed in
                    // to Tailscale yet: sign in, then Eclipse tries again by itself.
                    loginThenRetry(q, name, job);
                    return;
                }
                notFound(q, name, otherPc, tunnelState);
                return;
            }
            if (target.name != null && !target.name.isEmpty()) name = target.name;
            computers.put(target.uuid, target);
            pushComputers();

            // 1b. Not signed in to Tailscale yet: the PC opens this phone's
            //     sign-in page during the pairing (Eclipse 2.3 or newer on
            //     the PC), else the phone opens it once connected.
            String token = q.token;
            String login = "";
            String signIn = "";
            if (tunnel) {
                if (tunnelState.isEmpty() || "starting".equals(tunnelState) || "stopped".equals(tunnelState)) {
                    tunnelState = tunnelWait(10000);
                }
                if ("running".equals(tunnelState)) {
                    login = "running";
                } else if ("needslogin".equals(tunnelState) && !superseded(job)) {
                    signIn = tunnelAuthUrl(10000);
                    if (!signIn.isEmpty()) {
                        token = q.pcLogin ? EclipseQr.deviceToken(q.token, signIn) : q.token;
                        login = token.equals(q.token) ? "phone" : "pc";
                    }
                }
            }

            // 2. Pair, with the PIN of the QR code and its token in our name.
            emitQr("pair", name, target.uuid, null);
            stopUpdates(true);
            stopped = true;
            // Even when the phone already knows this PC, it pairs again: the PC
            // waits for it to say "connected", and gets the phone's current name.
            NvHTTP h = http(target);
            pairHttp = h;
            if (superseded(job)) return;
            PairingManager pm = h.getPairingManager();
            String serverInfo = h.getServerInfo(true);
            if (superseded(job)) return;
            EclipsePairing.setToken(token);
            long until = System.currentTimeMillis() + 60000;
            PairState st;
            while (true) {
                try {
                    st = pm.pair(serverInfo, q.pin);
                    break;
                } catch (HostHttpResponseException e) {
                    // Another device is pairing with this PC: wait for it.
                    boolean busy = e.getErrorCode() == 409 || e.getErrorCode() == 503;
                    if (!busy || System.currentTimeMillis() > until || superseded(job)) throw e;
                    emitQr("busy", name, target.uuid, null);
                    Thread.sleep(3000);
                    if (superseded(job)) return;
                    emitQr("pair", name, target.uuid, null);
                }
            }
            if (superseded(job)) return;
            if (st == PairState.PAIRED) {
                ComputerDetails live = mb.getComputer(target.uuid);
                if (live != null) live.serverCert = pm.getPairedCert();
                target.serverCert = pm.getPairedCert();
                mb.invalidateStateForComputer(target.uuid);
                qrPaired(target, name, job, q, login, signIn);
            } else {
                emitQr("error", name, target.uuid, st == PairState.PIN_WRONG
                        ? "Le code ne correspond plus. Rescanne le QR code affiché sur le PC."
                        : st == PairState.ALREADY_IN_PROGRESS ? "Une autre connexion est en cours sur ce PC. Réessaie dans un instant."
                        : target.runningGameId != 0 ? "Quitte le jeu en cours sur le PC, puis réessaie."
                        : "La connexion a échoué. Rescanne le QR code affiché sur le PC.");
            }
        } catch (Exception e) {
            if (!superseded(job)) {
                emitQr("error", name, null, pairError(e, true));
            }
        } finally {
            EclipsePairing.clearToken();
            pairHttp = null;
            if (stopped) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        startUpdates();
                    }
                });
            }
        }
    }

    /** The PC at this address (added to Moonlight's list), or null. */
    private static ComputerDetails probePc(ComputerManagerService.ComputerManagerBinder mb, String addr, int port)
            throws InterruptedException {
        ComputerDetails d = new ComputerDetails();
        d.manualAddress = new ComputerDetails.AddressTuple(addr, port);
        if (!mb.addComputerBlocking(d) || d.uuid == null || d.activeAddress == null) {
            return null;
        }
        return d;
    }

    /**
     * QR code scanned away from the PC's Wi-Fi (or on 4G at home) before this
     * phone ever signed in to Tailscale: opens the sign-in page, waits for it
     * (5 minutes at most), then connects to the PC through Tailscale.
     */
    private void loginThenRetry(EclipseQr q, String name, int job) {
        String url = tunnelAuthUrl(15000);
        if (superseded(job)) return;
        if (url.isEmpty() && !"running".equals(EclipseTunnel.state())) {
            notFound(q, name, false, "needslogin");
            return;
        }
        if (!url.isEmpty()) {
            try {
                JSONObject ev = new JSONObject();
                ev.put("stage", "login");
                ev.put("name", name);
                ev.put("account", q.remoteAccount);
                emit("qr", ev.toString());
            } catch (JSONException ignored) {
            }
            openSignIn(url);
        }
        long end = System.currentTimeMillis() + 300000;
        while (System.currentTimeMillis() < end && !superseded(job)) {
            if ("running".equals(EclipseTunnel.state())) {
                emitQr("search", name, null, null);
                runQrPairing(q, job);
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
        if (!superseded(job)) {
            emitQr("error", name, null, "La connexion à Tailscale n'a pas abouti. Rescanne le QR code affiché sur le PC pour réessayer.");
        }
    }

    /** Pairing with a 4-digit code shown here and typed on the PC. */
    private void runCodePairing(String uuid, String pin, int job) {
        JSONObject ev = new JSONObject();
        boolean stopped = false;
        try {
            ev.put("uuid", uuid);
            ComputerDetails c = computers.get(uuid);
            if (c == null || managerBinder == null || c.activeAddress == null) {
                ev.put("ok", false);
                ev.put("error", "Ce PC n'est pas joignable. Vérifie qu'il est allumé.");
            } else {
                stopUpdates(true);
                stopped = true;
                NvHTTP h = http(c);
                pairHttp = h;
                if (h.getPairState() == PairState.PAIRED) {
                    ev.put("ok", true);
                } else {
                    PairingManager pm = h.getPairingManager();
                    String serverInfo = h.getServerInfo(true);
                    if (superseded(job)) return;
                    EclipsePairing.setToken("");
                    PairState st = pm.pair(serverInfo, pin);
                    if (st == PairState.PAIRED) {
                        ComputerDetails live = managerBinder.getComputer(c.uuid);
                        if (live != null) live.serverCert = pm.getPairedCert();
                        c.serverCert = pm.getPairedCert();
                        c.pairState = PairState.PAIRED;
                        prefs().edit().remove("hidden:" + c.uuid).apply();
                        managerBinder.invalidateStateForComputer(c.uuid);
                        ev.put("ok", true);
                    } else {
                        ev.put("ok", false);
                        ev.put("error", st == PairState.PIN_WRONG ? "Le code tapé sur le PC n'était pas le bon. Réessaie."
                                : st == PairState.ALREADY_IN_PROGRESS ? "Une connexion est déjà en cours sur ce PC."
                                : c.runningGameId != 0 ? "Quitte le jeu en cours sur le PC, puis réessaie."
                                : "La connexion a échoué.");
                    }
                }
            }
        } catch (Exception e) {
            try {
                ev.put("ok", false);
                ev.put("error", pairError(e, false));
            } catch (JSONException ignored) {
            }
        } finally {
            EclipsePairing.clearToken();
            pairHttp = null;
            if (stopped) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        startUpdates();
                    }
                });
            }
        }
        if (!superseded(job)) {
            emit("paired", ev.toString());
            pushComputers();
        }
    }

    /** Explains why the PC of a QR code was not found, and what to do. */
    private void notFound(EclipseQr q, String name, boolean otherPc, String tunnelState) {
        String msg;
        String fix = null;
        boolean vpn = vpnActive();
        if (otherPc) {
            msg = "Un autre PC répond à l'adresse de ce QR code. Rescanne le code affiché sur " + name + ".";
        } else if (!q.remote.isEmpty() && EclipseTunnel.available() && "running".equals(tunnelState)) {
            String mine = EclipseTunnel.status().optString("account", "");
            if (!q.remoteAccount.isEmpty() && !mine.isEmpty() && !mine.equalsIgnoreCase(q.remoteAccount)) {
                msg = "Ce téléphone est connecté à Tailscale avec " + mine + ", mais " + name + " utilise " + q.remoteAccount
                        + ". Déconnecte-toi de Tailscale (Réglages → Jouer partout), puis rescanne le QR code.";
            } else {
                msg = "Le téléphone ne trouve pas " + name + ", ni sur le Wi-Fi ni par Internet. Vérifie que le PC est allumé, "
                        + "avec Eclipse ouvert, puis rescanne le QR code.";
            }
            fix = "tunnel";
        } else if (!q.remote.isEmpty() && EclipseTunnel.available()) {
            msg = "Le téléphone ne trouve pas " + name + " sur ce Wi-Fi, et la connexion à Tailscale n'a pas abouti. "
                    + "Vérifie que le téléphone a Internet, puis rescanne le QR code.";
            fix = "tunnel";
        } else if (!q.remote.isEmpty() && !vpn) {
            msg = "Ton téléphone n'est pas sur le Wi-Fi de " + name + ". Pour le joindre de loin, active Tailscale sur ce téléphone"
                    + (q.remoteAccount.isEmpty() ? "" : " (compte " + q.remoteAccount + ")") + ", puis rescanne le QR code.";
            fix = "tailscale";
        } else if (!q.remote.isEmpty()) {
            msg = "Tailscale est actif, mais " + name + " ne répond pas. Vérifie que le PC est allumé avec Eclipse ouvert, "
                    + "et que les deux utilisent le même compte Tailscale"
                    + (q.remoteAccount.isEmpty() ? "" : " (" + q.remoteAccount + ")") + ".";
            fix = "tailscale";
        } else if (!onWifi()) {
            msg = "Ton téléphone n'est pas sur le Wi-Fi de " + name + ". Pour cette première connexion, connecte-le au Wi-Fi de la maison, "
                    + "puis rescanne le QR code.";
        } else {
            msg = "Le téléphone ne trouve pas " + name + ". Vérifie qu'il est sur le même Wi-Fi que le PC, et qu'Eclipse est ouvert sur le PC.";
        }
        try {
            JSONObject ev = new JSONObject();
            ev.put("stage", "error");
            ev.put("name", name);
            ev.put("error", msg);
            if (fix != null) {
                ev.put("fix", fix);
                ev.put("installed", tailscaleInstalled());
            }
            emit("qr", ev.toString());
        } catch (JSONException ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private boolean onWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Connected. login: "running" (Tailscale ready: playable everywhere),
     * "pc" (the PC opened this phone's Tailscale sign-in page), "phone"
     * (this phone opens it now), or "" (no Tailscale).
     */
    private void qrPaired(ComputerDetails target, String name, int job, EclipseQr q, String login, final String signIn) {
        if (superseded(job)) return;
        target.pairState = PairState.PAIRED;
        saveRemote(target, q);
        computers.put(target.uuid, target);
        pushComputers();
        try {
            JSONObject ev = new JSONObject();
            ev.put("stage", "done");
            ev.put("name", name);
            ev.put("uuid", target.uuid);
            if (!q.remote.isEmpty()) {
                ev.put("remote", true);
                ev.put("account", q.remoteAccount);
                ev.put("tailscale", tailscaleInstalled());
                ev.put("tunnel", EclipseTunnel.available());
                ev.put("login", login);
            }
            emit("qr", ev.toString());
        } catch (JSONException ignored) {
        }
        if ("phone".equals(login) && !signIn.isEmpty() && web != null) {
            // The person first sees "connected", then the sign-in page opens.
            web.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!superseded(job) && inForeground) openSignIn(signIn);
                }
            }, 1800);
        }
    }

    /** Explains a pairing failure simply. */
    private static String pairError(Exception e, boolean qr) {
        String again = qr ? " Rescanne le QR code affiché sur le PC." : " Réessaie.";
        if (e instanceof HostHttpResponseException) {
            int code = ((HostHttpResponseException) e).getErrorCode();
            switch (code) {
                case 400:
                    return qr ? "Ce QR code n'est plus valable." + again : "Le PC a refusé la demande." + again;
                case 404:
                    return qr ? "Le PC a refusé la connexion." + again : "Le code tapé sur le PC n'était pas le bon." + again;
                case 408:
                    return "Le PC n'a pas répondu à temps. Sur le PC, clique « Connecter un appareil »" + (qr ? " et rescanne le code." : ", puis réessaie.");
                case 409:
                case 503:
                    return "Une autre demande de connexion attend sur le PC. Sur le PC, refuse-la dans « Connecter un appareil », puis réessaie.";
                case 401:
                    return "Le PC n'a pas confirmé la connexion." + again;
                default:
                    return "Le PC a répondu : " + ((HostHttpResponseException) e).getErrorMessage() + "." + again;
            }
        }
        if (e instanceof FileNotFoundException) {
            return qr ? "Le PC a refusé la connexion." + again : "Le code tapé sur le PC n'était pas le bon." + again;
        }
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException
                || e instanceof java.net.SocketTimeoutException || e instanceof java.net.NoRouteToHostException) {
            return "Le PC ne répond plus. Vérifie qu'il est allumé et sur le même Wi-Fi.";
        }
        String m = e.getMessage();
        return "La connexion a échoué (" + (m == null ? e.getClass().getSimpleName() : m) + ")." + again;
    }

    private void emitQr(String stage, String name, String uuid, String error) {
        try {
            JSONObject ev = new JSONObject();
            ev.put("stage", stage);
            if (name != null) ev.put("name", name);
            if (uuid != null) ev.put("uuid", uuid);
            if (error != null) ev.put("error", error);
            emit("qr", ev.toString());
        } catch (JSONException ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Talking to the page
    // ------------------------------------------------------------------

    /** New version of Eclipse: checked each time Eclipse opens. */
    private void checkUpdate(final boolean asked) {
        new Thread("eclipse-update") {
            @Override
            public void run() {
                try {
                    if (!asked) Thread.sleep(1500); // let the screen appear first
                } catch (InterruptedException e) {
                    return;
                }
                EclipseUpdate.check(getApplicationContext(), new EclipseUpdate.Listener() {
                    @Override
                    public void onUpdate(JSONObject ev) {
                        emit("update", ev.toString());
                    }
                }, asked);
            }
        }.start();
    }

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
    // ------------------------------------------------------------------
    // Files (Eclipse 2.4): the PCs' files here, this phone's files on the PCs
    // ------------------------------------------------------------------

    private EclipseFiles files;
    private volatile boolean openFilesTab;
    private volatile boolean shareWanted;
    private String pickUuid, pickDir;
    private Runnable afterStorage;
    private static final int REQ_PICK = 7301, REQ_STORAGE = 7302, REQ_SHARE = 7303, REQ_NOTIF = 7304;

    private void emitShare() {
        JSONObject o = new JSONObject();
        put(o, "enabled", EclipseShare.enabled(this));
        put(o, "access", EclipseShare.hasAccess(this));
        put(o, "waiting", shareWanted);
        emit("share", o.toString());
    }

    private void shareOn(boolean on) {
        if (!on) {
            shareWanted = false;
            EclipseShare.set(this, false);
            emitShare();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        if (EclipseShare.hasAccess(this)) {
            EclipseShare.set(this, true);
            emitShare();
            return;
        }
        shareWanted = true;
        emitShare();
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_SHARE);
        }
    }

    /** Android 8 and 9: saving in Downloads needs a permission. */
    private void withStorage(Runnable r) {
        if (Build.VERSION.SDK_INT >= 29 || (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)) {
            r.run();
            return;
        }
        afterStorage = r;
        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean ok = grantResults.length > 0;
        for (int g : grantResults) ok &= g == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_STORAGE) {
            Runnable r = afterStorage;
            afterStorage = null;
            if (ok && r != null) r.run();
            else if (!ok) toastLong("Sans cette autorisation, Eclipse ne peut pas enregistrer les fichiers téléchargés.");
        } else if (requestCode == REQ_SHARE) {
            if (ok) {
                shareWanted = false;
                EclipseShare.set(this, true);
            } else {
                shareWanted = false;
                toastLong("Sans cette autorisation, tes PC ne peuvent pas voir les fichiers de ce téléphone.");
            }
            emitShare();
        }
    }

    private void toastLong(final String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK) return;
        if (resultCode != RESULT_OK || data == null || pickUuid == null || files == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (!uris.isEmpty()) files.upload(pickUuid, pickDir, uris);
    }

    private Map<String, Boolean> onlineMap() {
        Map<String, Boolean> m = new java.util.HashMap<>();
        for (ComputerDetails d : computers.values()) {
            if (d.state != null && d.uuid != null) m.put(d.uuid, d.state == ComputerDetails.State.ONLINE);
        }
        return m;
    }

    private class Bridge {
        @JavascriptInterface
        public String version() {
            return BuildConfig.VERSION_NAME;
        }

        // ---- files ----

        @JavascriptInterface
        public String filesDevices() {
            return files == null ? "[]" : files.devices(onlineMap());
        }

        @JavascriptInterface
        public void filesCall(String req, String uuid, String op, String path) {
            if (files != null) files.call(req, uuid, op, path);
        }

        @JavascriptInterface
        public void filesDownload(final String id, final String uuid, final String path, final String name, final boolean open) {
            if (files == null) return;
            runOnUiThread(() -> withStorage(() -> files.download(id, uuid, path, name, open)));
        }

        @JavascriptInterface
        public void filesPick(final String uuid, final String dir) {
            runOnUiThread(() -> {
                pickUuid = uuid;
                pickDir = dir;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(i, REQ_PICK);
                } catch (Exception e) {
                    toastLong("Impossible d'ouvrir le choix de fichiers.");
                }
            });
        }

        @JavascriptInterface
        public void filesView(String uri, String name) {
            if (files != null) files.view(Uri.parse(uri), name);
        }

        @JavascriptInterface
        public void shareSet(final boolean on) {
            runOnUiThread(() -> shareOn(on));
        }

        @JavascriptInterface
        public void shareState() {
            emitShare();
        }

        /** "Mettre à jour" on the banner (mobile data: download on request). */
        @JavascriptInterface
        public void updateNow() {
            checkUpdate(true);
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
            final int job = pairJob.incrementAndGet();
            cancelPairHttp();
            pairExec.submit(new Runnable() {
                @Override
                public void run() {
                    runCodePairing(uuid, pin, job);
                }
            });
            return pin;
        }

        /** Opens the QR code scanner. */
        @JavascriptInterface
        public void scanQr() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    scanTries = 0;
                    scanWanted = true;
                    EclipseActivity.this.scanQr();
                }
            });
        }

        /** Stops the pairing in progress (QR code or 4-digit code). */
        @JavascriptInterface
        public void cancelPairing() {
            pairJob.incrementAndGet();
            cancelPairHttp();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    scanWanted = false;
                }
            });
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
            final String loop = prefs().getString("loop:" + uuid, null);
            // hidden: not added back by the search on Tailscale (a new QR code does)
            prefs().edit().remove("remote:" + uuid).remove("account:" + uuid).remove("loop:" + uuid)
                    .putBoolean("hidden:" + uuid, true).apply();
            if (loop != null && EclipseTunnel.available()) {
                new Thread() {
                    @Override
                    public void run() {
                        EclipseTunnel.stopForward(loop);
                    }
                }.start();
            }
            ComputerDetails c = computers.remove(uuid);
            if (c != null && managerBinder != null) {
                managerBinder.removeComputer(c);
            }
            pushComputers();
        }

        /** Opens the Tailscale app (or its Play Store page). */
        @JavascriptInterface
        public void openTailscale() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    EclipseActivity.this.openTailscale();
                }
            });
        }

        @JavascriptInterface
        public void tailscale() {
            emitTailscale();
            tunnelLast = "";
            if (EclipseTunnel.available()) {
                new Thread() {
                    @Override
                    public void run() {
                        tunnelSync();
                    }
                }.start();
            } else {
                emitTunnelOff();
            }
        }

        /** Technical report (settings): sent to the page as a "diag" event. */
        @JavascriptInterface
        public void diagnostic() {
            new Thread() {
                @Override
                public void run() {
                    String r;
                    try {
                        r = EclipseDiag.report(EclipseActivity.this);
                    } catch (Throwable t) {
                        r = "Rapport impossible : " + t;
                    }
                    emit("diag", JSONObject.quote(r));
                }
            }.start();
        }

        @JavascriptInterface
        public void copy(String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Eclipse", text));
                        if (Build.VERSION.SDK_INT < 33) toast("Copié");
                    } catch (Exception e) {
                        toast("Copie impossible");
                    }
                }
            });
        }

        /** Built-in Tailscale: sign in (opens the browser). */
        @JavascriptInterface
        public void tunnelLogin() {
            EclipseActivity.this.tunnelLogin();
        }

        @JavascriptInterface
        public void tunnelLogout() {
            new Thread() {
                @Override
                public void run() {
                    try {
                        EclipseTunnel.logout();
                    } catch (Exception e) {
                        LimeLog.warning("Eclipse: logout: " + e);
                    }
                    tunnelLast = "";
                    tunnelSync();
                }
            }.start();
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
