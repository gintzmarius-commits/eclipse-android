package main

import (
	"bytes"
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func makeCert(t *testing.T, cn string) (certPEM, keyPEM []byte, c tls.Certificate) {
	t.Helper()
	k, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	tpl := &x509.Certificate{SerialNumber: big.NewInt(time.Now().UnixNano()), Subject: pkix.Name{CommonName: cn},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(24 * time.Hour)}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &k.PublicKey, k)
	if err != nil {
		t.Fatal(err)
	}
	certPEM = pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM = pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(k)})
	c, err = tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		t.Fatal(err)
	}
	return
}

type filesEnv struct {
	base     string // http://127.0.0.1:port
	port     int
	srvCert  []byte
	phone    *http.Client // paired phone
	stranger *http.Client // TLS, not paired
	dir      string       // a folder of "the PC" for the tests
	fs       *fileServer
}

func freePort(t *testing.T) int {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	p := l.Addr().(*net.TCPAddr).Port
	l.Close()
	return p
}

func setupFiles(t *testing.T) *filesEnv {
	t.Helper()
	root := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(root, "config"))
	t.Setenv("XDG_STATE_HOME", filepath.Join(root, "state"))
	sun := filepath.Join(root, "sunshine")
	os.MkdirAll(filepath.Join(sun, "credentials"), 0o755)
	t.Setenv("ECLIPSE_SUNSHINE_CONFIG", sun)
	confDir.Lock()
	confDir.dir = ""
	confDir.Unlock()

	srvCert, srvKey, _ := makeCert(t, "Sunshine Gamestream Host")
	os.WriteFile(filepath.Join(sun, "credentials", "cacert.pem"), srvCert, 0o600)
	os.WriteFile(filepath.Join(sun, "credentials", "cakey.pem"), srvKey, 0o600)
	phoneCert, _, phoneTLS := makeCert(t, "NVIDIA GameStream Client")
	_, _, strangerTLS := makeCert(t, "NVIDIA GameStream Client")
	state := map[string]any{"root": map[string]any{"named_devices": []map[string]string{
		{"name": "Pixel de Marius", "cert": string(phoneCert), "uuid": "U1"},
	}}}
	data, _ := json.Marshal(state)
	os.WriteFile(filepath.Join(sun, "sunshine_state.json"), data, 0o600)

	port := freePort(t)
	t.Setenv("ECLIPSE_FILES_PORT", fmt.Sprint(port))
	core := &Core{show: make(chan struct{}, 1)}
	fsrv := newFileServer(core)
	core.files = fsrv
	go fsrv.start()
	for i := 0; i < 100; i++ {
		if c, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", port)); err == nil {
			c.Close()
			break
		}
		time.Sleep(30 * time.Millisecond)
	}

	pool := x509.NewCertPool()
	pool.AppendCertsFromPEM(srvCert)
	mk := func(c tls.Certificate) *http.Client {
		return &http.Client{Timeout: 60 * time.Second, Transport: &http.Transport{TLSClientConfig: &tls.Config{
			RootCAs: pool, Certificates: []tls.Certificate{c},
			// The phone pins Sunshine's certificate (no host name in it).
			InsecureSkipVerify: true,
			VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
				b, _ := pem.Decode(srvCert)
				if len(raw) == 0 || !bytes.Equal(raw[0], b.Bytes) {
					return fmt.Errorf("wrong server certificate")
				}
				return nil
			}}}}
	}
	dir := filepath.Join(root, "pc-files")
	os.MkdirAll(filepath.Join(dir, "Photos"), 0o755)
	os.WriteFile(filepath.Join(dir, "notes.txt"), []byte("bonjour"), 0o644)
	os.WriteFile(filepath.Join(dir, "Photos", "a.jpg"), bytes.Repeat([]byte{1}, 3000), 0o644)
	return &filesEnv{base: fmt.Sprintf("127.0.0.1:%d", port), port: port, srvCert: srvCert,
		phone: mk(phoneTLS), stranger: mk(strangerTLS), dir: dir, fs: fsrv}
}

func getJSONT(t *testing.T, c *http.Client, u string, out any) int {
	t.Helper()
	r, err := c.Get(u)
	if err != nil {
		t.Fatalf("GET %s: %v", u, err)
	}
	defer r.Body.Close()
	if out != nil {
		_ = json.NewDecoder(r.Body).Decode(out)
	}
	return r.StatusCode
}

func TestPhoneBrowsesPC(t *testing.T) {
	e := setupFiles(t)
	https := "https://" + e.base

	// A phone that is not paired gets nothing.
	if code := getJSONT(t, e.stranger, https+"/x/devices", nil); code != 403 {
		t.Fatalf("stranger: %d", code)
	}
	var devs struct{ Devices []deviceView }
	if code := getJSONT(t, e.phone, https+"/x/devices", &devs); code != 200 || len(devs.Devices) != 1 || devs.Devices[0].ID != "pc" {
		t.Fatalf("devices %d %+v", code, devs)
	}
	var roots struct{ Roots []rootEntry }
	getJSONT(t, e.phone, https+"/x/pc/roots", &roots)
	if len(roots.Roots) == 0 || roots.Roots[0].Path != "/" {
		t.Fatalf("roots %+v", roots)
	}
	var l listing
	if code := getJSONT(t, e.phone, https+"/x/pc/list?path="+url.QueryEscape(e.dir), &l); code != 200 {
		t.Fatalf("list %d", code)
	}
	if len(l.Entries) != 2 || !l.Entries[0].Dir || l.Entries[0].Name != "Photos" || l.Entries[1].Size != 7 {
		t.Fatalf("listing %+v", l)
	}
	// Relative paths and missing folders are refused cleanly.
	var er map[string]string
	if code := getJSONT(t, e.phone, https+"/x/pc/list?path=etc", &er); code != 400 || er["error"] == "" {
		t.Fatalf("relative path: %d %v", code, er)
	}
	// Download, with resume.
	req, _ := http.NewRequest("GET", https+"/x/pc/get?path="+url.QueryEscape(filepath.Join(e.dir, "Photos", "a.jpg")), nil)
	req.Header.Set("Range", "bytes=1000-")
	r, err := e.phone.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(r.Body)
	r.Body.Close()
	if r.StatusCode != 206 || len(b) != 2000 || !strings.Contains(r.Header.Get("Content-Disposition"), "a.jpg") {
		t.Fatalf("range get %d %d %v", r.StatusCode, len(b), r.Header)
	}
	// Upload twice: never overwritten.
	for i, want := range []string{"notes.txt", "notes (2).txt"} {
		_ = i
		u := https + "/x/pc/put?dir=" + url.QueryEscape(e.dir) + "&name=" + url.QueryEscape("../notes.txt")
		if want == "notes.txt" {
			u = https + "/x/pc/put?dir=" + url.QueryEscape(filepath.Join(e.dir, "Photos")) + "&name=notes.txt"
		}
		r, err := e.phone.Post(u, "application/octet-stream", strings.NewReader("salut"))
		if err != nil {
			t.Fatal(err)
		}
		var got map[string]string
		json.NewDecoder(r.Body).Decode(&got)
		r.Body.Close()
		if got["name"] != want {
			t.Fatalf("put %d: %v", r.StatusCode, got)
		}
	}
	if b, _ := os.ReadFile(filepath.Join(e.dir, "notes.txt")); string(b) != "bonjour" {
		t.Fatal("existing file overwritten")
	}
	if b, _ := os.ReadFile(filepath.Join(e.dir, "notes (2).txt")); string(b) != "salut" {
		t.Fatal("upload content")
	}
	if _, err := os.Stat(filepath.Join(filepath.Dir(e.dir), "notes.txt")); err == nil {
		t.Fatal("upload escaped its folder")
	}
}

// fakePhone answers the PC's requests like Eclipse for Android does.
func fakePhone(t *testing.T, c *http.Client, base string, files map[string][]byte, stop chan struct{}) {
	for {
		select {
		case <-stop:
			return
		default:
		}
		r, err := c.Get(base + "/p/poll")
		if err != nil {
			time.Sleep(100 * time.Millisecond)
			continue
		}
		if r.StatusCode == 204 {
			r.Body.Close()
			continue
		}
		var j phoneJob
		json.NewDecoder(r.Body).Decode(&j)
		r.Body.Close()
		go func(j phoneJob) {
			var body io.Reader
			hdr := map[string]string{}
			switch j.Op {
			case "roots":
				body = strings.NewReader(`{"roots":[{"name":"Stockage interne","path":"/storage/emulated/0","kind":"disk"}]}`)
			case "list":
				var es []fileEntry
				for name, data := range files {
					es = append(es, fileEntry{Name: name, Size: int64(len(data)), Mtime: 1})
				}
				out, _ := json.Marshal(listing{Path: j.Path, Entries: es})
				body = bytes.NewReader(out)
			case "get":
				data, ok := files[filepath.Base(j.Path)]
				if !ok {
					hdr["X-Status"] = "404"
					body = strings.NewReader(`{"error":"ce fichier n'existe plus"}`)
				} else {
					hdr["X-Size"] = fmt.Sprint(len(data))
					hdr["X-Name"] = url.PathEscape(filepath.Base(j.Path))
					body = bytes.NewReader(data)
				}
			case "put":
				br, err := c.Get(base + "/p/blob/" + j.ID)
				if err != nil {
					t.Errorf("blob: %v", err)
					return
				}
				data, _ := io.ReadAll(br.Body)
				br.Body.Close()
				files[j.Name] = data
				body = strings.NewReader(`{"name":"` + j.Name + `"}`)
			}
			req, _ := http.NewRequest("POST", base+"/p/reply/"+j.ID, body)
			for k, v := range hdr {
				req.Header.Set(k, v)
			}
			if rr, err := c.Do(req); err == nil {
				io.Copy(io.Discard, rr.Body)
				rr.Body.Close()
			}
		}(j)
	}
}

func TestPCBrowsesPhoneAndBrowser(t *testing.T) {
	e := setupFiles(t)
	https := "https://" + e.base
	files := map[string][]byte{"IMG_1.jpg": bytes.Repeat([]byte{7}, 100000)}
	stop := make(chan struct{})
	defer close(stop)
	go fakePhone(t, e.phone, https, files, stop)
	time.Sleep(300 * time.Millisecond)

	// --- a browser: the access code first ---
	jar, _ := cookiejar.New(nil)
	br := &http.Client{Jar: jar, Timeout: 60 * time.Second}
	plain := "http://" + e.base
	if code := getJSONT(t, br, plain+"/x/devices", nil); code != 401 {
		t.Fatalf("browser without code: %d", code)
	}
	r, _ := br.Get(plain + "/web/")
	page, _ := io.ReadAll(r.Body)
	r.Body.Close()
	if !strings.Contains(string(page), "Code d'accès") && !strings.Contains(string(page), "code d'accès") {
		t.Fatalf("page: %d %.200s", r.StatusCode, page)
	}
	login := func(code string) int {
		r, err := br.Post(plain+"/login", "application/json", strings.NewReader(`{"code":"`+code+`"}`))
		if err != nil {
			t.Fatal(err)
		}
		r.Body.Close()
		return r.StatusCode
	}
	if login("zzzz-zzzz") != 401 {
		t.Fatal("wrong code accepted")
	}
	if login(e.fs.web.code()) != 200 {
		t.Fatal("right code refused")
	}
	var devs struct{ Devices []deviceView }
	getJSONT(t, br, plain+"/x/devices", &devs)
	if len(devs.Devices) != 2 || devs.Devices[1].Kind != "phone" || !devs.Devices[1].Online || devs.Devices[1].Name != "Pixel de Marius" {
		t.Fatalf("devices %+v", devs)
	}
	ph := devs.Devices[1].ID

	var roots struct{ Roots []rootEntry }
	if code := getJSONT(t, br, plain+"/x/"+ph+"/roots", &roots); code != 200 || roots.Roots[0].Path != "/storage/emulated/0" {
		t.Fatalf("phone roots %d %+v", code, roots)
	}
	var l listing
	getJSONT(t, br, plain+"/x/"+ph+"/list?path=/storage/emulated/0/DCIM", &l)
	if len(l.Entries) != 1 || l.Entries[0].Name != "IMG_1.jpg" {
		t.Fatalf("phone list %+v", l)
	}
	r, err := br.Get(plain + "/x/" + ph + "/get?path=/storage/emulated/0/DCIM/IMG_1.jpg")
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(r.Body)
	r.Body.Close()
	if r.StatusCode != 200 || len(b) != 100000 || !strings.Contains(r.Header.Get("Content-Disposition"), "IMG_1.jpg") {
		t.Fatalf("phone get %d %d", r.StatusCode, len(b))
	}
	var er map[string]string
	if code := getJSONT(t, br, plain+"/x/"+ph+"/get?path=/x/none.jpg", &er); code != 400 || er["error"] != "ce fichier n'existe plus" {
		t.Fatalf("missing phone file: %d %v", code, er)
	}

	// Sending to the phone needs the browser's header (no other site can).
	req, _ := http.NewRequest("POST", plain+"/x/"+ph+"/put?dir=/storage/emulated/0/Download&name=doc.pdf", strings.NewReader("PDF!"))
	r, _ = br.Do(req)
	r.Body.Close()
	if r.StatusCode != 403 {
		t.Fatalf("put without header: %d", r.StatusCode)
	}
	req, _ = http.NewRequest("POST", plain+"/x/"+ph+"/put?dir=/storage/emulated/0/Download&name=doc.pdf", strings.NewReader("PDF!"))
	req.Header.Set("X-Eclipse", "1")
	r, _ = br.Do(req)
	var got map[string]string
	json.NewDecoder(r.Body).Decode(&got)
	r.Body.Close()
	if got["name"] != "doc.pdf" || string(files["doc.pdf"]) != "PDF!" {
		t.Fatalf("put to phone: %d %v", r.StatusCode, got)
	}

	// The PC's own files, as a zip.
	r, _ = br.Get(plain + "/x/pc/zip?path=" + url.QueryEscape(e.dir))
	b, _ = io.ReadAll(r.Body)
	r.Body.Close()
	if r.StatusCode != 200 || len(b) < 100 || string(b[:2]) != "PK" {
		t.Fatalf("zip %d %d", r.StatusCode, len(b))
	}

	// A new code: the browser has to sign in again.
	e.fs.web.newCode()
	if code := getJSONT(t, br, plain+"/x/devices", nil); code != 401 {
		t.Fatalf("old session still valid: %d", code)
	}
	// Too many wrong codes: locked for a while, even with the right one.
	for i := 0; i < 5; i++ {
		login("abcd-efgh")
	}
	if login(e.fs.web.code()) != 429 {
		t.Fatal("no lockout")
	}
	// Turned off: nothing for browsers, phones still work.
	e.fs.web.setEnabled(false)
	if code := getJSONT(t, br, plain+"/x/devices", nil); code != 403 {
		t.Fatalf("disabled: %d", code)
	}
	if code := getJSONT(t, e.phone, https+"/x/devices", nil); code != 200 {
		t.Fatalf("phone after disabling browsers: %d", code)
	}
}

func TestPhoneAwayAndHelpers(t *testing.T) {
	e := setupFiles(t)
	https := "https://" + e.base
	// The phone polled once, then went away: asking it fails fast.
	r, _ := e.phone.Get(https + "/x/devices")
	r.Body.Close()
	l := e.fs.phones[func() string {
		for k := range e.fs.phones {
			return k
		}
		return ""
	}()]
	l.lastSeen = time.Now().Add(-time.Hour)
	_, err := e.fs.phoneJSON(t.Context(), l, &phoneJob{Op: "roots"})
	if err != errPhoneAway {
		t.Fatalf("away: %v", err)
	}

	for in, want := range map[string]string{"a/b/c.txt": "c.txt", `..\..\x.exe`: "x.exe", "co:n?.txt": "co_n_.txt", "  .. ": ""} {
		got, _ := safeName(in)
		if got != want {
			t.Errorf("safeName(%q) = %q, want %q", in, got, want)
		}
	}
	for addr, ok := range map[string]bool{"192.168.1.5": true, "10.0.0.2": true, "100.101.1.2": true, "127.0.0.1": true, "8.8.8.8": false, "100.200.1.1": false} {
		if allowedPeer(&net.TCPAddr{IP: net.ParseIP(addr)}) != ok {
			t.Errorf("allowedPeer(%s) != %v", addr, ok)
		}
	}
	// Downloads saved under a free name.
	dest := t.TempDir()
	os.WriteFile(filepath.Join(dest, "a.txt"), []byte("x"), 0o644)
	n, err := saveFile(dest, "a.txt", strings.NewReader("yy"), 2)
	if err != nil || n != "a (2).txt" {
		t.Fatalf("saveFile %q %v", n, err)
	}
	if _, err := saveFile(dest, "b.txt", strings.NewReader("y"), 5); err == nil {
		t.Fatal("short upload accepted")
	}
	if left, _ := filepath.Glob(filepath.Join(dest, "*"+partSuffix)); len(left) != 0 {
		t.Fatalf("part files left: %v", left)
	}
}
