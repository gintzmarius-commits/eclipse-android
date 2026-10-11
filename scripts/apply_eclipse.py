#!/usr/bin/env python3
"""Turn a Moonlight Android checkout into Eclipse.

Usage: apply_eclipse.py <moonlight-android checkout>

Everything Eclipse changes lives in this repository (this script and the
eclipse/ folder). The Moonlight source itself is fetched by the build at the
commit written in upstream.txt.
"""
import pathlib
import re
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
up = pathlib.Path(sys.argv[1]).resolve()
app = up / "app"
res = app / "src/main/res"
version = (ROOT / "VERSION").read_text().strip()
major, minor, patch = (int(x) for x in version.split("."))
version_code = major * 10000 + minor * 100 + patch


def edit(path, func):
    p = pathlib.Path(path)
    before = p.read_text(encoding="utf-8")
    after = func(before)
    if after == before:
        raise SystemExit(f"apply_eclipse: nothing changed in {p} (Moonlight source moved?)")
    p.write_text(after, encoding="utf-8")
    print(f"  edited {p.relative_to(up)}")


def must_sub(pattern, repl, text, count=0):
    new, n = re.subn(pattern, repl, text, count=count)
    if n == 0:
        raise SystemExit(f"apply_eclipse: pattern not found: {pattern}")
    return new


print(f"Eclipse {version} (code {version_code}) <- Moonlight in {up}")

# 1) Identity: our own application id (the Moonlight author asks every fork to
#    change it), name and version. Eclipse installs next to Moonlight.
def gradle(t):
    t = must_sub(r'applicationId "com\.limelight\.root"', 'applicationId "com.eclipse.stream.root"', t)
    t = must_sub(r'applicationId "com\.limelight"', 'applicationId "com.eclipse.stream"', t)
    t = must_sub(r'\n\s*applicationIdSuffix "\.unofficial"', "", t)
    t = must_sub(r'resValue "string", "app_label", "Moonlight \(Debug\)"', 'resValue "string", "app_label", "Eclipse (Debug)"', t)
    t = must_sub(r'resValue "string", "app_label_root", "Moonlight \(Root Debug\)"', 'resValue "string", "app_label_root", "Eclipse (Root Debug)"', t)
    t = must_sub(r'resValue "string", "app_label", "Moonlight"', 'resValue "string", "app_label", "Eclipse"', t)
    t = must_sub(r'resValue "string", "app_label_root", "Moonlight \(Root\)"', 'resValue "string", "app_label_root", "Eclipse (Root)"', t)
    t = must_sub(r'versionName "[^"]*"', f'versionName "{version}"', t, count=1)
    t = must_sub(r'versionCode = \d+', f'versionCode = {version_code}', t, count=1)
    # Google's QR code scanner needs Android 6 or newer.
    t = must_sub(r'minSdk 21\b', 'minSdk 23', t, count=1)
    # Signing: one stable key, so each new APK installs over the previous one.
    t += '''

// ---- Eclipse: release signing ----
android {
    signingConfigs {
        eclipse {
            storeFile file(System.getenv("ECLIPSE_KEYSTORE") ?: "eclipse.keystore")
            storePassword System.getenv("ECLIPSE_KEYSTORE_PASSWORD")
            keyAlias System.getenv("ECLIPSE_KEY_ALIAS") ?: "eclipse"
            keyPassword System.getenv("ECLIPSE_KEY_PASSWORD") ?: System.getenv("ECLIPSE_KEYSTORE_PASSWORD")
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.eclipse
        }
    }
}

// ---- Eclipse: QR code scanner (Google Play services, no camera permission) ----
dependencies {
    implementation 'com.google.android.gms:play-services-code-scanner:16.1.0'
    implementation 'com.google.android.gms:play-services-base:18.5.0'
    // Built-in Tailscale (tunnel/, built by scripts/build_tunnel.sh)
    implementation files('libs/eclipsenet.aar')
}

// ---- Eclipse: the built-in Tailscale ships for arm64 phones only ----
// (its x86_64 build exists for the tests on the Android emulator:
// gradlew -PeclipseEmulator=true)
android {
    packaging {
        jniLibs {
            if (!project.hasProperty("eclipseEmulator")) {
                excludes += ["lib/x86_64/libgojni.so", "lib/x86/libgojni.so", "lib/armeabi-v7a/libgojni.so"]
            }
        }
    }
}
'''
    return t

edit(app / "build.gradle", gradle)

# Google Play services libraries are AndroidX libraries.
def gradle_props(t):
    if "android.useAndroidX" in t:
        return t + "\n# Eclipse\n"
    return t.rstrip("\n") + "\n\n# Eclipse: Google's QR code scanner uses AndroidX\nandroid.useAndroidX=true\n"

edit(up / "gradle.properties", gradle_props)

# 2) Texts: say "Eclipse" where the app talks about itself (English + French).
def rename(t):
    t = re.sub(r"Moonlight(?! Internet Hosting Tool| Game Streaming)", "Eclipse", t)
    return t

for lang in ("values", "values-fr"):
    edit(res / lang / "strings.xml", rename)

# 3) Look: Eclipse gold for buttons, switches and highlights.
ACCENT = "#F6A51A"
def accent(t):
    return must_sub(r'(<style name="AppBaseTheme" parent="android:Theme\.Material">)',
                    r'\1\n        <item name="android:colorAccent">' + ACCENT + "</item>", t, count=1)

for v in ("values-v21", "values-v29"):
    edit(res / v / "styles.xml", accent)

# 4) Eclipse files: resources (icons, colours), the Eclipse home screen
#    (Java) and its Liquid Glass interface (assets).
for src_dir, dst_dir in (("eclipse/res", res),
                         ("eclipse/java", app / "src/main/java"),
                         ("eclipse/assets", app / "src/main/assets")):
    overlay = ROOT / src_dir
    for f in overlay.rglob("*"):
        if f.is_file():
            dst = dst_dir / f.relative_to(overlay)
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(f, dst)
            print(f"  copied {dst.relative_to(up)}")

# 4b) Pairing: the phone tells its real name to the PC (Moonlight sends
#     "roth"), with the token of the QR code when there is one; a pairing
#     waiting for the PC can be stopped.
http = app / "src/main/java/com/limelight/nvstream/http/NvHTTP.java"
def nvhttp(t):
    t = must_sub(r'"devicename=roth&', '"devicename=" + com.limelight.EclipsePairing.deviceName() + "&', t)
    if t.count("com.limelight.EclipsePairing.deviceName()") != 2:
        raise SystemExit("apply_eclipse: expected 2 pairing requests in NvHTTP")
    return must_sub(r'(\n    public HttpUrl getHttpsUrl\(boolean likelyOnline\))', r"""
    // Eclipse: stops the requests of this connection (a pairing waiting for the PC).
    public void eclipseCancel() {
        httpClientLongConnectTimeout.dispatcher().cancelAll();
    }
\1""", t, count=1)

edit(http, nvhttp)

#     Sunshine 2026.914 refuses for a moment a phone that pairs again (it
#     keeps two records of it until Eclipse on the PC removes the old one):
#     the last pairing step is retried for a few seconds.
pm = app / "src/main/java/com/limelight/nvstream/http/PairingManager.java"
edit(pm, lambda t: must_sub(
    r'(        // Do the initial challenge \(seems necessary for us to show as paired\)\n)        String pairChallenge = http\.executePairingChallenge\(\);\n',
    r"""\1        String pairChallenge;
        for (int eclipseTry = 0; ; eclipseTry++) {
            try {
                pairChallenge = http.executePairingChallenge();
                break;
            } catch (HostHttpResponseException e) {
                if (e.getErrorCode() != 401 || eclipseTry >= 6) {
                    throw e;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    throw new InterruptedIOException();
                }
            }
        }
""", t, count=1))

#     Through Eclipse's built-in Tailscale, the PC is reached on 127.0.0.1:
#     stream it as a remote PC (smaller packets, fit for the tunnel).
nvc = app / "src/main/java/com/limelight/nvstream/NvConnection.java"
edit(nvc, lambda t: must_sub(
    r'(    private int detectServerConnectionType\(\) \{\n)',
    r"""\1        // Eclipse: its built-in Tailscale relays the PC on 127.0.0.1 (remote play)
        if (context.serverAddress != null && context.serverAddress.address.startsWith("127.")) {
            return StreamConfiguration.STREAM_CFG_REMOTE;
        }
""", t, count=1))

#     On 4G (no Wi-Fi), the PC's home address cannot answer: Moonlight
#     skips it instead of waiting 3 seconds for it at every check, so a PC
#     reached through Tailscale shows up at once.
cms = app / "src/main/java/com/limelight/computers/ComputerManagerService.java"
edit(cms, lambda t: must_sub(
    r'ParallelPollTuple localInfo = new ParallelPollTuple\(details\.localAddress, details\);',
    'ParallelPollTuple localInfo = new ParallelPollTuple(com.limelight.EclipseNet.lanAddress(ComputerManagerService.this, details.localAddress), details);',
    t, count=1))

#     On 4G through Tailscale, the default video bit rate is lowered (see
#     EclipseNet.bitrate); a bit rate chosen in the settings is kept.
game = app / "src/main/java/com/limelight/Game.java"
edit(game, lambda t: must_sub(
    r'\.setBitrate\(prefConfig\.bitrate\)',
    '.setBitrate(com.limelight.EclipseNet.bitrate(Game.this, host, prefConfig.bitrate))',
    t, count=1))

# 5) The Eclipse screen opens the app; Moonlight's screen stays reachable
#    from Eclipse's settings ("Interface Moonlight classique").
ECLIPSE_ACTIVITY = """<activity
            android:name=".EclipseActivity"
            android:exported="true"
            android:resizeableActivity="true"
            android:enableOnBackInvokedCallback="false"
            android:launchMode="singleTop"
            android:theme="@style/EclipseTheme"
            android:configChanges="mcc|mnc|touchscreen|keyboard|keyboardHidden|navigation|screenLayout|fontScale|uiMode|orientation|screenSize|smallestScreenSize|layoutDirection">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />

                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="android.intent.category.MULTIWINDOW_LAUNCHER" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
            <!-- QR code of Eclipse for PC read by the camera app -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />

                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />

                <data android:scheme="eclipse" android:host="pair" />
            </intent-filter>
        </activity>
        <meta-data
            android:name="com.google.mlkit.vision.DEPENDENCIES"
            android:value="barcode_ui" />
        <!-- Built-in Tailscale, in its own process: if it ever fails,
             Eclipse itself stays open -->
        <provider
            android:name=".EclipseTunnelProvider"
            android:authorities="${applicationId}.tunnel"
            android:process=":tunnel"
            android:exported="false" />
        """

def manifest(t):
    start = t.index('android:name=".PcView"')
    a = t.rindex("<activity", 0, start)
    end = t.index("</activity>", start)
    block = t[a:end]
    stripped = re.sub(r"\s*<intent-filter>.*?</intent-filter>", "", block, flags=re.S)
    if stripped == block:
        raise SystemExit("apply_eclipse: PcView launcher filter not found")
    t = t[:a] + ECLIPSE_ACTIVITY + stripped + t[end:]
    # Eclipse checks whether the Tailscale app is installed (Android 11+
    # only shows apps that are declared here).
    return must_sub(r"(\n    <application\b)", "\n    <queries>\n        <package android:name=\"com.tailscale.ipn\" />\n    </queries>\n\\1", t, count=1)

edit(app / "src/main/AndroidManifest.xml", manifest)

# 6) Keep the methods the page calls (R8 would remove them as unused).
edit(app / "proguard-rules.pro", lambda t: t + """
# Eclipse: methods called from the interface (window.EclipseNative)
-keepattributes JavascriptInterface
-keepclassmembers class com.limelight.EclipseActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Eclipse: Google's QR code scanner (ML Kit, Google Play services) creates
# some of its classes by reflection (the registrars listed in the manifest).
# With AGP 9, R8 removes their constructors unless told otherwise, and the
# scanner then fails ("getClass() on a null object reference"): keep it whole.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>(...);
    *;
}
-keep class com.google.firebase.components.** { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.common.moduleinstall.** { *; }
-keep class com.google.android.datatransport.** { *; }

# Eclipse: built-in Tailscale (Go library, called through JNI)
-keep class go.** { *; }
-keep class eclipsenet.** { *; }
""")

print("Eclipse changes applied.")
