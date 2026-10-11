package com.limelight;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.limelight.nvstream.http.ComputerDetails;

import java.net.InetAddress;

/**
 * Small network helpers used by Moonlight's code (see apply_eclipse.py):
 * Wi-Fi or 4G, the phone finds and streams from the PC by itself.
 */
public final class EclipseNet {
    private EclipseNet() {
    }

    /** The phone is on a local network (Wi-Fi, Ethernet) or a VPN. */
    @SuppressWarnings("deprecation")
    public static boolean hasLan(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network[] all = cm.getAllNetworks();
            if (all.length == 0) return true; // unknown: do not skip anything
            for (Network n : all) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        || nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || nc.hasTransport(NetworkCapabilities.TRANSPORT_USB)
                        || nc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /** 10.x, 172.16-31.x, 192.168.x, 169.254.x, fc00::/7, fe80::/10 (literal addresses only). */
    public static boolean isPrivate(String address) {
        if (address == null || address.isEmpty()) return false;
        char ch = address.charAt(0);
        if (!(Character.isDigit(ch) || address.indexOf(':') >= 0)) return false; // a name: never resolved here
        try {
            InetAddress a = InetAddress.getByName(address);
            if (a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
            byte[] b = a.getAddress();
            return b.length == 16 && (b[0] & 0xfe) == 0xfc;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The PC's home address, or null when the phone cannot reach a home
     * network right now (4G only): Moonlight then does not wait 3 seconds
     * for it at each check, and finds the PC at once through Tailscale.
     */
    public static ComputerDetails.AddressTuple lanAddress(Context c, ComputerDetails.AddressTuple a) {
        if (a != null && isPrivate(a.address) && !hasLan(c)) return null;
        return a;
    }

    /** The stream goes through Eclipse's built-in Tailscale (relay on 127.x). */
    public static boolean viaRelay(String host) {
        return host != null && host.startsWith("127.");
    }

    /** Bit rate used through Tailscale on 4G when the person kept the default one. */
    static final int MOBILE_BITRATE_KBPS = 10000;

    /**
     * Video bit rate of a stream: on 4G through Tailscale, the default bit
     * rate is lowered (a mobile network rarely carries more steadily, and a
     * stream above what it carries stutters and lags). A bit rate chosen
     * in the settings is always kept.
     */
    public static int bitrate(Context c, String host, int bitrate) {
        try {
            if (!viaRelay(host) || hasLan(c)) return bitrate;
            // A bit rate chosen by the person is kept (Moonlight also saves
            // the default one when the resolution changes: that one is not).
            if (bitrate != com.limelight.preferences.PreferenceConfiguration.getDefaultBitrate(c)) return bitrate;
            int out = Math.min(bitrate, MOBILE_BITRATE_KBPS);
            if (out != bitrate) LimeLog.info("Eclipse: 4G stream, bit rate " + bitrate + " -> " + out + " kbps");
            return out;
        } catch (Exception e) {
            return bitrate;
        }
    }
}
