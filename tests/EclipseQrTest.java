import com.limelight.EclipseQr;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * Checks the reading of the QR codes made by Eclipse on the PC, and the
 * name the phone gives to the PC. Run by the build before compiling the
 * app: javac -d out eclipse/java/com/limelight/EclipseQr.java tests/EclipseQrTest.java
 * && java -cp out EclipseQrTest [file of extra payloads]
 */
public class EclipseQrTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    static String reason(String raw) {
        try {
            EclipseQr.parse(raw);
            return "ok";
        } catch (EclipseQr.Invalid e) {
            return e.reason;
        }
    }

    public static void main(String[] args) throws Exception {
        // Real codes made by Eclipse 2.0 for Windows / Ubuntu
        EclipseQr q = EclipseQr.parse("eclipse://pair?a=172.218.104.225%2C10.47.248.89%2C10.120.219.98&id=DE7509DC-6D80-5E2F-7700-BF75D0205FCB&n=Lenovo+500w+Gen+5&p=0631&port=47989&t=qvfmkr3x&v=1");
        check(q.addresses.equals(Arrays.asList("172.218.104.225", "10.47.248.89", "10.120.219.98")), "addresses " + q.addresses);
        check(q.id.equals("DE7509DC-6D80-5E2F-7700-BF75D0205FCB"), "id");
        check(q.name.equals("Lenovo 500w Gen 5"), "name '" + q.name + "'");
        check(q.pin.equals("0631"), "pin keeps its leading zero");
        check(q.port == 47989, "port");
        check(q.token.equals("qvfmkr3x"), "token");

        q = EclipseQr.parse("eclipse://pair?a=192.181.184.173&n=Ordinateur+de+Zo%C3%A9&p=4547&port=47989&t=gq2pcdh3&v=1");
        check(q.name.equals("Ordinateur de Zoé"), "accents '" + q.name + "'");
        check(q.id.isEmpty(), "no id");
        check(q.addresses.size() == 1, "one address");

        q = EclipseQr.parse("  eclipse://pair?t=abcdefgh&p=1234&a=pc.local  \n");
        check(q.port == EclipseQr.DEFAULT_PORT && q.name.isEmpty() && q.addresses.get(0).equals("pc.local"), "defaults");

        // Tailscale (playing away from home)
        q = EclipseQr.parse("eclipse://pair?a=192.168.1.20&id=AB-12&n=PC&p=1234&port=47989&t=abcdefgh&ts=100.101.102.103&tu=marius%40gmail.com&v=1");
        check(q.remote.equals("100.101.102.103"), "tailscale address '" + q.remote + "'");
        check(q.remoteAccount.equals("marius@gmail.com"), "tailscale account '" + q.remoteAccount + "'");
        check(q.addresses.equals(Arrays.asList("192.168.1.20")), "tailscale not mixed with home addresses");
        q = EclipseQr.parse("eclipse://pair?a=192.168.1.20&p=1234&t=abcdefgh");
        check(q.remote.isEmpty() && q.remoteAccount.isEmpty(), "no tailscale");
        q = EclipseQr.parse("eclipse://pair?a=192.168.1.20&p=1234&t=abcdefgh&ts=1.2.3.4%26x%3D1&tu=%3Cb%3Ex");
        check(q.remote.isEmpty() && q.remoteAccount.equals("bx"), "unsafe tailscale values ignored");

        // The PC opens the phone's Tailscale sign-in page (Eclipse 2.3)
        q = EclipseQr.parse("eclipse://pair?a=192.168.1.20&id=AB-12&n=PC&p=1234&port=47989&t=abcdefgh&tl=1&ts=100.101.102.103&tu=marius%40gmail.com&v=1");
        check(q.pcLogin, "tl=1 read");
        check(!EclipseQr.parse("eclipse://pair?a=192.168.1.20&p=1234&t=abcdefgh&tl=1").pcLogin, "tl ignored without Tailscale address");
        check(!EclipseQr.parse("eclipse://pair?a=192.168.1.20&p=1234&t=abcdefgh&ts=100.1.2.3").pcLogin, "no tl: old PC");
        check(EclipseQr.deviceToken("abcdefgh", "https://login.tailscale.com/a/1a2b3c4d5e6f").equals("abcdefgh~1a2b3c4d5e6f"), "token with sign-in key");
        check(EclipseQr.deviceToken("abcdefgh", "https://evil.example/a/1a2b3c4d5e6f").equals("abcdefgh"), "other site refused");
        check(EclipseQr.deviceToken("abcdefgh", "https://login.tailscale.com/a/1a2b&x=.y").equals("abcdefgh"), "odd key refused");
        check(EclipseQr.deviceToken("abcdefgh", null).equals("abcdefgh") && EclipseQr.deviceToken("abcdefgh", "").equals("abcdefgh"), "no page");
        check(EclipseQr.deviceName(EclipseQr.deviceToken("abcdefgh", "https://login.tailscale.com/a/f00d1234"), "Pixel 7").equals("Eclipse.abcdefgh~f00d1234.Pixel 7"), "device name with key");

        // Codes that cannot be used, and why
        check(reason("https://github.com/gintzmarius-commits/eclipse-android/releases/latest/download/Eclipse.apk").equals("app-link"), "download link");
        check(reason("https://example.com").equals("not-eclipse"), "other link");
        check(reason("WIFI:S:maison;T:WPA;P:secret;;").equals("not-eclipse"), "wifi code");
        check(reason("").equals("not-eclipse"), "empty");
        check(reason(null).equals("not-eclipse"), "null");
        check(reason("eclipse://pair?v=2&t=abcdefgh&p=1234&a=1.2.3.4").equals("newer"), "newer format");
        check(reason("eclipse://pair?v=x&t=abcdefgh&p=1234&a=1.2.3.4").equals("damaged"), "bad version");
        check(reason("eclipse://pair?t=abcdefgh&p=123&a=1.2.3.4").equals("damaged"), "short pin");
        check(reason("eclipse://pair?t=ABC&p=1234&a=1.2.3.4").equals("damaged"), "bad token");
        check(reason("eclipse://pair?t=abcdefgh&p=1234").equals("damaged"), "no address");
        check(reason("eclipse://pair?t=abcdefgh&p=1234&a=1.2.3.4&port=99999").equals("damaged"), "bad port");
        check(reason("eclipse://pair?t=abcdefgh&p=1234&a=1.2.3.4&port=abc").equals("damaged"), "port not a number");
        check(reason("eclipse://pair?t=abcdefgh&p=1234&a=%ZZ").equals("damaged"), "bad escape");
        check(reason("eclipse://pairing?t=abcdefgh&p=1234&a=1.2.3.4").equals("damaged"), "other host");
        check(reason("eclipse://pair?t=abcdefgh&p=1234&a=1.2.3.4%26x%3Dy,5.6.7.8").equals("ok")
                && EclipseQr.parse("eclipse://pair?t=abcdefgh&p=1234&a=1.2.3.4%26x%3Dy,5.6.7.8").addresses.equals(Arrays.asList("5.6.7.8")), "unsafe address skipped");

        // Name sent to the PC
        check(EclipseQr.deviceName("k3f9xq2a", "Galaxy S23").equals("Eclipse.k3f9xq2a.Galaxy S23"), "device name");
        check(EclipseQr.deviceName("", "Pixel 7").equals("Eclipse..Pixel 7"), "device name without QR");
        check(EclipseQr.deviceName("", "  ").equals("Eclipse..Téléphone"), "empty phone name");
        check(EclipseQr.deviceName("t", "A&B=C#D+E%F\"G<H>").equals("Eclipse.t.A B C D E F G H"), "unsafe characters removed: " + EclipseQr.deviceName("t", "A&B=C#D+E%F\"G<H>"));
        check(EclipseQr.deviceName("t", "Téléphone de Zoé").equals("Eclipse.t.Téléphone de Zoé"), "accents kept");
        String longName = EclipseQr.deviceName("t", "abcdefghij abcdefghij abcdefghij abcdefghij abcdefghij");
        check(longName.length() <= "Eclipse.t.".length() + 40, "name length " + longName.length());
        check(EclipseQr.cleanName("😀 Phone\t\n(Pro)", 40).equals("😀 Phone (Pro)") || EclipseQr.cleanName("😀 Phone\t\n(Pro)", 40).equals("Phone (Pro)"), "symbols");

        // Extra payloads (generated by the PC app's own tests), one per line:
        // <payload>\t<anything>
        if (args.length > 0) {
            List<String> lines = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
            int n = 0;
            for (String line : lines) {
                String payload = line.split("\t", 2)[0];
                if (payload.startsWith("eclipse://")) {
                    EclipseQr x = EclipseQr.parse(payload);
                    check(x.pin.length() == 4 && x.token.length() == 8 && !x.addresses.isEmpty(), "payload " + payload);
                    System.out.println("PARSED\t" + payload + "\t" + x.name + "\t" + String.join(",", x.addresses) + "\t" + x.port + "\t" + x.id + "\t" + x.token + "\t" + x.pin);
                    n++;
                }
            }
            System.out.println(n + " codes du PC lus");
        }

        if (failures > 0) {
            System.out.println(failures + " test(s) en échec");
            System.exit(1);
        }
        System.out.println("EclipseQr : tous les tests sont bons");
    }
}
