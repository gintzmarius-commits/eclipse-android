#!/usr/bin/env python3
"""Checks the built Eclipse APK without the Android SDK (run by the build).

usage: check_apk.py Eclipse.apk VERSION [SHA-256 of the signing certificate]

- manifest: package, version, Eclipse screen, eclipse://pair links;
- every class Android or a library creates by reflection (components of the
  manifest, ML Kit / Firebase registrars, startup initializers…) is in the
  code with its constructor without parameters: R8 (AGP 9) removes such
  constructors when keep rules forget them, and the app then fails at run
  time (Eclipse 2.0.0: the QR code scanner did not open);
- the interface files and the signing key.
"""
import hashlib, re, struct, sys, zipfile

apk = sys.argv[1]
z = zipfile.ZipFile(apk)
names = z.namelist()
FAILS = []


def check(c, what):
    print(("  PASS " if c else "  FAIL ") + what)
    if not c:
        FAILS.append(what)


# ---------------- binary XML (AndroidManifest.xml) ----------------
def axml(data):
    def u16(o): return struct.unpack_from("<H", data, o)[0]
    def u32(o): return struct.unpack_from("<I", data, o)[0]
    strings = []
    resmap = []
    out = []
    pos = 8
    ns = {}
    while pos < len(data):
        ctype, hsize, csize = u16(pos), u16(pos + 2), u32(pos + 4)
        if ctype == 0x0001:  # string pool
            n, flags, sstart = u32(pos + 8), u32(pos + 16), u32(pos + 20)
            utf8 = flags & 0x100
            offs = [u32(pos + 28 + 4 * i) for i in range(n)]
            for o in offs:
                p = pos + sstart + o
                if utf8:
                    l = data[p]; p += 2 if l & 0x80 else 1
                    l = data[p]
                    if l & 0x80:
                        l = ((l & 0x7f) << 8) | data[p + 1]; p += 2
                    else:
                        p += 1
                    strings.append(data[p:p + l].decode("utf-8", "replace"))
                else:
                    l = u16(p); p += 2
                    if l & 0x8000:
                        l = ((l & 0x7fff) << 16) | u16(p); p += 2
                    strings.append(data[p:p + 2 * l].decode("utf-16le", "replace"))
        elif ctype == 0x0180:
            resmap = [u32(pos + 8 + 4 * i) for i in range((csize - 8) // 4)]
        elif ctype == 0x0102:  # start element
            name = strings[u32(pos + 20)]
            acount = u16(pos + 28)
            attrs = {}
            for i in range(acount):
                a = pos + 36 + 20 * i
                an = strings[u32(a + 4)]
                raw = u32(a + 8)
                dtype = data[a + 15]
                dval = u32(a + 16)
                if raw != 0xFFFFFFFF:
                    v = strings[raw]
                elif dtype == 0x03:
                    v = strings[dval]
                elif dtype == 0x12:
                    v = dval != 0
                elif dtype in (0x10, 0x11):
                    v = dval
                elif dtype == 0x01:
                    v = "@0x%08x" % dval
                else:
                    v = dval
                attrs[an] = v
            out.append(("start", name, attrs))
        elif ctype == 0x0103:
            out.append(("end", strings[u32(pos + 20)], {}))
        pos += csize
    return out


def uleb(b, p):
    r = s = 0
    while True:
        x = b[p]; p += 1
        r |= (x & 0x7f) << s
        if x < 0x80:
            return r, p
        s += 7


class Dex:
    def __init__(self, b):
        self.b = b
        u = lambda o: struct.unpack_from("<I", b, o)[0]
        self.str_n, self.str_off = u(56), u(60)
        self.type_n, self.type_off = u(64), u(68)
        self.proto_n, self.proto_off = u(72), u(76)
        self.field_n, self.field_off = u(80), u(84)
        self.meth_n, self.meth_off = u(88), u(92)
        self.cls_n, self.cls_off = u(96), u(100)
        self._s = {}

    def s(self, i):
        if i in self._s:
            return self._s[i]
        off = struct.unpack_from("<I", self.b, self.str_off + 4 * i)[0]
        _, p = uleb(self.b, off)
        e = self.b.index(b"\0", p)
        v = self.b[p:e].decode("utf-8", "replace")
        self._s[i] = v
        return v

    def t(self, i):
        return self.s(struct.unpack_from("<I", self.b, self.type_off + 4 * i)[0])

    def proto(self, i):
        o = self.proto_off + 12 * i
        _, ret, params = struct.unpack_from("<III", self.b, o)
        ps = []
        if params:
            n = struct.unpack_from("<I", self.b, params)[0]
            ps = [self.t(struct.unpack_from("<H", self.b, params + 4 + 2 * k)[0]) for k in range(n)]
        return "(" + "".join(ps) + ")" + self.t(ret)

    def method(self, i):
        o = self.meth_off + 8 * i
        cls, proto, name = struct.unpack_from("<HHI", self.b, o)
        return self.t(cls), self.s(name), self.proto(proto)

    def field(self, i):
        o = self.field_off + 8 * i
        cls, typ, name = struct.unpack_from("<HHI", self.b, o)
        return self.s(name), self.t(typ)

    def classes(self):
        for k in range(self.cls_n):
            o = self.cls_off + 32 * k
            cidx, flags, sup, ifs, src, ann, data, sv = struct.unpack_from("<IIIIIIII", self.b, o)
            yield self.t(cidx), flags, (self.t(sup) if sup != 0xFFFFFFFF else None), ifs, data

    def members(self, data):
        if not data:
            return [], []
        b = self.b
        p = data
        sf, p = uleb(b, p); inf, p = uleb(b, p); dm, p = uleb(b, p); vm, p = uleb(b, p)
        fields = []
        idx = 0
        for _ in range(sf + inf):
            d, p = uleb(b, p); fl, p = uleb(b, p); idx += d
            if _ == sf - 1 or _ == sf:
                pass
            fields.append(self.field(idx))
            if _ == sf - 1:
                idx = 0
        meths = []
        idx = 0
        for k in range(dm + vm):
            if k == dm:
                idx = 0
            d, p = uleb(b, p); fl, p = uleb(b, p); code, p = uleb(b, p); idx += d
            c, n, pr = self.method(idx)
            meths.append((n + pr, "direct" if k < dm else "virtual", fl))
        return fields, meths



dexes = [Dex(z.read(n)) for n in names if re.fullmatch(r"classes\d*\.dex", n)]
methods = {}
for d in dexes:
    for cname, flags, sup, ifs, data in d.classes():
        f, m = d.members(data)
        methods[cname] = [x[0] for x in m]


def jtype(c):
    return "L" + c.replace(".", "/") + ";"


ev = axml(z.read("AndroidManifest.xml"))
man = next(a for t, n, a in ev if t == "start" and n == "manifest")
pkg = man.get("package")
print("== Manifeste")
print("   ", pkg, man.get("versionName"), man.get("versionCode"))
check(pkg == "com.eclipse.stream", "identifiant com.eclipse.stream")
if len(sys.argv) > 2:
    check(man.get("versionName") == sys.argv[2], "version " + str(man.get("versionName")))

# Classes created by reflection, from the manifest
reflect = []
for t, n, a in ev:
    if t != "start":
        continue
    if n in ("activity", "service", "provider", "receiver", "activity-alias", "application") and a.get("name"):
        nm = a.get("targetActivity") or a["name"]
        if n == "application" and nm.startswith("android."):
            continue
        reflect.append((n, nm[1:] if nm.startswith(".") else nm, n == "application" and not a.get("name")))
    if n == "meta-data":
        k, v = a.get("name") or "", a.get("value")
        if k.startswith("com.google.firebase.components:"):
            reflect.append(("registrar", k.split(":", 1)[1], False))
        elif k.startswith("backend:"):
            reflect.append(("backend", k.split(":", 1)[1], False))
        elif v == "androidx.startup":
            reflect.append(("initializer", k, False))

print("== Classes créées par réflexion :", len(reflect))
bad = []
for kind, cls, _ in reflect:
    if not cls.startswith(("com.", "org.", "androidx.", "okhttp3.")):
        cls = pkg + "." + cls if "." not in cls else cls
    jt = jtype(cls)
    if jt not in methods:
        bad.append(f"{kind} {cls} absente")
    elif "<init>()V" not in methods[jt]:
        bad.append(f"{kind} {cls} sans constructeur")
for b in bad:
    print("    ", b)
check(not bad, "toutes présentes avec leur constructeur")

eca = [a for t, n, a in ev if t == "start" and n == "activity" and str(a.get("name", "")).endswith("EclipseActivity")]
check(len(eca) == 1, "écran Eclipse")
check(any(t == "start" and n == "data" and a.get("scheme") == "eclipse" and a.get("host") == "pair" for t, n, a in ev), "liens eclipse://pair")

print("== Code et interface")
check("lib/arm64-v8a/libgojni.so" in names and "lib/armeabi-v7a/libgojni.so" in names, "Tailscale intégré (arm64, arm)")
check("Leclipsenet/Eclipsenet;" in methods and "forward(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V" in methods["Leclipsenet/Eclipsenet;"], "pont vers le Tailscale intégré")
check("Lgo/Seq;" in methods, "moteur gomobile gardé")
check("Lcom/limelight/EclipseActivity$Bridge;" in methods and "scanQr()V" in methods["Lcom/limelight/EclipseActivity$Bridge;"], "pont scanQr")
for f in ["assets/eclipse/index.html", "assets/eclipse/app.js", "assets/eclipse/app.css", "assets/eclipse/logo.png"]:
    check(f in names, f)

if len(sys.argv) > 3:
    print("== Signature")
    data = open(apk, "rb").read()
    eocd = data.rfind(b"PK\x05\x06")
    cd_off = struct.unpack_from("<I", data, eocd + 16)[0]
    check(data[cd_off - 16:cd_off] == b"APK Sig Block 42", "bloc de signature v2")
    size = struct.unpack_from("<Q", data, cd_off - 24)[0]
    pos = cd_off - size
    certs = []
    while pos < cd_off - 24:
        l = struct.unpack_from("<Q", data, pos)[0]
        bid = struct.unpack_from("<I", data, pos + 8)[0]
        val = data[pos + 12:pos + 8 + l]
        if bid in (0x7109871a, 0xf05368c0):
            p = 8
            sd_len = struct.unpack_from("<I", val, p)[0]; p += 4
            sd = val[p:p + sd_len]
            q = 4 + struct.unpack_from("<I", sd, 0)[0]
            q += 4
            c1 = struct.unpack_from("<I", sd, q)[0]; q += 4
            certs.append(sd[q:q + c1])
        pos += 8 + l
    want = sys.argv[3].replace(":", "").upper()
    check(certs and all(hashlib.sha256(c).hexdigest().upper() == want for c in certs), "même clé que les versions précédentes")

print()
print("Résultat :", "TOUT EST BON" if not FAILS else f"{len(FAILS)} ÉCHEC(S) : {FAILS}")
sys.exit(1 if FAILS else 0)
