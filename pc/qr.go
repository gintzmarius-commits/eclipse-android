package main

// QR code pairing (Eclipse 2.0).
//
// The "Connecter un appareil" window shows a QR code holding everything the
// Eclipse phone app needs: the addresses of this PC, a one-time token and a
// PIN. The phone then pairs with Sunshine exactly like Moonlight does, but it
// puts the token in its device name ("Eclipse.<token>.<phone name>"). Eclipse
// sees that request in Sunshine's list of pending requests and answers it
// with the PIN, so nobody has to type anything.
//
// Safety: the token and the PIN only exist on this screen. A request with
// any other token is refused, and after a few failed attempts a new code is
// shown. Requests that are not Eclipse QR requests (Moonlight, older Eclipse
// apps) are left alone: the window asks for their 4-digit code as before.

import (
	"crypto/rand"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	qrcode "github.com/skip2/go-qrcode"
)

const (
	qrPrefix      = "Eclipse."
	qrTokenLen    = 8
	qrMaxFailures = 5
	qrHeartbeat   = 25 * time.Second // the window asks for news every second
	qrTick        = 700 * time.Millisecond
	phoneAppURL   = "https://github.com/gintzmarius-commits/eclipse-android/releases/latest/download/Eclipse.apk"
)

var errQRUnsupported = errors.New("cette version de Sunshine ne permet pas la connexion par QR code")

type qrSession struct {
	mu       sync.Mutex
	token    string
	pin      string
	rev      int    // changes each time a new code is drawn
	svg      string // data URL of the QR code
	payload  string
	status   string // waiting, pairing, done, failed
	device   string
	errText  string
	failures int
	seen     time.Time
	handled  map[string]bool // Sunshine pairing ids already answered
	stopped  bool
	stop     chan struct{}
	sunErrs  int
	// the phone's Tailscale sign-in page was opened on this PC
	phoneLogin bool
}

type qrState struct {
	mu  sync.Mutex
	cur *qrSession
}

// ---------------------------------------------------------------------
// Device names
// ---------------------------------------------------------------------

// parseDeviceName reads the name a client gave when asking to pair.
//
//	"Eclipse.<token>.<name>" Eclipse phone app, from a QR code -> kind "qr"
//	"Eclipse..<name>"        Eclipse phone app, 4-digit code   -> kind "eclipse"
//	"roth" (or empty)        Moonlight                         -> kind "other", no name
//	anything else            another client                    -> kind "other"
func parseDeviceName(raw string) (kind, token, name string) {
	if strings.HasPrefix(raw, qrPrefix) {
		parts := strings.SplitN(raw[len(qrPrefix):], ".", 2)
		if len(parts) == 2 {
			name = cleanDeviceName(parts[1])
			if parts[0] == "" {
				return "eclipse", "", name
			}
			return "qr", parts[0], name
		}
	}
	if raw == "roth" {
		return "other", "", ""
	}
	return "other", "", cleanDeviceName(raw)
}

// cleanDeviceName keeps a name printable and within Sunshine's limit
// (1 to 128 bytes).
func cleanDeviceName(s string) string {
	var b strings.Builder
	space := false
	for _, r := range s {
		if r < 0x20 || r == 0x7f || r == utf8.RuneError {
			r = ' '
		}
		if r == ' ' {
			if space || b.Len() == 0 {
				continue
			}
			space = true
		} else {
			space = false
		}
		if b.Len()+utf8.RuneLen(r) > 100 {
			break
		}
		b.WriteRune(r)
	}
	return strings.TrimSpace(b.String())
}

// splitToken separates the QR token from the key of the phone's Tailscale
// sign-in page ("<token>~<key>", Eclipse 2.3 phones). A key that does not
// look like one is ignored.
func splitToken(tok string) (token, key string) {
	i := strings.IndexByte(tok, '~')
	if i < 0 {
		return tok, ""
	}
	token, key = tok[:i], tok[i+1:]
	if !tsKeyRe.MatchString(key) {
		key = ""
	}
	return token, key
}

var tsKeyRe = regexp.MustCompile(`^[A-Za-z0-9]{6,64}$`)

// phoneLoginURL is the phone's Tailscale sign-in page for a key.
func phoneLoginURL(key string) string { return "https://login.tailscale.com/a/" + key }

// displayDeviceName is the name shown (and saved in Sunshine) for a request.
func displayDeviceName(raw string) string {
	_, _, name := parseDeviceName(raw)
	return name
}

// ---------------------------------------------------------------------
// The code itself
// ---------------------------------------------------------------------

func randomToken() string {
	const chars = "abcdefghijkmnpqrstuvwxyz23456789" // 32 symbols: no bias
	b := make([]byte, qrTokenLen)
	_, _ = rand.Read(b)
	for i := range b {
		b[i] = chars[int(b[i])%len(chars)]
	}
	return string(b)
}

// qrSVG draws text as a QR code (SVG data URL, black on white, with the
// standard white margin around it).
func qrSVG(text string) (string, error) {
	q, err := qrcode.New(text, qrcode.Medium)
	if err != nil {
		return "", err
	}
	bm := q.Bitmap() // includes the 4-module quiet zone
	n := len(bm)
	var p strings.Builder
	for y, row := range bm {
		for x := 0; x < n; {
			if !row[x] {
				x++
				continue
			}
			start := x
			for x < n && row[x] {
				x++
			}
			fmt.Fprintf(&p, "M%d %dh%dv1h-%dz", start, y, x-start, x-start)
		}
	}
	svg := fmt.Sprintf(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" shape-rendering="crispEdges">`+
		`<rect width="%d" height="%d" fill="#fff"/><path fill="#0b0d14" d="%s"/></svg>`, n, n, n, n, p.String())
	return "data:image/svg+xml;charset=utf-8," + url.PathEscape(svg), nil
}

// sunshineHTTPPort is the port phones use to reach Sunshine (47989 unless
// changed in Sunshine's settings).
func sunshineHTTPPort() int {
	if p, err := strconv.Atoi(readSunshineConf(cachedConfigDir())["port"]); err == nil && p > 1024 && p < 65536 {
		return p
	}
	return 47989
}

// qrPayload is the text inside the QR code.
func qrPayload(token, pin string) string {
	port := sunshineHTTPPort()
	name, _ := os.Hostname()
	id := ""
	if si, ok := fetchServerInfo("127.0.0.1:"+strconv.Itoa(port), 2*time.Second); ok {
		name, id = si.Hostname, si.UniqueID
	}
	v := url.Values{}
	v.Set("v", "1")
	v.Set("n", name)
	v.Set("a", strings.Join(localIPs(), ","))
	v.Set("port", strconv.Itoa(port))
	if id != "" {
		v.Set("id", id)
	}
	if ip, account := remoteForQR(); ip != "" {
		// Tailscale: the phone keeps this address to play away from home.
		v.Set("ts", ip)
		if account != "" {
			v.Set("tu", account)
		}
		// This PC opens the phone's Tailscale sign-in page (Eclipse 2.3):
		// the phone sends its key with its name ("<token>~<key>").
		v.Set("tl", "1")
	}
	v.Set("t", token)
	v.Set("p", pin)
	return "eclipse://pair?" + v.Encode()
}

// newCode gives the session a new token, PIN and drawing.
func (s *qrSession) newCode() error {
	token, pin := randomToken(), randomPin()
	payload := qrPayload(token, pin)
	svg, err := qrSVG(payload)
	if err != nil {
		return err
	}
	s.mu.Lock()
	s.token, s.pin, s.payload, s.svg = token, pin, payload, svg
	s.rev++
	s.handled = map[string]bool{}
	s.mu.Unlock()
	return nil
}

// ---------------------------------------------------------------------
// Session life
// ---------------------------------------------------------------------

func (a *Core) qrStart() (*qrSession, error) {
	if _, supported, err := a.sun.Pending(); err != nil {
		return nil, err
	} else if !supported {
		return nil, errQRUnsupported
	}
	s := &qrSession{status: "waiting", seen: time.Now(), stop: make(chan struct{})}
	if err := s.newCode(); err != nil {
		return nil, err
	}
	a.qr.mu.Lock()
	old := a.qr.cur
	a.qr.cur = s
	a.qr.mu.Unlock()
	if old != nil {
		old.end()
	}
	log.Printf("qr: new code (%s)", strings.SplitN(s.payload, "&t=", 2)[0])
	go a.qrLoop(s)
	return s, nil
}

func (s *qrSession) end() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.stopped {
		s.stopped = true
		close(s.stop)
	}
}

func (a *Core) qrStop() {
	a.qr.mu.Lock()
	s := a.qr.cur
	a.qr.cur = nil
	a.qr.mu.Unlock()
	if s != nil {
		s.end()
	}
}

// qrCurrent returns the running session and notes that the window is open.
func (a *Core) qrCurrent() *qrSession {
	a.qr.mu.Lock()
	defer a.qr.mu.Unlock()
	s := a.qr.cur
	if s == nil {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.stopped {
		return nil
	}
	s.seen = time.Now()
	return s
}

func (a *Core) qrLoop(s *qrSession) {
	t := time.NewTicker(qrTick)
	defer t.Stop()
	defer func() {
		a.qr.mu.Lock()
		if a.qr.cur == s {
			a.qr.cur = nil
		}
		a.qr.mu.Unlock()
		s.end()
	}()
	for {
		select {
		case <-s.stop:
			return
		case <-t.C:
		}
		s.mu.Lock()
		idle := time.Since(s.seen) > qrHeartbeat
		done := s.status == "done"
		s.mu.Unlock()
		if idle {
			log.Printf("qr: window closed, code no longer valid")
			return
		}
		if !done {
			a.qrHandle(s)
		}
	}
}

// qrHandle answers the Eclipse QR requests waiting in Sunshine.
func (a *Core) qrHandle(s *qrSession) {
	ps, _, err := a.sun.Pending()
	s.mu.Lock()
	if err != nil {
		s.sunErrs++
		if s.sunErrs >= 6 && s.status != "pairing" {
			s.status, s.errText = "failed", "Sunshine ne répond pas. Vérifie qu'il est démarré."
		}
		s.mu.Unlock()
		return
	}
	if s.sunErrs >= 6 && s.errText == "Sunshine ne répond pas. Vérifie qu'il est démarré." {
		s.status, s.errText = "waiting", ""
	}
	s.sunErrs = 0
	s.mu.Unlock()

	for _, p := range ps {
		kind, token, name := parseDeviceName(p.Name)
		if kind != "qr" {
			continue
		}
		token, key := splitToken(token)
		s.mu.Lock()
		if s.handled[p.ID] || s.stopped {
			s.mu.Unlock()
			continue
		}
		s.handled[p.ID] = true
		match := token == s.token
		pin := s.pin
		s.mu.Unlock()

		if !match {
			// A phone scanned an older code: refuse it so it does not block
			// the next request, and so it can scan the code shown now.
			ok, err := a.sun.CancelPairing(p.ID)
			log.Printf("qr: request with an old code refused (%v %v)", ok, err)
			continue
		}
		if name == "" {
			name = "Téléphone"
		}
		a.qrPair(s, p.ID, pin, name, key)
		s.mu.Lock()
		done := s.status == "done"
		s.mu.Unlock()
		if done {
			return
		}
	}
}

func (a *Core) qrPair(s *qrSession, id, pin, name, key string) {
	s.mu.Lock()
	s.status, s.device, s.errText = "pairing", name, ""
	s.mu.Unlock()
	log.Printf("qr: answering %q", name)

	ok, err := a.pairAndTidy(id, pin, name)

	s.mu.Lock()
	defer s.mu.Unlock()
	if ok {
		s.status = "done"
		log.Printf("qr: %q connected", name)
		if key != "" {
			// The phone is not signed in to Tailscale yet: its sign-in page
			// opens here, where the person is (and already signed in).
			s.phoneLogin = true
			log.Printf("qr: Tailscale sign-in page of %q opened", name)
			go openURL(phoneLoginURL(key))
		}
		return
	}
	s.failures++
	log.Printf("qr: %q not connected (attempt %d): %v", name, s.failures, err)
	s.status = "failed"
	s.errText = "La connexion a échoué. Réessaie depuis le téléphone."
	if s.failures >= qrMaxFailures {
		s.failures = 0
		s.mu.Unlock()
		nerr := s.newCode()
		s.mu.Lock()
		if nerr == nil {
			s.errText = "Plusieurs essais ont échoué : un nouveau code est affiché. Scanne-le."
		}
	}
}

// ---------------------------------------------------------------------
// Routes for the interface
// ---------------------------------------------------------------------

func (s *qrSession) view() map[string]any {
	s.mu.Lock()
	defer s.mu.Unlock()
	return map[string]any{
		"active": !s.stopped, "rev": s.rev, "svg": s.svg, "status": s.status,
		"device": s.device, "error": s.errText, "phoneLogin": s.phoneLogin,
	}
}

func (a *Core) qrRoutes(api *http.ServeMux) {
	// Opens the window: a fresh code each time.
	api.HandleFunc("POST /api/qr/start", func(w http.ResponseWriter, r *http.Request) {
		s, err := a.qrStart()
		if errors.Is(err, errQRUnsupported) {
			writeJSON(w, map[string]any{"supported": false})
			return
		}
		if err != nil {
			writeErr(w, "Impossible de préparer le QR code.", err)
			return
		}
		v := s.view()
		v["supported"] = true
		writeJSON(w, v)
	})

	// Asked every second while the window is open (keeps the code valid).
	api.HandleFunc("GET /api/qr/status", func(w http.ResponseWriter, r *http.Request) {
		s := a.qrCurrent()
		if s == nil {
			writeJSON(w, map[string]any{"active": false})
			return
		}
		v := s.view()
		if r.URL.Query().Get("rev") == strconv.Itoa(v["rev"].(int)) {
			delete(v, "svg") // the window already shows this code
		}
		writeJSON(w, v)
	})

	api.HandleFunc("POST /api/qr/stop", func(w http.ResponseWriter, r *http.Request) {
		a.qrStop()
		writeJSON(w, map[string]bool{"ok": true})
	})

	// QR code to download the phone app.
	api.HandleFunc("GET /api/qr/app", func(w http.ResponseWriter, r *http.Request) {
		svg, err := qrSVG(phoneAppURL)
		if err != nil {
			writeErr(w, "Impossible de dessiner le QR code.", err)
			return
		}
		writeJSON(w, map[string]string{"svg": svg, "url": phoneAppURL})
	})
}
