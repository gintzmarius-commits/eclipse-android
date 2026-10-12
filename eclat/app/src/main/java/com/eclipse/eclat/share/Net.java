package com.eclipse.eclat.share;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.provider.Settings;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The phone's addresses on its local networks, and its name. */
public final class Net {
    private Net() {
    }

    /** IPv4 addresses of the local networks (Wi-Fi and hotspot first). */
    public static List<String> localAddresses() {
        List<String> wifi = new ArrayList<>();
        List<String> other = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                // mobile data (rmnet, ccmnet...) and VPNs are not reachable by another phone
                boolean local = n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")
                        || n.startsWith("p2p") || n.startsWith("eth") || n.startsWith("softap");
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    (local ? wifi : other).add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        // emulators and odd devices: keep the others last (never tun/rmnet first)
        for (String a : other) {
            if (!a.startsWith("100.") && !wifi.contains(a)) wifi.add(a);
        }
        return wifi;
    }

    /** True when the phone is on a Wi-Fi (or Ethernet) network. */
    public static boolean onWifi(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                return true;
            }
        }
        return false;
    }

    /** The name people gave their phone ("Pixel de Marius"), else its model. */
    public static String deviceName(Context c) {
        String n = null;
        try {
            n = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME);
        } catch (Exception ignored) {
        }
        if (n == null || n.trim().isEmpty()) {
            try {
                n = Settings.Secure.getString(c.getContentResolver(), "bluetooth_name");
            } catch (Exception ignored) {
            }
        }
        if (n == null || n.trim().isEmpty()) {
            String m = Build.MODEL;
            n = m.toLowerCase().startsWith(Build.MANUFACTURER.toLowerCase()) ? m : Build.MANUFACTURER + " " + m;
        }
        return n.trim();
    }
}
