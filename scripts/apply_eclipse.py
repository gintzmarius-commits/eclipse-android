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
'''
    return t

edit(app / "build.gradle", gradle)

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

# 5) The Eclipse screen opens the app; Moonlight's screen stays reachable
#    from Eclipse's settings ("Interface Moonlight classique").
ECLIPSE_ACTIVITY = """<activity
            android:name=".EclipseActivity"
            android:exported="true"
            android:resizeableActivity="true"
            android:enableOnBackInvokedCallback="false"
            android:theme="@style/EclipseTheme"
            android:configChanges="mcc|mnc|touchscreen|keyboard|keyboardHidden|navigation|screenLayout|fontScale|uiMode|orientation|screenSize|smallestScreenSize|layoutDirection">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />

                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="android.intent.category.MULTIWINDOW_LAUNCHER" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
        </activity>
        """

def manifest(t):
    start = t.index('android:name=".PcView"')
    a = t.rindex("<activity", 0, start)
    end = t.index("</activity>", start)
    block = t[a:end]
    stripped = re.sub(r"\s*<intent-filter>.*?</intent-filter>", "", block, flags=re.S)
    if stripped == block:
        raise SystemExit("apply_eclipse: PcView launcher filter not found")
    return t[:a] + ECLIPSE_ACTIVITY + stripped + t[end:]

edit(app / "src/main/AndroidManifest.xml", manifest)

# 6) Keep the methods the page calls (R8 would remove them as unused).
edit(app / "proguard-rules.pro", lambda t: t + """
# Eclipse: methods called from the interface (window.EclipseNative)
-keepattributes JavascriptInterface
-keepclassmembers class com.limelight.EclipseActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
""")

print("Eclipse changes applied.")
