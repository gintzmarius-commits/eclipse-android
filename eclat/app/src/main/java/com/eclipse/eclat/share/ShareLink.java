package com.eclipse.eclat.share;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The link one phone gives another over NFC:
 * {@code http://<ip>:<port>/eclat/<token>?n=<name>&s=<size>&m=<mime>&d=<device>&a=<other ips>}
 *
 * An Android phone with Éclat reads it and shows the "Accept" island; an
 * iPhone reads it too (background tag reading) and opens it in Safari, where
 * the sending phone serves a download page. Pure Java: tested on the PC.
 */
public final class ShareLink {
    public static final String PATH = "/eclat/";

    public final String host;
    public final int port;
    public final String token;
    public final String name;
    public final long size;
    public final String mime;
    public final String device;
    /** Other addresses of the sending phone (another network interface). */
    public final List<String> otherHosts;

    public ShareLink(String host, int port, String token, String name, long size, String mime,
                     String device, List<String> otherHosts) {
        this.host = host;
        this.port = port;
        this.token = token;
        this.name = name == null ? "fichier" : name;
        this.size = size;
        this.mime = mime == null ? "application/octet-stream" : mime;
        this.device = device == null ? "" : device;
        this.otherHosts = otherHosts == null ? new ArrayList<String>() : otherHosts;
    }

    /** Every address to try, the main one first. */
    public List<String> hosts() {
        List<String> all = new ArrayList<>();
        all.add(host);
        for (String h : otherHosts) {
            if (!all.contains(h)) all.add(h);
        }
        return all;
    }

    public String base(String h) {
        return "http://" + (h.indexOf(':') >= 0 ? "[" + h + "]" : h) + ":" + port + PATH + token;
    }

    public String toUri() {
        StringBuilder b = new StringBuilder(base(host));
        b.append("?n=").append(enc(name));
        b.append("&s=").append(size);
        b.append("&m=").append(enc(mime));
        if (!device.isEmpty()) b.append("&d=").append(enc(device));
        if (!otherHosts.isEmpty()) b.append("&a=").append(enc(join(otherHosts)));
        return b.toString();
    }

    /** Reads a link; null if it is not an Éclat link. */
    public static ShareLink parse(String uri) {
        if (uri == null || !uri.startsWith("http://")) return null;
        try {
            String rest = uri.substring("http://".length());
            int slash = rest.indexOf('/');
            if (slash < 0) return null;
            String authority = rest.substring(0, slash);
            String pathAndQuery = rest.substring(slash);
            String host;
            int port;
            if (authority.startsWith("[")) {
                int end = authority.indexOf(']');
                host = authority.substring(1, end);
                port = Integer.parseInt(authority.substring(end + 2));
            } else {
                int colon = authority.lastIndexOf(':');
                if (colon < 0) return null;
                host = authority.substring(0, colon);
                port = Integer.parseInt(authority.substring(colon + 1));
            }
            if (!pathAndQuery.startsWith(PATH)) return null;
            int q = pathAndQuery.indexOf('?');
            String token = pathAndQuery.substring(PATH.length(), q < 0 ? pathAndQuery.length() : q);
            if (token.isEmpty() || token.contains("/")) return null;
            Map<String, String> p = new LinkedHashMap<>();
            if (q >= 0) {
                for (String kv : pathAndQuery.substring(q + 1).split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) p.put(kv.substring(0, eq), dec(kv.substring(eq + 1)));
                }
            }
            long size = -1;
            try {
                size = Long.parseLong(p.get("s"));
            } catch (Exception ignored) {
            }
            List<String> others = new ArrayList<>();
            String a = p.get("a");
            if (a != null) {
                for (String h : a.split(",")) {
                    if (!h.trim().isEmpty()) others.add(h.trim());
                }
            }
            return new ShareLink(host, port, token, p.get("n"), size, p.get("m"), p.get("d"), others);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) {
            if (b.length() > 0) b.append(',');
            b.append(s);
        }
        return b.toString();
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }
}
