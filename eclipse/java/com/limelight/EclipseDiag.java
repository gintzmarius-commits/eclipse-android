package com.limelight;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Technical report shown in Eclipse's settings ("Rapport technique"), to
 * copy and send when something goes wrong: versions, state of the built-in
 * Tailscale, and why Eclipse's processes stopped the last times (Android
 * keeps that, crash details included, on Android 11 and newer).
 */
public final class EclipseDiag {
    private EclipseDiag() {
    }

    public static String report(Context c) {
        StringBuilder b = new StringBuilder();
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            b.append("Eclipse ").append(pi.versionName).append('\n');
        } catch (Exception e) {
            b.append("Eclipse ?\n");
        }
        b.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", ")
                .append(android.text.TextUtils.join(",", Build.SUPPORTED_ABIS)).append('\n');
        b.append("Tailscale intégré : ").append(EclipseTunnel.available() ? "oui"
                : EclipseTunnel.failedHere() ? "arrêté (trop d'échecs)" : "non").append('\n');
        exits(c, b);
        File crash = new File(c.getFilesDir(), EclipseTunnelProvider.CRASH_FILE);
        if (crash.length() > 0) {
            b.append("\n--- Erreur du moteur réseau ---\n").append(tail(crash, 2500)).append('\n');
        }
        return b.toString();
    }

    private static void exits(Context c, StringBuilder b) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 12);
            SimpleDateFormat f = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE);
            int shown = 0;
            for (ApplicationExitInfo e : list) {
                int r = e.getReason();
                boolean crash = r == ApplicationExitInfo.REASON_CRASH || r == ApplicationExitInfo.REASON_CRASH_NATIVE
                        || r == ApplicationExitInfo.REASON_ANR || r == ApplicationExitInfo.REASON_SIGNALED
                        || r == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                        // the Go runtime ends its process itself (status 2) on a fatal error
                        || (r == ApplicationExitInfo.REASON_EXIT_SELF && e.getStatus() != 0);
                if (!crash || shown >= 4) continue;
                shown++;
                b.append("\n--- Arrêt ").append(f.format(new Date(e.getTimestamp()))).append(" : ")
                        .append(e.getProcessName()).append(", ").append(reason(r))
                        .append(r == ApplicationExitInfo.REASON_SIGNALED || r == ApplicationExitInfo.REASON_CRASH_NATIVE
                                ? " (signal " + e.getStatus() + ")" : r == ApplicationExitInfo.REASON_EXIT_SELF ? " (code " + e.getStatus() + ")" : "")
                        .append('\n');
                if (e.getDescription() != null) b.append(e.getDescription()).append('\n');
                if (r == ApplicationExitInfo.REASON_CRASH_NATIVE && Build.VERSION.SDK_INT >= 31) {
                    try (InputStream in = e.getTraceInputStream()) {
                        if (in != null) b.append(Tombstone.summary(readAll(in, 4 << 20)));
                    } catch (Throwable t) {
                        b.append("(détail illisible : ").append(t).append(")\n");
                    }
                }
            }
            if (shown == 0) b.append("Aucun arrêt anormal récent.\n");
        } catch (Throwable t) {
            b.append("(historique indisponible : ").append(t).append(")\n");
        }
    }

    private static String reason(int r) {
        switch (r) {
            case ApplicationExitInfo.REASON_CRASH:
                return "plantage Java";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "plantage natif";
            case ApplicationExitInfo.REASON_ANR:
                return "ne répondait plus";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "tué par un signal";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return "démarrage impossible";
            case ApplicationExitInfo.REASON_EXIT_SELF:
                return "arrêt sur erreur";
            default:
                return "raison " + r;
        }
    }

    private static byte[] readAll(InputStream in, int max) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < max) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String tail(File f, int max) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            long from = Math.max(0, len - max);
            byte[] b = new byte[(int) (len - from)];
            r.seek(from);
            r.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(illisible)";
        }
    }

    /**
     * Reads the crash report Android makes for a native crash (tombstone,
     * a protocol buffer): signal, abort message, the crashing thread's
     * backtrace, and the last log lines of the process.
     */
    static final class Tombstone {
        private final byte[] d;
        private int p;

        private Tombstone(byte[] d) {
            this.d = d;
        }

        static String summary(byte[] data) {
            StringBuilder b = new StringBuilder();
            try {
                Msg t = parse(data, 0, data.length);
                int tid = (int) t.num(6);
                Msg sig = t.msg(10);
                if (sig != null) {
                    b.append("Signal ").append(sig.str(2)).append(' ').append(sig.str(4));
                    if (sig.num(8) != 0) b.append(" adresse 0x").append(Long.toHexString(sig.num(9)));
                    b.append('\n');
                }
                String abort = t.str(14);
                if (!abort.isEmpty()) b.append("Message : ").append(abort).append('\n');
                for (Msg cause : t.msgs(15)) {
                    b.append("Cause : ").append(cause.str(1)).append('\n');
                }
                for (Msg entry : t.msgs(16)) { // map<uint32, Thread>
                    Msg th = entry.msg(2);
                    if (th == null || th.num(1) != tid) continue;
                    b.append("Fil ").append(th.str(2)).append(" :\n");
                    int n = 0;
                    for (Msg fr : th.msgs(4)) {
                        if (n++ >= 14) break;
                        String file = fr.str(6);
                        b.append("  #").append(n - 1).append(" ").append(file.substring(file.lastIndexOf('/') + 1))
                                .append(" +0x").append(Long.toHexString(fr.num(1)));
                        String fn = fr.str(4);
                        if (!fn.isEmpty()) b.append(" (").append(fn).append(")");
                        b.append('\n');
                    }
                }
                List<String> logs = new ArrayList<>();
                for (Msg buf : t.msgs(18)) {
                    for (Msg l : buf.msgs(2)) {
                        logs.add(l.str(5) + ": " + l.str(6));
                    }
                }
                if (!logs.isEmpty()) {
                    b.append("Journal :\n");
                    for (String l : logs.subList(Math.max(0, logs.size() - 25), logs.size())) {
                        b.append("  ").append(l.length() > 300 ? l.substring(0, 300) : l).append('\n');
                    }
                }
            } catch (Throwable e) {
                b.append("(rapport illisible : ").append(e).append(")\n");
            }
            return b.toString();
        }

        /** Fields of one message: numbers and byte ranges, by field number. */
        static final class Msg {
            final byte[] d;
            final List<int[]> fields = new ArrayList<>(); // {number, wireType, start, end}
            final List<Long> values = new ArrayList<>();

            Msg(byte[] d) {
                this.d = d;
            }

            long num(int f) {
                for (int i = 0; i < fields.size(); i++) {
                    if (fields.get(i)[0] == f && fields.get(i)[1] != 2) return values.get(i);
                }
                return 0;
            }

            String str(int f) {
                for (int[] x : fields) {
                    if (x[0] == f && x[1] == 2) return new String(d, x[2], x[3] - x[2], StandardCharsets.UTF_8);
                }
                return "";
            }

            Msg msg(int f) {
                for (int[] x : fields) {
                    if (x[0] == f && x[1] == 2) return parse(d, x[2], x[3]);
                }
                return null;
            }

            List<Msg> msgs(int f) {
                List<Msg> out = new ArrayList<>();
                for (int[] x : fields) {
                    if (x[0] == f && x[1] == 2) out.add(parse(d, x[2], x[3]));
                }
                return out;
            }
        }

        static Msg parse(byte[] d, int start, int end) {
            Tombstone r = new Tombstone(d);
            r.p = start;
            Msg m = new Msg(d);
            while (r.p < end) {
                long key = r.varint();
                int f = (int) (key >>> 3), wt = (int) (key & 7);
                switch (wt) {
                    case 0:
                        m.fields.add(new int[]{f, 0, 0, 0});
                        m.values.add(r.varint());
                        break;
                    case 1: {
                        long v = 0;
                        for (int i = 0; i < 8; i++) v |= (d[r.p + i] & 0xffL) << (8 * i);
                        r.p += 8;
                        m.fields.add(new int[]{f, 1, 0, 0});
                        m.values.add(v);
                        break;
                    }
                    case 2: {
                        int len = (int) r.varint();
                        if (len < 0 || r.p + len > end) throw new IllegalStateException("bad length");
                        m.fields.add(new int[]{f, 2, r.p, r.p + len});
                        m.values.add(0L);
                        r.p += len;
                        break;
                    }
                    case 5: {
                        long v = 0;
                        for (int i = 0; i < 4; i++) v |= (d[r.p + i] & 0xffL) << (8 * i);
                        r.p += 4;
                        m.fields.add(new int[]{f, 5, 0, 0});
                        m.values.add(v);
                        break;
                    }
                    default:
                        throw new IllegalStateException("wire type " + wt);
                }
            }
            return m;
        }

        private long varint() {
            long v = 0;
            for (int s = 0; s < 64; s += 7) {
                byte b = d[p++];
                v |= (long) (b & 0x7f) << s;
                if ((b & 0x80) == 0) return v;
            }
            throw new IllegalStateException("bad varint");
        }
    }
}
