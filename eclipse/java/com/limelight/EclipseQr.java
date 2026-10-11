package com.limelight;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The QR code shown by Eclipse on the PC ("Connecter un appareil"):
 *
 *   eclipse://pair?v=1&n=<PC name>&a=<address>,<address>&port=47989&id=<PC id>&t=<token>&p=<PIN>
 *
 * The phone pairs with Sunshine using the PIN, and puts the token in the
 * name it gives to the PC ("Eclipse.<token>.<phone name>"): this is how
 * Eclipse on the PC recognizes the request and answers it by itself.
 *
 * Plain Java (no Android classes) so it can be tested on its own.
 */
public final class EclipseQr {
    public static final int DEFAULT_PORT = 47989;
    public static final String APP_URL_PART = "eclipse-android";

    public final String name;
    public final List<String> addresses;
    public final int port;
    public final String id;
    public final String token;
    public final String pin;
    /** Tailscale address of the PC, to play away from home ("" if none). */
    public final String remote;
    /** Tailscale account of the PC ("" if unknown). */
    public final String remoteAccount;

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]{6,32}");
    private static final Pattern PIN = Pattern.compile("[0-9]{4}");
    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9.\\-]{1,253}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9\\-]{1,64}");

    /** Why a scanned code cannot be used. */
    public static final class Invalid extends Exception {
        /** "not-eclipse", "app-link", "newer" or "damaged". */
        public final String reason;

        Invalid(String reason) {
            super(reason);
            this.reason = reason;
        }
    }

    private EclipseQr(String name, List<String> addresses, int port, String id, String token, String pin,
                      String remote, String remoteAccount) {
        this.name = name;
        this.addresses = Collections.unmodifiableList(addresses);
        this.port = port;
        this.id = id;
        this.token = token;
        this.pin = pin;
        this.remote = remote;
        this.remoteAccount = remoteAccount;
    }

    public static EclipseQr parse(String raw) throws Invalid {
        if (raw == null) {
            throw new Invalid("not-eclipse");
        }
        String text = raw.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("eclipse://pair")) {
            if ((lower.startsWith("https://") || lower.startsWith("http://")) && lower.contains(APP_URL_PART)) {
                throw new Invalid("app-link");
            }
            throw new Invalid("not-eclipse");
        }
        int q = text.indexOf('?');
        if (q < 0 || !text.substring("eclipse://pair".length(), q).replace("/", "").isEmpty()) {
            throw new Invalid("damaged");
        }

        Map<String, String> args = new HashMap<>();
        for (String part : text.substring(q + 1).split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = decode(part.substring(0, eq));
            if (!args.containsKey(k)) {
                args.put(k, decode(part.substring(eq + 1)));
            }
        }

        String v = args.get("v");
        if (v != null && !v.equals("1")) {
            try {
                if (Integer.parseInt(v.trim()) > 1) {
                    throw new Invalid("newer");
                }
            } catch (NumberFormatException e) {
                throw new Invalid("damaged");
            }
        }

        String token = args.get("t");
        String pin = args.get("p");
        if (token == null || !TOKEN.matcher(token).matches() || pin == null || !PIN.matcher(pin).matches()) {
            throw new Invalid("damaged");
        }

        List<String> addresses = new ArrayList<>();
        String a = args.get("a");
        if (a != null) {
            for (String addr : a.split(",")) {
                addr = addr.trim();
                if (ADDRESS.matcher(addr).matches() && !addresses.contains(addr) && addresses.size() < 8) {
                    addresses.add(addr);
                }
            }
        }
        if (addresses.isEmpty()) {
            throw new Invalid("damaged");
        }

        int port = DEFAULT_PORT;
        String p = args.get("port");
        if (p != null && !p.isEmpty()) {
            try {
                port = Integer.parseInt(p.trim());
            } catch (NumberFormatException e) {
                throw new Invalid("damaged");
            }
            if (port < 1 || port > 65535) {
                throw new Invalid("damaged");
            }
        }

        String id = args.get("id");
        if (id == null || !ID.matcher(id).matches()) {
            id = "";
        }
        String name = args.get("n");
        name = name == null ? "" : cleanName(name, 64);
        String remote = args.get("ts");
        if (remote == null || !ADDRESS.matcher(remote).matches()) {
            remote = "";
        }
        String account = args.get("tu");
        account = account == null ? "" : printable(account, 128);
        return new EclipseQr(name, addresses, port, id, token, pin, remote, account);
    }

    private static String decode(String s) throws Invalid {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            throw new Invalid("damaged");
        }
    }

    private static String printable(String s, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < max; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 0x20 && cp != 0x7f && cp != '<' && cp != '>') {
                b.appendCodePoint(cp);
            }
        }
        return b.toString().trim();
    }

    /** Name given to the PC when pairing; token is "" without a QR code. */
    public static String deviceName(String token, String phoneName) {
        String n = cleanName(phoneName == null ? "" : phoneName, 40);
        if (n.isEmpty()) {
            n = "Téléphone";
        }
        return "Eclipse." + (token == null ? "" : token) + "." + n;
    }

    /**
     * Keeps letters, digits, spaces and - _ ' ( ) . so the name travels
     * safely in the pairing request; at most max characters.
     */
    public static String cleanName(String s, int max) {
        StringBuilder b = new StringBuilder();
        boolean space = true;
        int count = 0;
        for (int i = 0; i < s.length() && count < max; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            boolean keep = Character.isLetterOrDigit(cp) || "-_'().".indexOf(cp) >= 0;
            if (!keep) {
                if (!space) {
                    b.append(' ');
                    count++;
                    space = true;
                }
                continue;
            }
            b.appendCodePoint(cp);
            count++;
            space = false;
        }
        return b.toString().trim();
    }
}
