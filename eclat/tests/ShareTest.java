import com.eclipse.eclat.share.Fetch;
import com.eclipse.eclat.share.Ndef;
import com.eclipse.eclat.share.ShareLink;
import com.eclipse.eclat.share.ShareServer;
import com.eclipse.eclat.share.Type4Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests of Éclat's NFC sharing that need no phone: the link, the NDEF
 * message, the emulated NFC tag (read like Android and iOS read it), and a
 * real transfer between the sender's server and the receiver's code.
 */
public class ShareTest {
    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    static byte[] hex(String s) {
        s = s.replace(" ", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    static boolean ok(byte[] r) {
        return r.length >= 2 && r[r.length - 2] == (byte) 0x90 && r[r.length - 1] == 0;
    }

    /** Reads the tag the way a phone does (NFC Forum T4T 2.0 procedure). */
    static byte[] readTag(Type4Tag tag) {
        if (!ok(tag.process(hex("00A4040007D276000085010100")))) return null;
        if (!ok(tag.process(hex("00A4000C02E103")))) return null;
        byte[] cc = tag.process(hex("00B000000F"));
        if (!ok(cc) || cc.length != 17) return null;
        int mle = ((cc[3] & 0xFF) << 8) | (cc[4] & 0xFF);
        if (!ok(tag.process(hex("00A4000C02E104")))) return null;
        byte[] nlen = tag.process(hex("00B0000002"));
        int len = ((nlen[0] & 0xFF) << 8) | (nlen[1] & 0xFF);
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        int off = 2;
        while (msg.size() < len) {
            int n = Math.min(mle, len - msg.size());
            byte[] r = tag.process(new byte[]{0, (byte) 0xB0, (byte) (off >> 8), (byte) off, (byte) n});
            if (!ok(r)) return null;
            msg.write(r, 0, r.length - 2);
            off += r.length - 2;
        }
        return msg.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        // 1) The link
        List<String> others = new ArrayList<>(Arrays.asList("10.0.0.5"));
        ShareLink link = new ShareLink("192.168.1.20", 41234, "AbC_d-123", "Vacances à Nice (2).jpg", 123456,
                "image/jpeg", "Pixel de Marius & co", others);
        String uri = link.toUri();
        System.out.println("     " + uri);
        ShareLink back = ShareLink.parse(uri);
        check(back != null, "le lien se relit");
        check(back.host.equals("192.168.1.20") && back.port == 41234 && back.token.equals("AbC_d-123"), "adresse, port, jeton");
        check(back.name.equals("Vacances à Nice (2).jpg"), "nom avec accents et espaces");
        check(back.size == 123456 && back.mime.equals("image/jpeg"), "taille et type");
        check(back.device.equals("Pixel de Marius & co"), "nom de l'appareil");
        check(back.hosts().equals(Arrays.asList("192.168.1.20", "10.0.0.5")), "autres adresses");
        check(!uri.contains(" "), "pas d'espace dans le lien (iPhone)");
        check(ShareLink.parse("http://example.com/autre/chose") == null, "un autre lien n'est pas pris");
        check(ShareLink.parse("https://192.168.1.2:80/eclat/x") == null, "https refusé");

        // 2) NDEF message
        byte[] msg = Ndef.shareMessage(link, "DIRECT-ab", "motdepasse", "com.eclipse.eclat");
        List<Ndef.Record> recs = Ndef.decode(msg);
        check(recs.size() == 3, "3 enregistrements");
        check(uri.equals(recs.get(0).uri()), "1er enregistrement : le lien (iPhone et Android)");
        check(recs.get(0).payload[0] == 3, "préfixe http:// abrégé");
        check(recs.get(1).typeString().equals(Ndef.WIFI_MIME)
                && new String(recs.get(1).payload, "UTF-8").equals("DIRECT-ab\nmotdepasse"), "Wi-Fi du point d'accès");
        check(recs.get(2).tnf == Ndef.TNF_EXTERNAL && recs.get(2).typeString().equals("android.com:pkg")
                && new String(recs.get(2).payload, "UTF-8").equals("com.eclipse.eclat"), "Android Application Record en dernier");
        check((msg[0] & 0x80) != 0 && (msg[0] & 0x40) == 0, "MB sur le premier");
        byte[] noWifi = Ndef.shareMessage(link, null, null, "com.eclipse.eclat");
        check(Ndef.decode(noWifi).size() == 2, "sans point d'accès : 2 enregistrements");
        // long record (> 255 bytes)
        byte[] big = new byte[700];
        Arrays.fill(big, (byte) 'x');
        List<Ndef.Record> l = new ArrayList<>();
        l.add(Ndef.mimeRecord("a/b", big));
        check(Ndef.decode(Ndef.encode(l)).get(0).payload.length == 700, "enregistrement long");

        // 3) Emulated tag
        Type4Tag tag = new Type4Tag(msg);
        check(!tag.messageRead(), "pas encore lu");
        byte[] read = readTag(tag);
        check(read != null && Arrays.equals(read, msg), "le tag se lit comme un vrai tag NFC");
        check(tag.messageRead(), "lecture complète détectée");
        Type4Tag other = new Type4Tag(msg);
        check(!ok(other.process(hex("00A4040007A000000003101000"))), "une autre appli (paiement) : refusé");
        check(!ok(other.process(hex("00A4000C02E103"))), "fichier sans appli sélectionnée : refusé");
        check(!ok(other.process(hex("00B0000002"))), "lecture sans sélection : refusée");
        other.process(hex("00A4040007D2760000850101"));          // no Le byte
        check(ok(other.process(hex("00A4000002E104"))), "sélection avec P2=00 acceptée");
        check(!ok(other.process(hex("00D60000020000"))), "écriture refusée");
        // big message read in several pieces
        List<Ndef.Record> bl = new ArrayList<>();
        bl.add(Ndef.uriRecord(uri));
        bl.add(Ndef.mimeRecord("a/b", big));
        byte[] bigMsg = Ndef.encode(bl);
        check(Arrays.equals(readTag(new Type4Tag(bigMsg)), bigMsg), "message de 700+ octets lu en morceaux");

        // 4) A real transfer over the network
        final byte[] content = new byte[3 * 1024 * 1024 + 17];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i * 31 + 7);
        final AtomicReference<String> asked = new AtomicReference<>(), sent = new AtomicReference<>(), declined = new AtomicReference<>();
        ShareServer.Source src = new ShareServer.Source() {
            public String name() { return "Été 2026.mp4"; }
            public String mime() { return "video/mp4"; }
            public long size() { return content.length; }
            public InputStream open() { return new ByteArrayInputStream(content); }
            public byte[] thumbnail() { return new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2}; }
        };
        String token = "tok123";
        ShareServer server = new ShareServer(src, token, new ShareServer.Listener() {
            public void onAsked(String who, boolean eclat) { asked.set(who + "|" + eclat); }
            public void onDeclined(String who) { declined.set(who); }
            public void onProgress(long s, long t) { }
            public void onSent(String who) { sent.set(who); }
        });
        int port = server.start();
        ShareLink l2 = new ShareLink("10.255.255.1", port, token, src.name(), src.size(), src.mime(), "Test",
                new ArrayList<>(Arrays.asList("127.0.0.1")));
        String host = Fetch.findHost(Fetch.DEFAULT, l2, "Galaxy de Léa", 700);
        check("127.0.0.1".equals(host), "adresse injoignable sautée, la bonne trouvée");
        check("Galaxy de Léa|true".equals(asked.get()), "l'envoyeur sait qui demande");
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        long n = Fetch.download(Fetch.DEFAULT, l2, host, "Galaxy de Léa", got, (g, t) -> true);
        check(n == content.length && Arrays.equals(got.toByteArray(), content), "fichier de 3 Mo reçu à l'identique");
        Thread.sleep(100);
        check("Galaxy de Léa".equals(sent.get()), "l'envoyeur sait que c'est envoyé (" + sent.get() + ")");
        byte[] page = Fetch.get(Fetch.DEFAULT, l2.base(host), 2000, 1 << 20);
        String html = page == null ? "" : new String(page, "UTF-8");
        check(html.contains("Été 2026.mp4") && html.contains("/eclat/tok123/file"), "page pour iPhone");
        check(Arrays.equals(Fetch.get(Fetch.DEFAULT, l2.base(host) + "/thumb", 2000, 100), src.thumbnail()), "miniature");
        check(Fetch.get(Fetch.DEFAULT, "http://127.0.0.1:" + port + "/eclat/mauvais/file", 2000, 100) == null, "mauvais jeton : rien");
        // Range (Safari plays videos with ranges)
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(l2.base(host) + "/view").openConnection();
        c.setRequestProperty("Range", "bytes=100-199");
        check(c.getResponseCode() == 206, "Range : 206");
        byte[] part = c.getInputStream().readAllBytes();
        check(Arrays.equals(part, Arrays.copyOfRange(content, 100, 200)), "Range : les bons octets");
        check(String.valueOf(c.getHeaderField("Content-Disposition")).contains("filename*=UTF-8''%C3%89t%C3%A9%202026.mp4"), "nom de fichier UTF-8");
        check(Fetch.post(Fetch.DEFAULT, l2.base(host) + "/decline", "Galaxy de Léa", 2000) && "Galaxy de Léa".equals(declined.get()), "refus transmis");
        server.stop();
        check(Fetch.findHost(Fetch.DEFAULT, l2, "x", 500) == null, "serveur arrêté : plus rien");

        check(ShareServer.humanSize(1536).equals("1,5 Ko") && ShareServer.humanSize(5L << 30).equals("5,0 Go"), "tailles lisibles");

        System.out.println(failures == 0 ? "TOUS LES TESTS OK" : failures + " ÉCHEC(S)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
