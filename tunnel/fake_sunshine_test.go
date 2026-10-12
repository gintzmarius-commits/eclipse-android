package eclipsenet

// A PC answering like Sunshine, pairing included (for the emulator tests):
// the phone pairs with it for real (same protocol as Sunshine, PIN of the
// QR code), so it then reaches Eclipse's file sharing with its certificate.
// Once paired, the phone's certificate is written in sunshine_state.json,
// like Sunshine does: Eclipse for PC (run by the test) reads it there.

import (
	"crypto"
	"crypto/aes"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"math/big"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

type fakeSunshine struct {
	t       *testing.T
	dir     string // Sunshine's config folder (credentials, sunshine_state.json)
	pin     string
	cert    tls.Certificate
	x509    *x509.Certificate
	certPEM []byte
	key     *rsa.PrivateKey

	mu         sync.Mutex
	aesKey     []byte
	clientPEM  []byte
	clientName string
	secret     []byte
	paired     map[string]bool // client certificate (DER, hex) -> paired
}

func newFakeSunshine(t *testing.T, dir, pin string) *fakeSunshine {
	k, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	tpl := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "Sunshine Gamestream Host"},
		NotBefore: time.Now().Add(-24 * time.Hour), NotAfter: time.Now().Add(20 * 365 * 24 * time.Hour)}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &k.PublicKey, k)
	if err != nil {
		t.Fatal(err)
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(k)})
	c, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		t.Fatal(err)
	}
	xc, _ := x509.ParseCertificate(der)
	f := &fakeSunshine{t: t, dir: dir, pin: pin, cert: c, x509: xc, certPEM: certPEM, key: k, paired: map[string]bool{}}
	if dir != "" {
		os.MkdirAll(filepath.Join(dir, "credentials"), 0o755)
		os.WriteFile(filepath.Join(dir, "credentials", "cacert.pem"), certPEM, 0o600)
		os.WriteFile(filepath.Join(dir, "credentials", "cakey.pem"), keyPEM, 0o600)
		f.saveState()
	}
	return f
}

func xmlOK(w http.ResponseWriter, body string) {
	w.Header().Set("Content-Type", "application/xml")
	fmt.Fprintf(w, `<?xml version="1.0" encoding="utf-8"?><root status_code="200">%s</root>`, body)
}

func aesECB(key, data []byte, encrypt bool) []byte {
	b, _ := aes.NewCipher(key)
	n := (len(data) + 15) / 16 * 16
	in := make([]byte, n)
	copy(in, data)
	out := make([]byte, n)
	for i := 0; i < n; i += 16 {
		if encrypt {
			b.Encrypt(out[i:i+16], in[i:i+16])
		} else {
			b.Decrypt(out[i:i+16], in[i:i+16])
		}
	}
	return out
}

func sha(parts ...[]byte) []byte {
	h := sha256.New()
	for _, p := range parts {
		h.Write(p)
	}
	return h.Sum(nil)
}

func (f *fakeSunshine) serverInfo(paired bool) string {
	ps := "0"
	if paired {
		ps = "1"
	}
	return strings.Replace(sunshineServerInfo, "<PairStatus>0</PairStatus>", "<PairStatus>"+ps+"</PairStatus>", 1)
}

func deviceName(raw string) string {
	// "Eclipse.<token>.<name>" (Eclipse phone app)
	if strings.HasPrefix(raw, "Eclipse.") {
		if parts := strings.SplitN(raw[len("Eclipse."):], ".", 2); len(parts) == 2 {
			return parts[1]
		}
	}
	return raw
}

// httpHandler: Sunshine's plain HTTP port (47989): server info and pairing.
func (f *fakeSunshine) httpHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.t.Logf("PC: %s %s", r.Method, r.URL.Path)
		q := r.URL.Query()
		switch r.URL.Path {
		case "/serverinfo":
			w.Write([]byte(f.serverInfo(false)))
		case "/unpair":
			xmlOK(w, "")
		case "/pair":
			f.pair(w, q.Get("devicename"), q)
		default:
			http.NotFound(w, r)
		}
	})
}

func (f *fakeSunshine) pair(w http.ResponseWriter, devName string, q map[string][]string) {
	get := func(k string) string {
		if v := q[k]; len(v) > 0 {
			return v[0]
		}
		return ""
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	switch {
	case get("phrase") == "getservercert":
		salt, _ := hex.DecodeString(get("salt"))
		cpem, _ := hex.DecodeString(get("clientcert"))
		f.aesKey = sha(salt, []byte(f.pin))[:16]
		f.clientPEM = cpem
		f.clientName = deviceName(devName)
		xmlOK(w, "<paired>1</paired><plaincert>"+hex.EncodeToString(f.certPEM)+"</plaincert>")
	case get("clientchallenge") != "":
		enc, _ := hex.DecodeString(get("clientchallenge"))
		challenge := aesECB(f.aesKey, enc, false)[:16]
		f.secret = make([]byte, 16)
		rand.Read(f.secret)
		serverChallenge := make([]byte, 16)
		rand.Read(serverChallenge)
		resp := append(sha(challenge, f.x509.Signature, f.secret), serverChallenge...)
		xmlOK(w, "<paired>1</paired><challengeresponse>"+hex.EncodeToString(aesECB(f.aesKey, resp, true))+"</challengeresponse>")
	case get("serverchallengeresp") != "":
		sig, _ := rsa.SignPKCS1v15(rand.Reader, f.key, crypto.SHA256, sha(f.secret))
		xmlOK(w, "<paired>1</paired><pairingsecret>"+hex.EncodeToString(append(append([]byte{}, f.secret...), sig...))+"</pairingsecret>")
	case get("clientpairingsecret") != "":
		if b, _ := pem.Decode(f.clientPEM); b != nil {
			f.paired[hex.EncodeToString(b.Bytes)] = true
		}
		f.saveState()
		f.t.Logf("PC: %q paired", f.clientName)
		xmlOK(w, "<paired>1</paired>")
	default:
		xmlOK(w, "<paired>0</paired>")
	}
}

// saveState writes the paired devices like Sunshine (sunshine_state.json).
func (f *fakeSunshine) saveState() {
	if f.dir == "" {
		return
	}
	devs := []map[string]string{}
	if len(f.clientPEM) > 0 && len(f.paired) > 0 {
		devs = append(devs, map[string]string{"name": f.clientName, "cert": string(f.clientPEM), "uuid": "E2E-PHONE"})
	}
	data, _ := json.Marshal(map[string]any{"root": map[string]any{"named_devices": devs}})
	os.WriteFile(filepath.Join(f.dir, "sunshine_state.json.tmp"), data, 0o600)
	os.Rename(filepath.Join(f.dir, "sunshine_state.json.tmp"), filepath.Join(f.dir, "sunshine_state.json"))
}

func (f *fakeSunshine) isPaired(r *http.Request) bool {
	if r.TLS == nil || len(r.TLS.PeerCertificates) == 0 {
		return false
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.paired[hex.EncodeToString(r.TLS.PeerCertificates[0].Raw)]
}

// httpsHandler: Sunshine's HTTPS port (47984), for paired phones.
func (f *fakeSunshine) httpsHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.t.Logf("PC (https): %s %s", r.Method, r.URL.Path)
		paired := f.isPaired(r)
		switch r.URL.Path {
		case "/serverinfo":
			w.Write([]byte(f.serverInfo(paired)))
		case "/pair":
			if r.URL.Query().Get("phrase") == "pairchallenge" && paired {
				xmlOK(w, "<paired>1</paired>")
				return
			}
			w.WriteHeader(401)
			w.Write([]byte(`<?xml version="1.0" encoding="utf-8"?><root status_code="401" status_message="The client is not authorized. Certificate verification failed."/>`))
		case "/applist":
			if !paired {
				w.WriteHeader(401)
				return
			}
			xmlOK(w, `<App><IsHdrSupported>0</IsHdrSupported><AppTitle>Desktop</AppTitle><ID>881448767</ID></App>`)
		default:
			http.NotFound(w, r)
		}
	})
}

func (f *fakeSunshine) tlsConfig() *tls.Config {
	return &tls.Config{Certificates: []tls.Certificate{f.cert}, ClientAuth: tls.RequestClientCert}
}

// TestFakeSunshinePairing pairs with the fake like Moonlight's
// PairingManager does (same steps, same checks).
func TestFakeSunshinePairing(t *testing.T) {
	dir := t.TempDir()
	f := newFakeSunshine(t, dir, "1234")
	// The phone's own certificate.
	ck, _ := rsa.GenerateKey(rand.Reader, 2048)
	tpl := &x509.Certificate{SerialNumber: big.NewInt(2), Subject: pkix.Name{CommonName: "NVIDIA GameStream Client"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour)}
	cder, _ := x509.CreateCertificate(rand.Reader, tpl, tpl, &ck.PublicKey, ck)
	ccert, _ := x509.ParseCertificate(cder)
	cpem := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: cder})

	call := func(q string) string {
		r := httptestRecorder()
		req, _ := http.NewRequest("GET", "/pair?devicename=Eclipse.abc.Pixel%20de%20Marius&"+q, nil)
		f.httpHandler().ServeHTTP(r, req)
		return r.Body.String()
	}
	tag := func(s, k string) string {
		i := strings.Index(s, "<"+k+">")
		j := strings.Index(s, "</"+k+">")
		if i < 0 || j < 0 {
			t.Fatalf("no %s in %s", k, s)
		}
		return s[i+len(k)+2 : j]
	}
	salt := make([]byte, 16)
	rand.Read(salt)
	key := sha(salt, []byte("1234"))[:16]
	resp := call("phrase=getservercert&salt=" + hex.EncodeToString(salt) + "&clientcert=" + hex.EncodeToString(cpem))
	pc, _ := hex.DecodeString(tag(resp, "plaincert"))
	b, _ := pem.Decode(pc)
	scert, err := x509.ParseCertificate(b.Bytes)
	if err != nil {
		t.Fatal(err)
	}
	challenge := make([]byte, 16)
	rand.Read(challenge)
	resp = call("clientchallenge=" + hex.EncodeToString(aesECB(key, challenge, true)))
	enc, _ := hex.DecodeString(tag(resp, "challengeresponse"))
	dec := aesECB(key, enc, false)
	serverResponse, serverChallenge := dec[:32], dec[32:48]
	clientSecret := make([]byte, 16)
	rand.Read(clientSecret)
	resp = call("serverchallengeresp=" + hex.EncodeToString(aesECB(key, sha(serverChallenge, ccert.Signature, clientSecret), true)))
	ps, _ := hex.DecodeString(tag(resp, "pairingsecret"))
	serverSecret, sig := ps[:16], ps[16:]
	if err := rsa.VerifyPKCS1v15(scert.PublicKey.(*rsa.PublicKey), crypto.SHA256, sha(serverSecret), sig); err != nil {
		t.Fatalf("server signature: %v", err)
	}
	if string(sha(challenge, scert.Signature, serverSecret)) != string(serverResponse) {
		t.Fatal("PIN check failed")
	}
	csig, _ := rsa.SignPKCS1v15(rand.Reader, ck, crypto.SHA256, sha(clientSecret))
	resp = call("clientpairingsecret=" + hex.EncodeToString(append(clientSecret, csig...)))
	if tag(resp, "paired") != "1" {
		t.Fatal(resp)
	}
	data, _ := os.ReadFile(filepath.Join(dir, "sunshine_state.json"))
	if !strings.Contains(string(data), "Pixel de Marius") || !strings.Contains(string(data), "BEGIN CERTIFICATE") {
		t.Fatalf("state: %s", data)
	}
}

func httptestRecorder() *httptest.ResponseRecorder { return httptest.NewRecorder() }
