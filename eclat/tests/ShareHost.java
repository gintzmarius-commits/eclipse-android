import com.eclipse.eclat.share.ShareLink;
import com.eclipse.eclat.share.ShareServer;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;

/**
 * Plays the sending phone on the computer that runs the Android emulator:
 * serves one file like Éclat does after an NFC tap, and prints what the
 * receiving phone did. Used by scripts/eclat_smoke.sh.
 *   java ShareHost <file> <mime> <seconds to stay> <host seen by the phone>
 * Prints the link (what the NFC tag would hold) on its first line.
 */
public class ShareHost {
    public static void main(String[] a) throws Exception {
        final String path = a[0], mime = a[1];
        final long size = Files.size(Paths.get(path));
        final String name = "Photo de test é.jpg";
        ShareServer.Source src = new ShareServer.Source() {
            public String name() { return name; }
            public String mime() { return mime; }
            public long size() { return size; }
            public InputStream open() throws java.io.IOException { return new FileInputStream(path); }
            public byte[] thumbnail() {
                try { return Files.readAllBytes(Paths.get(path)); } catch (Exception e) { return null; }
            }
        };
        ShareServer s = new ShareServer(src, "smoketoken", new ShareServer.Listener() {
            public void onAsked(String who, boolean eclat) { System.out.println("ASKED " + who + " eclat=" + eclat); }
            public void onDeclined(String who) { System.out.println("DECLINED " + who); }
            public void onProgress(long sent, long total) { }
            public void onSent(String who) { System.out.println("SENT " + who); }
        });
        // ShareServer binds a free port; the emulator reaches it through 10.0.2.2.
        int port = s.start();
        ShareLink link = new ShareLink(a[3], port, "smoketoken", name, size, mime, "PC de test", new ArrayList<String>());
        System.out.println(link.toUri());
        System.out.flush();
        Thread.sleep(Long.parseLong(a[2]) * 1000);
        s.stop();
    }
}
