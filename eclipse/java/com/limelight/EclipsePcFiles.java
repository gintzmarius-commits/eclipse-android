package com.limelight;

import android.content.Context;
import android.content.SharedPreferences;

import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.nvstream.http.ComputerDetails;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

/**
 * Reaching the file sharing of Eclipse on a PC (port 48150): with TLS, the
 * phone shows the certificate it paired with, and only accepts the PC's
 * own (the one Sunshine showed when pairing). Several addresses are tried:
 * the home network one, then the PC's relay through the built-in
 * Tailscale (127.0.0.N), or its Tailscale address (Tailscale app).
 */
final class EclipsePcFiles {
    static final int PORT = 48150;

    private EclipsePcFiles() {
    }

    /** A PC paired with this phone. */
    static final class Pc {
        String uuid;
        String name;
        X509Certificate cert;
        final List<String> hosts = new ArrayList<>();
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("eclipse", Context.MODE_PRIVATE);
    }

    private static final Object loopLock = new Object();

    /** Local address of a PC's relay on this phone (127.0.0.2 to .250), kept for good. */
    static String loopFor(Context c, String id) {
        synchronized (loopLock) {
            SharedPreferences p = prefs(c);
            String v = p.getString("loop:" + id, null);
            if (v != null) return v;
            java.util.HashSet<Object> used = new java.util.HashSet<>();
            for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
                if (e.getKey().startsWith("loop:")) used.add(e.getValue());
            }
            for (int n = 2; n <= 250; n++) {
                String a = "127.0.0." + n;
                if (!used.contains(a)) {
                    p.edit().putString("loop:" + id, a).commit();
                    return a;
                }
            }
            return EclipseTunnel.LOCAL;
        }
    }

    /** "100.x.y.z" of a PC from its QR code / Tailscale, or null. */
    static String tailscaleIp(Context c, String uuid) {
        String v = prefs(c).getString("remote:" + uuid, null);
        if (v == null) return null;
        int bar = v.lastIndexOf('|');
        return bar > 0 ? v.substring(0, bar) : null;
    }

    static int tailscalePort(Context c, String uuid) {
        String v = prefs(c).getString("remote:" + uuid, null);
        if (v == null) return EclipseQr.DEFAULT_PORT;
        try {
            return Integer.parseInt(v.substring(v.lastIndexOf('|') + 1));
        } catch (Exception e) {
            return EclipseQr.DEFAULT_PORT;
        }
    }

    private static boolean usable(ComputerDetails.AddressTuple a) {
        return a != null && a.address != null && !a.address.isEmpty();
    }

    /** The paired PCs and where to reach their files. */
    static List<Pc> pcs(Context c) {
        List<Pc> out = new ArrayList<>();
        List<ComputerDetails> all;
        ComputerDatabaseManager db = null;
        try {
            db = new ComputerDatabaseManager(c);
            all = db.getAllComputers();
        } catch (Throwable t) {
            LimeLog.warning("Eclipse: files: computers: " + t);
            return out;
        } finally {
            if (db != null) db.close();
        }
        boolean lan = EclipseNet.hasLan(c);
        boolean tunnel = EclipseTunnel.available() && "running".equals(EclipseTunnel.state());
        for (ComputerDetails d : all) {
            if (d.serverCert == null || d.uuid == null) continue; // not paired
            Pc p = new Pc();
            p.uuid = d.uuid;
            p.name = d.name == null ? "PC" : d.name;
            p.cert = d.serverCert;
            List<String> home = new ArrayList<>();
            for (ComputerDetails.AddressTuple a : new ComputerDetails.AddressTuple[]{d.localAddress, d.manualAddress, d.activeAddress}) {
                if (!usable(a) || a.address.startsWith("127.") || !EclipseNet.isPrivate(a.address)) continue;
                if (a.address.startsWith("100.")) continue; // Tailscale: below
                if (!home.contains(a.address)) home.add(a.address);
            }
            List<String> far = new ArrayList<>();
            String ts = tailscaleIp(c, d.uuid);
            if (ts != null) {
                if (tunnel) far.add(loopFor(c, d.uuid));
                far.add(ts); // the Tailscale app, if it is on
            }
            if (lan) {
                p.hosts.addAll(home);
                p.hosts.addAll(far);
            } else {
                p.hosts.addAll(far);
                p.hosts.addAll(home);
            }
            if (!p.hosts.isEmpty()) out.add(p);
        }
        return out;
    }

    /** Makes sure the relay of each PC also carries the file sharing port. */
    static void relays(Context c) {
        if (!EclipseTunnel.available() || !"running".equals(EclipseTunnel.state())) return;
        for (String k : new ArrayList<>(prefs(c).getAll().keySet())) {
            if (!k.startsWith("remote:")) continue;
            String uuid = k.substring(7);
            String ip = tailscaleIp(c, uuid);
            if (ip == null) continue;
            try {
                EclipseTunnel.forwardAt(loopFor(c, uuid), ip, tailscalePort(c, uuid));
            } catch (Exception e) {
                LimeLog.warning("Eclipse: files relay: " + e);
            }
        }
    }

    // ---- TLS: this phone's certificate, the PC's pinned one ----

    private static final Map<String, OkHttpClient> clients = new HashMap<>();
    private static AndroidCryptoProvider crypto;

    static synchronized OkHttpClient client(Context c, final X509Certificate pinned, long readSeconds) throws Exception {
        String key = pinned.getSerialNumber() + "/" + readSeconds + "/" + java.util.Arrays.hashCode(pinned.getSignature());
        OkHttpClient cl = clients.get(key);
        if (cl != null) return cl;
        if (crypto == null) crypto = new AndroidCryptoProvider(c.getApplicationContext());
        final AndroidCryptoProvider cp = crypto;
        X509KeyManager km = new X509KeyManager() {
            public String chooseClientAlias(String[] keyTypes, java.security.Principal[] issuers, Socket socket) {
                return "Limelight-RSA";
            }

            public String chooseServerAlias(String keyType, java.security.Principal[] issuers, Socket socket) {
                return null;
            }

            public X509Certificate[] getCertificateChain(String alias) {
                return new X509Certificate[]{cp.getClientCertificate()};
            }

            public String[] getClientAliases(String keyType, java.security.Principal[] issuers) {
                return null;
            }

            public PrivateKey getPrivateKey(String alias) {
                return cp.getClientPrivateKey();
            }

            public String[] getServerAliases(String keyType, java.security.Principal[] issuers) {
                return null;
            }
        };
        X509TrustManager tm = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }

            public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException {
                throw new CertificateException("not a server");
            }

            public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
                if (certs == null || certs.length == 0 || !certs[0].equals(pinned)) {
                    throw new CertificateException("ce n'est pas le PC connecté");
                }
            }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(new KeyManager[]{km}, new X509TrustManager[]{tm}, new SecureRandom());
        cl = new OkHttpClient.Builder()
                .sslSocketFactory(sc.getSocketFactory(), tm)
                .hostnameVerifier((host, session) -> {
                    try {
                        return session.getPeerCertificates().length > 0 && session.getPeerCertificates()[0].equals(pinned);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(readSeconds, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
        clients.put(key, cl);
        return cl;
    }

    // ---- which address answers ----

    private static final Map<String, String> good = new HashMap<>();
    private static final Map<String, Long> goodAt = new HashMap<>();

    /** "https://host:48150" of a PC's file sharing, or throws if none answers. */
    static String base(Pc p, boolean fresh) throws Exception {
        synchronized (good) {
            String g = good.get(p.uuid);
            Long at = goodAt.get(p.uuid);
            if (!fresh && g != null && at != null && System.currentTimeMillis() - at < 120000 && p.hosts.contains(g)) {
                return "https://" + hostPort(g);
            }
        }
        Exception last = null;
        for (String h : p.hosts) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(h, PORT), h.startsWith("127.") ? 2500 : 1800);
                synchronized (good) {
                    good.put(p.uuid, h);
                    goodAt.put(p.uuid, System.currentTimeMillis());
                }
                return "https://" + hostPort(h);
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) LimeLog.info("Eclipse: files: " + p.name + " not reachable: " + last);
        throw new java.io.IOException("le PC ne répond pas : vérifie qu'il est allumé, avec Eclipse 2.4 ou plus récent");
    }

    static void forget(String uuid) {
        synchronized (good) {
            good.remove(uuid);
        }
    }

    private static String hostPort(String h) {
        return (h.indexOf(':') >= 0 ? "[" + h + "]" : h) + ":" + PORT;
    }

    static Pc find(Context c, String uuid) {
        for (Pc p : pcs(c)) {
            if (p.uuid.equals(uuid)) return p;
        }
        return null;
    }
}
