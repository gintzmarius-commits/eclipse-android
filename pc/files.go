package main

// File sharing (Eclipse 2.4).
//
// Eclipse listens on one port (48150) of the home network and of Tailscale:
//
//   - Eclipse phones connect with TLS. The PC shows Sunshine's certificate
//     (the one the phone already trusts since pairing) and the phone shows
//     its own: only phones paired with Sunshine get in. A phone can browse
//     this PC's disks, download and send files. It also keeps a request
//     waiting ("poll"): that is how this PC (and browsers) reach the phone's
//     files, even through 4G, without the phone listening on any port.
//   - Browsers use plain HTTP (no certificate warning) and an access code
//     shown in Eclipse on the PC. Over Tailscale the traffic is encrypted
//     anyway; on the home network it stays in the home.
//
// Only private addresses (home network, Tailscale, this PC) are answered.
// Nothing is ever overwritten or deleted: a file sent with a name already
// taken is saved as "name (2).ext".

import (
	"archive/zip"
	"bufio"
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"mime"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const (
	filesPort    = 48150
	pollWait     = 25 * time.Second
	phoneOnline  = 40 * time.Second
	replyWait    = 45 * time.Second
	maxEntries   = 20000
	sessionLife  = 30 * 24 * time.Hour
	cookieName   = "eclipse_files"
	partSuffix   = ".eclipse-part"
	webCodeLen   = 8
	loginLockout = 10 * time.Minute
)

// ---------------------------------------------------------------------
// Local files: the same for phones, browsers and the PC's own window
// ---------------------------------------------------------------------

type fileEntry struct {
	Name  string `json:"name"`
	Dir   bool   `json:"dir"`
	Size  int64  `json:"size"`
	Mtime int64  `json:"mtime"` // unix ms
}

type listing struct {
	Path    string      `json:"path"`
	Parent  string      `json:"parent"` // "" at the top of a disk
	Entries []fileEntry `json:"entries"`
	More    bool        `json:"more,omitempty"` // cut at maxEntries
}

type rootEntry struct {
	Name string `json:"name"`
	Path string `json:"path"`
	Kind string `json:"kind"` // disk, home, folder
}

// cleanAbs checks a path sent by a client: absolute, no "..", cleaned.
func cleanAbs(p string) (string, error) {
	if p == "" || strings.ContainsRune(p, 0) {
		return "", errors.New("chemin vide")
	}
	if runtime.GOOS == "windows" {
		p = strings.ReplaceAll(p, "/", `\`)
		if len(p) == 2 && p[1] == ':' {
			p += `\`
		}
	}
	if !filepath.IsAbs(p) {
		return "", errors.New("chemin invalide")
	}
	return filepath.Clean(p), nil
}

func listDir(p string) (*listing, error) {
	p, err := cleanAbs(p)
	if err != nil {
		return nil, err
	}
	f, err := os.Open(p)
	if err != nil {
		return nil, friendlyFSErr(err)
	}
	defer f.Close()
	des, err := f.ReadDir(-1)
	if err != nil && len(des) == 0 {
		return nil, friendlyFSErr(err)
	}
	out := &listing{Path: p, Entries: []fileEntry{}}
	if parent := filepath.Dir(p); parent != p {
		out.Parent = parent
	}
	for _, de := range des {
		if len(out.Entries) >= maxEntries {
			out.More = true
			break
		}
		name := de.Name()
		if strings.HasSuffix(name, partSuffix) {
			continue // a file still being received
		}
		info, err := de.Info()
		if err != nil {
			continue
		}
		dir := info.IsDir()
		if info.Mode()&fs.ModeSymlink != 0 {
			if t, err := os.Stat(filepath.Join(p, name)); err == nil {
				info, dir = t, t.IsDir()
			}
		}
		e := fileEntry{Name: name, Dir: dir, Mtime: info.ModTime().UnixMilli()}
		if !dir {
			e.Size = info.Size()
		}
		out.Entries = append(out.Entries, e)
	}
	sortEntries(out.Entries)
	return out, nil
}

func sortEntries(es []fileEntry) {
	sort.SliceStable(es, func(i, j int) bool {
		if es[i].Dir != es[j].Dir {
			return es[i].Dir
		}
		a, b := strings.ToLower(es[i].Name), strings.ToLower(es[j].Name)
		if a != b {
			return a < b
		}
		return es[i].Name < es[j].Name
	})
}

func friendlyFSErr(err error) error {
	switch {
	case errors.Is(err, fs.ErrNotExist):
		return errors.New("ce dossier ou ce fichier n'existe plus")
	case errors.Is(err, fs.ErrPermission):
		return errors.New("accès refusé par le système")
	}
	return err
}

// safeName keeps only a file name a client may create (no folders).
func safeName(n string) (string, error) {
	n = strings.TrimSpace(strings.ReplaceAll(n, "\\", "/"))
	if i := strings.LastIndexByte(n, '/'); i >= 0 {
		n = n[i+1:]
	}
	var b strings.Builder
	for _, r := range n {
		switch {
		case r < 0x20 || r == 0x7f || r == utf8.RuneError:
			continue
		case strings.ContainsRune(`<>:"|?*`, r):
			b.WriteRune('_')
		default:
			b.WriteRune(r)
		}
	}
	n = strings.TrimRight(b.String(), ". ")
	if n == "" || n == "." || n == ".." {
		return "", errors.New("nom de fichier invalide")
	}
	if len(n) > 240 {
		ext := filepath.Ext(n)
		if len(ext) > 20 {
			ext = ""
		}
		n = strings.ToValidUTF8(n[:240-len(ext)], "") + ext
	}
	return n, nil
}

// freeName returns name, or "name (2).ext", "name (3).ext"… if taken.
func freeName(dir, name string) string {
	if _, err := os.Lstat(filepath.Join(dir, name)); errors.Is(err, fs.ErrNotExist) {
		return name
	}
	ext := filepath.Ext(name)
	base := strings.TrimSuffix(name, ext)
	if ext == name {
		base, ext = name, ""
	}
	for i := 2; ; i++ {
		n := fmt.Sprintf("%s (%d)%s", base, i, ext)
		if _, err := os.Lstat(filepath.Join(dir, n)); errors.Is(err, fs.ErrNotExist) {
			return n
		}
	}
}

var saveMu sync.Mutex // choosing a free name and creating it, one at a time

// saveFile writes body into dir under name (never overwriting): it is
// received as "name.eclipse-part" and renamed at the end.
func saveFile(dir, name string, body io.Reader, size int64) (string, error) {
	dir, err := cleanAbs(dir)
	if err != nil {
		return "", err
	}
	name, err = safeName(name)
	if err != nil {
		return "", err
	}
	if st, err := os.Stat(dir); err != nil || !st.IsDir() {
		return "", errors.New("ce dossier n'existe plus")
	}
	saveMu.Lock()
	final := freeName(dir, name)
	tmp, err := os.OpenFile(filepath.Join(dir, final+partSuffix), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
	saveMu.Unlock()
	if err != nil {
		return "", friendlyFSErr(err)
	}
	tmpName := tmp.Name()
	n, err := io.Copy(tmp, body)
	if cerr := tmp.Close(); err == nil {
		err = cerr
	}
	if err == nil && size >= 0 && n != size {
		err = errors.New("envoi interrompu")
	}
	if err != nil {
		os.Remove(tmpName)
		return "", err
	}
	saveMu.Lock()
	defer saveMu.Unlock()
	final = freeName(dir, final) // someone else may have taken it meanwhile
	if err := os.Rename(tmpName, filepath.Join(dir, final)); err != nil {
		os.Remove(tmpName)
		return "", friendlyFSErr(err)
	}
	return final, nil
}

// serveFile sends a file (with resume support).
func serveFile(w http.ResponseWriter, r *http.Request, p string, inline bool) {
	p, err := cleanAbs(p)
	if err != nil {
		writeErr(w, err.Error(), nil)
		return
	}
	f, err := os.Open(p)
	if err != nil {
		writeErr(w, friendlyFSErr(err).Error(), nil)
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil || st.IsDir() {
		writeErr(w, "ce n'est pas un fichier", nil)
		return
	}
	setDisposition(w, filepath.Base(p), inline)
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if inline {
		// Shown in the browser: never as a page of this site.
		w.Header().Set("Content-Security-Policy", "sandbox")
	}
	http.ServeContent(w, r, "", st.ModTime(), f)
}

func setDisposition(w http.ResponseWriter, name string, inline bool) {
	kind := "attachment"
	if inline {
		kind = "inline"
	}
	if ct := mime.TypeByExtension(filepath.Ext(name)); ct != "" {
		w.Header().Set("Content-Type", ct)
	} else {
		w.Header().Set("Content-Type", "application/octet-stream")
	}
	w.Header().Set("Content-Disposition", mime.FormatMediaType(kind, map[string]string{"filename": name}))
}

// serveZip sends a folder as a .zip made on the fly.
func serveZip(w http.ResponseWriter, p string) {
	p, err := cleanAbs(p)
	if err != nil {
		writeErr(w, err.Error(), nil)
		return
	}
	if st, err := os.Stat(p); err != nil || !st.IsDir() {
		writeErr(w, "ce dossier n'existe plus", nil)
		return
	}
	name := filepath.Base(p)
	if name == "" || name == string(filepath.Separator) || strings.HasSuffix(name, ":\\") {
		name = "disque"
	}
	setDisposition(w, name+".zip", false)
	zw := zip.NewWriter(w)
	_ = filepath.WalkDir(p, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil // unreadable: skipped
		}
		rel, _ := filepath.Rel(p, path)
		if rel == "." {
			return nil
		}
		rel = filepath.ToSlash(rel)
		if d.IsDir() {
			_, _ = zw.Create(rel + "/")
			return nil
		}
		if !d.Type().IsRegular() || strings.HasSuffix(d.Name(), partSuffix) {
			return nil
		}
		f, err := os.Open(path)
		if err != nil {
			return nil
		}
		defer f.Close()
		info, _ := d.Info()
		h := &zip.FileHeader{Name: rel, Method: zip.Deflate}
		if info != nil {
			h.Modified = info.ModTime()
		}
		out, err := zw.CreateHeader(h)
		if err != nil {
			return err
		}
		_, err = io.Copy(out, f)
		return err // a write error: the browser is gone
	})
	_ = zw.Close()
}

// ---------------------------------------------------------------------
// The phones: reached through the request they keep waiting
// ---------------------------------------------------------------------

type phoneJob struct {
	ID   string `json:"id"`
	Op   string `json:"op"` // roots, list, get, put
	Path string `json:"path,omitempty"`
	Dir  string `json:"dir,omitempty"`
	Name string `json:"name,omitempty"`
	Size int64  `json:"size,omitempty"`

	reply    chan phoneReply
	blob     io.Reader     // put: the file being sent
	blobDone chan error    // put: the phone has read it
	gone     chan struct{} // the person asking gave up
	link     *phoneLink
}

type phoneReply struct {
	status int
	header http.Header
	body   io.Reader
	done   chan struct{} // closed by the asker when it has read body
}

type phoneLink struct {
	id       string // hash of the phone's certificate
	name     string
	lastSeen time.Time
	polling  int
	jobs     chan *phoneJob
}

type fileServer struct {
	a *Core

	mu     sync.Mutex
	phones map[string]*phoneLink
	jobs   map[string]*phoneJob

	devMu    sync.Mutex
	devCerts map[string]string // sha256 of certificate -> device name
	devAt    time.Time

	tlsMu   sync.Mutex
	tlsCert *tls.Certificate
	tlsAt   time.Time
	tlsErr  error

	web webAuth

	listenErr string
	running   bool
}

func newFileServer(a *Core) *fileServer {
	return &fileServer{a: a, phones: map[string]*phoneLink{}, jobs: map[string]*phoneJob{}}
}

func certID(der []byte) string {
	h := sha256.Sum256(der)
	return hex.EncodeToString(h[:])
}

// pairedDevice tells whether a certificate belongs to a phone paired with
// Sunshine, and its name there.
func (s *fileServer) pairedDevice(der []byte) (string, bool) {
	id := certID(der)
	s.devMu.Lock()
	defer s.devMu.Unlock()
	if name, ok := s.devCerts[id]; ok && time.Since(s.devAt) < 10*time.Minute {
		return name, true
	}
	if time.Since(s.devAt) < 3*time.Second && s.devCerts != nil {
		return "", false // read a moment ago: not paired
	}
	m, err := readDeviceCerts()
	if err != nil {
		log.Printf("files: paired devices: %v", err)
		if s.devCerts != nil {
			name, ok := s.devCerts[id]
			return name, ok
		}
		return "", false
	}
	s.devCerts, s.devAt = m, time.Now()
	name, ok := m[id]
	return name, ok
}

func readDeviceCerts() (map[string]string, error) {
	dir := cachedConfigDir()
	if dir == "" {
		return nil, errors.New("dossier de Sunshine introuvable")
	}
	data, err := os.ReadFile(stateFile(dir))
	if err != nil {
		return nil, err
	}
	var st struct {
		Root struct {
			NamedDevices []struct {
				Name string `json:"name"`
				Cert string `json:"cert"`
			} `json:"named_devices"`
		} `json:"root"`
	}
	if err := json.Unmarshal(data, &st); err != nil {
		return nil, err
	}
	m := map[string]string{}
	for _, d := range st.Root.NamedDevices {
		rest := []byte(d.Cert)
		for {
			var b *pem.Block
			b, rest = pem.Decode(rest)
			if b == nil {
				break
			}
			if b.Type == "CERTIFICATE" {
				name := d.Name
				if name == "" {
					name = "Téléphone"
				}
				m[certID(b.Bytes)] = name
			}
		}
	}
	return m, nil
}

// sunshineCert is the certificate Sunshine shows to phones (and that they
// remember when they pair): Eclipse shows the same one.
func (s *fileServer) sunshineCert() (*tls.Certificate, error) {
	s.tlsMu.Lock()
	defer s.tlsMu.Unlock()
	if s.tlsCert != nil && time.Since(s.tlsAt) < 5*time.Minute {
		return s.tlsCert, nil
	}
	if s.tlsErr != nil && time.Since(s.tlsAt) < 10*time.Second {
		return nil, s.tlsErr
	}
	c, err := loadSunshineCert()
	s.tlsAt = time.Now()
	if err != nil {
		s.tlsErr = err
		if s.tlsCert != nil {
			return s.tlsCert, nil
		}
		return nil, err
	}
	s.tlsCert, s.tlsErr = c, nil
	return c, nil
}

func loadSunshineCert() (*tls.Certificate, error) {
	if p := os.Getenv("ECLIPSE_FILES_CERT"); p != "" { // tests
		c, err := tls.LoadX509KeyPair(p, os.Getenv("ECLIPSE_FILES_KEY"))
		return &c, err
	}
	dir := cachedConfigDir()
	if dir == "" {
		return nil, errors.New("dossier de Sunshine introuvable")
	}
	conf := readSunshineConf(dir)
	abs := func(v, def string) string {
		if v == "" {
			v = def
		}
		if !filepath.IsAbs(v) {
			v = filepath.Join(dir, v)
		}
		return v
	}
	c, err := tls.LoadX509KeyPair(abs(conf["cert"], "credentials/cacert.pem"), abs(conf["pkey"], "credentials/cakey.pem"))
	if err != nil {
		return nil, err
	}
	return &c, nil
}

func (s *fileServer) link(id, name string) *phoneLink {
	s.mu.Lock()
	defer s.mu.Unlock()
	l := s.phones[id]
	if l == nil {
		l = &phoneLink{id: id, jobs: make(chan *phoneJob, 16)}
		s.phones[id] = l
		log.Printf("files: phone %q connected", name)
	}
	l.name = name
	return l
}

type deviceView struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Kind   string `json:"kind"` // pc, phone
	Online bool   `json:"online"`
}

func (s *fileServer) devices(includePC bool, except string) []deviceView {
	out := []deviceView{}
	if includePC {
		host, _ := os.Hostname()
		out = append(out, deviceView{ID: "pc", Name: host, Kind: "pc", Online: true})
	}
	s.mu.Lock()
	var ps []deviceView
	for _, l := range s.phones {
		if l.id == except || (l.lastSeen.IsZero() && l.polling == 0) {
			continue // never shared its files (only browsed this PC's)
		}
		on := l.polling > 0 || time.Since(l.lastSeen) < phoneOnline
		if !on && time.Since(l.lastSeen) > 7*24*time.Hour {
			continue
		}
		ps = append(ps, deviceView{ID: l.id[:16], Name: l.name, Kind: "phone", Online: on})
	}
	s.mu.Unlock()
	sort.Slice(ps, func(i, j int) bool {
		if ps[i].Online != ps[j].Online {
			return ps[i].Online
		}
		return strings.ToLower(ps[i].Name) < strings.ToLower(ps[j].Name)
	})
	return append(out, ps...)
}

func (s *fileServer) phoneByShortID(id string) *phoneLink {
	if len(id) != 16 {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, l := range s.phones {
		if strings.HasPrefix(l.id, id) {
			return l
		}
	}
	return nil
}

var errPhoneAway = errors.New("le téléphone n'est pas joignable : ouvre Eclipse dessus (avec le partage de fichiers activé)")

// ask sends a job to a phone and waits for the beginning of its answer.
// The caller must close reply.done when it has read reply.body.
func (s *fileServer) ask(ctx context.Context, l *phoneLink, j *phoneJob) (phoneReply, error) {
	j.ID = randHex(12)
	j.reply = make(chan phoneReply, 1)
	j.gone = make(chan struct{})
	j.link = l
	s.mu.Lock()
	on := l.polling > 0 || time.Since(l.lastSeen) < phoneOnline
	if on {
		s.jobs[j.ID] = j
	}
	s.mu.Unlock()
	if !on {
		return phoneReply{}, errPhoneAway
	}
	defer func() {
		s.mu.Lock()
		delete(s.jobs, j.ID)
		s.mu.Unlock()
		close(j.gone)
	}()
	select {
	case l.jobs <- j:
	default:
		return phoneReply{}, errors.New("le téléphone est occupé, réessaie dans un instant")
	}
	t := time.NewTimer(replyWait)
	defer t.Stop()
	select {
	case r := <-j.reply:
		return r, nil
	case <-t.C:
		return phoneReply{}, errors.New("le téléphone ne répond pas")
	case <-ctx.Done():
		return phoneReply{}, ctx.Err()
	}
}

// phoneJSON runs a roots/list job and decodes its JSON answer.
func (s *fileServer) phoneJSON(ctx context.Context, l *phoneLink, j *phoneJob) (json.RawMessage, error) {
	r, err := s.ask(ctx, l, j)
	if err != nil {
		return nil, err
	}
	defer close(r.done)
	data, err := io.ReadAll(io.LimitReader(r.body, 32<<20))
	if err != nil {
		return nil, err
	}
	if r.status != 200 {
		return nil, errors.New(replyError(data))
	}
	if !json.Valid(data) {
		return nil, errors.New("réponse illisible du téléphone")
	}
	return data, nil
}

func replyError(data []byte) string {
	var e struct {
		Error string `json:"error"`
	}
	if json.Unmarshal(data, &e) == nil && e.Error != "" {
		return e.Error
	}
	return "le téléphone n'a pas pu le faire"
}

// phoneGet streams a phone's file to w.
func (s *fileServer) phoneGet(w http.ResponseWriter, r *http.Request, l *phoneLink, p string, inline bool) {
	rep, err := s.ask(r.Context(), l, &phoneJob{Op: "get", Path: p})
	if err != nil {
		writeErr(w, err.Error(), nil)
		return
	}
	defer close(rep.done)
	if rep.status != 200 {
		data, _ := io.ReadAll(io.LimitReader(rep.body, 1<<16))
		writeErr(w, replyError(data), nil)
		return
	}
	name := rep.header.Get("X-Name")
	if n, err := url.PathUnescape(name); err == nil {
		name = n
	}
	if name == "" {
		name = filepath.Base(strings.ReplaceAll(p, "\\", "/"))
	}
	setDisposition(w, name, inline)
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if inline {
		w.Header().Set("Content-Security-Policy", "sandbox")
	}
	if n, err := strconv.ParseInt(rep.header.Get("X-Size"), 10, 64); err == nil && n >= 0 {
		w.Header().Set("Content-Length", strconv.FormatInt(n, 10))
	}
	_, _ = io.Copy(w, rep.body)
}

// phoneFetch copies a phone's file into a local file (the PC window's
// "Télécharger": the file lands in Downloads\Eclipse).
func (s *fileServer) phoneFetch(ctx context.Context, l *phoneLink, p, destDir string) (string, error) {
	rep, err := s.ask(ctx, l, &phoneJob{Op: "get", Path: p})
	if err != nil {
		return "", err
	}
	defer close(rep.done)
	if rep.status != 200 {
		data, _ := io.ReadAll(io.LimitReader(rep.body, 1<<16))
		return "", errors.New(replyError(data))
	}
	name := rep.header.Get("X-Name")
	if n, err := url.PathUnescape(name); err == nil {
		name = n
	}
	if name == "" {
		name = filepath.Base(strings.ReplaceAll(p, "\\", "/"))
	}
	size := int64(-1)
	if n, err := strconv.ParseInt(rep.header.Get("X-Size"), 10, 64); err == nil {
		size = n
	}
	if err := os.MkdirAll(destDir, 0o755); err != nil {
		return "", err
	}
	return saveFile(destDir, name, rep.body, size)
}

// phonePut sends body to a phone's folder; returns the name it got there.
func (s *fileServer) phonePut(ctx context.Context, l *phoneLink, dir, name string, size int64, body io.Reader) (string, error) {
	j := &phoneJob{Op: "put", Dir: dir, Name: name, Size: size, blob: body, blobDone: make(chan error, 1)}
	rep, err := s.ask(ctx, l, j)
	if err != nil {
		return "", err
	}
	defer close(rep.done)
	data, _ := io.ReadAll(io.LimitReader(rep.body, 1<<16))
	if rep.status != 200 {
		return "", errors.New(replyError(data))
	}
	var ok struct {
		Name string `json:"name"`
	}
	_ = json.Unmarshal(data, &ok)
	return ok.Name, nil
}

// ---------------------------------------------------------------------
// The port: TLS (phones) and plain HTTP (browsers) on the same number
// ---------------------------------------------------------------------

// allowedPeer: home network, Tailscale or this PC; never the Internet.
func allowedPeer(addr net.Addr) bool {
	ta, ok := addr.(*net.TCPAddr)
	if !ok {
		return false
	}
	ip := ta.IP
	if v4 := ip.To4(); v4 != nil {
		ip = v4
		if v4[0] == 100 && v4[1]&0xC0 == 64 {
			return true // Tailscale
		}
	}
	return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast()
}

type peekConn struct {
	net.Conn
	r *bufio.Reader
}

func (c *peekConn) Read(b []byte) (int, error) { return c.r.Read(b) }

// sniffListener hands out TLS connections for clients starting with a TLS
// handshake and plain ones for the others.
type sniffListener struct {
	net.Listener
	conns  chan net.Conn
	tlsCfg *tls.Config
	done   chan struct{}
	once   sync.Once
}

func newSniffListener(ln net.Listener, cfg *tls.Config) *sniffListener {
	s := &sniffListener{Listener: ln, conns: make(chan net.Conn), tlsCfg: cfg, done: make(chan struct{})}
	go s.loop()
	return s
}

func (s *sniffListener) loop() {
	for {
		c, err := s.Listener.Accept()
		if err != nil {
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				time.Sleep(50 * time.Millisecond)
				continue
			}
			s.once.Do(func() { close(s.done) })
			return
		}
		if !allowedPeer(c.RemoteAddr()) {
			c.Close()
			continue
		}
		go func(c net.Conn) {
			_ = c.SetReadDeadline(time.Now().Add(15 * time.Second))
			br := bufio.NewReader(c)
			b, err := br.Peek(1)
			_ = c.SetReadDeadline(time.Time{})
			if err != nil {
				c.Close()
				return
			}
			var out net.Conn = &peekConn{Conn: c, r: br}
			if b[0] == 0x16 {
				out = tls.Server(out, s.tlsCfg)
			}
			select {
			case s.conns <- out:
			case <-s.done:
				c.Close()
			}
		}(c)
	}
}

func (s *sniffListener) Accept() (net.Conn, error) {
	select {
	case c := <-s.conns:
		return c, nil
	case <-s.done:
		return nil, net.ErrClosed
	}
}

func (s *sniffListener) Close() error {
	s.once.Do(func() { close(s.done) })
	return s.Listener.Close()
}

// start opens the port (tries again later if it is taken).
func (s *fileServer) start() {
	port := filesPort
	if v, err := strconv.Atoi(os.Getenv("ECLIPSE_FILES_PORT")); err == nil && v > 0 {
		port = v
	}
	cfg := &tls.Config{
		MinVersion: tls.VersionTLS12,
		ClientAuth: tls.RequestClientCert,
		GetCertificate: func(*tls.ClientHelloInfo) (*tls.Certificate, error) {
			return s.sunshineCert()
		},
	}
	for i := 0; ; i++ {
		ln, err := net.Listen("tcp", fmt.Sprintf(":%d", port))
		if err != nil {
			s.mu.Lock()
			s.listenErr = err.Error()
			s.mu.Unlock()
			if i == 0 {
				log.Printf("files: port %d: %v", port, err)
			}
			time.Sleep(10 * time.Second)
			continue
		}
		s.mu.Lock()
		s.listenErr, s.running = "", true
		s.mu.Unlock()
		log.Printf("files: listening on port %d", port)
		openFirewall(port)
		srv := &http.Server{Handler: s.handler(), ReadHeaderTimeout: 20 * time.Second}
		err = srv.Serve(newSniffListener(ln, cfg))
		log.Printf("files: server stopped: %v", err)
		s.mu.Lock()
		s.running = false
		s.mu.Unlock()
		time.Sleep(5 * time.Second)
	}
}

// who is asking: a paired phone (TLS) or a signed-in browser.
type client struct {
	phone *phoneLink // nil for a browser
}

func (s *fileServer) identify(w http.ResponseWriter, r *http.Request) (*client, bool) {
	if r.TLS != nil {
		if len(r.TLS.PeerCertificates) == 0 {
			http.Error(w, "certificat manquant", http.StatusUnauthorized)
			return nil, false
		}
		der := r.TLS.PeerCertificates[0].Raw
		name, ok := s.pairedDevice(der)
		if !ok {
			http.Error(w, "appareil non connecté à ce PC", http.StatusForbidden)
			return nil, false
		}
		return &client{phone: s.link(certID(der), name)}, true
	}
	if !s.web.enabled() {
		writeErrCode(w, http.StatusForbidden, "L'accès par navigateur est désactivé sur ce PC.")
		return nil, false
	}
	if !s.web.validCookie(r) {
		writeErrCode(w, http.StatusUnauthorized, "Connexion nécessaire")
		return nil, false
	}
	// Changes need a header a page of another site cannot add.
	if r.Method != http.MethodGet && r.Header.Get("X-Eclipse") != "1" {
		writeErrCode(w, http.StatusForbidden, "requête refusée")
		return nil, false
	}
	return &client{}, true
}

func writeErrCode(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

func (s *fileServer) handler() http.Handler {
	mux := http.NewServeMux()

	// --- browsers: the page and signing in ---
	sub, _ := fs.Sub(uiFiles, "ui")
	static := http.FileServer(http.FS(sub))
	page := func(w http.ResponseWriter, r *http.Request) {
		if r.TLS != nil {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "no-referrer")
		static.ServeHTTP(w, r)
	}
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		if r.TLS != nil {
			http.NotFound(w, r)
			return
		}
		http.Redirect(w, r, "/web/", http.StatusFound)
	})
	mux.HandleFunc("GET /web/", page)
	for _, f := range []string{"/files.js", "/files.css", "/logo.png", "/app.css", "/intro.js", "/intro.css"} {
		mux.HandleFunc("GET "+f, page)
	}
	mux.HandleFunc("POST /login", func(w http.ResponseWriter, r *http.Request) {
		if r.TLS != nil {
			http.NotFound(w, r)
			return
		}
		s.web.login(w, r)
	})
	mux.HandleFunc("POST /logout", func(w http.ResponseWriter, r *http.Request) {
		s.web.logout(w, r)
		writeJSON(w, map[string]bool{"ok": true})
	})
	mux.HandleFunc("GET /me", func(w http.ResponseWriter, r *http.Request) {
		host, _ := os.Hostname()
		writeJSON(w, map[string]any{"enabled": s.web.enabled(), "signedIn": s.web.validCookie(r), "pc": host})
	})

	// --- everyone signed in: the devices and their files ---
	x := http.NewServeMux()
	s.deviceRoutes(x, "")
	mux.Handle("/x/", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, ok := s.identify(w, r)
		if !ok {
			return
		}
		r = r.WithContext(context.WithValue(r.Context(), clientKey{}, c))
		x.ServeHTTP(w, r)
	}))

	// --- phones: the request kept waiting, and their answers ---
	mux.HandleFunc("GET /p/poll", func(w http.ResponseWriter, r *http.Request) {
		c, ok := s.identify(w, r)
		if !ok || c.phone == nil {
			if ok {
				http.Error(w, "réservé aux téléphones", http.StatusForbidden)
			}
			return
		}
		s.poll(w, r, c.phone)
	})
	mux.HandleFunc("GET /p/blob/{id}", func(w http.ResponseWriter, r *http.Request) {
		c, ok := s.identify(w, r)
		if !ok || c.phone == nil {
			return
		}
		j := s.jobFor(r.PathValue("id"), c.phone)
		if j == nil || j.Op != "put" {
			http.NotFound(w, r)
			return
		}
		if j.Size >= 0 {
			w.Header().Set("Content-Length", strconv.FormatInt(j.Size, 10))
		}
		_, err := io.Copy(w, j.blob)
		select {
		case j.blobDone <- err:
		default:
		}
	})
	mux.HandleFunc("POST /p/reply/{id}", func(w http.ResponseWriter, r *http.Request) {
		c, ok := s.identify(w, r)
		if !ok || c.phone == nil {
			return
		}
		j := s.jobFor(r.PathValue("id"), c.phone)
		if j == nil {
			http.NotFound(w, r)
			return
		}
		status, _ := strconv.Atoi(r.Header.Get("X-Status"))
		if status == 0 {
			status = 200
		}
		rep := phoneReply{status: status, header: r.Header, body: r.Body, done: make(chan struct{})}
		select {
		case j.reply <- rep:
		case <-j.gone:
			http.Error(w, "plus attendu", http.StatusGone)
			return
		}
		select {
		case <-rep.done:
		case <-r.Context().Done():
		}
		writeJSON(w, map[string]bool{"ok": true})
	})
	return mux
}

type clientKey struct{}

func (s *fileServer) jobFor(id string, l *phoneLink) *phoneJob {
	s.mu.Lock()
	defer s.mu.Unlock()
	j := s.jobs[id]
	if j == nil || j.link != l {
		return nil
	}
	return j
}

func (s *fileServer) poll(w http.ResponseWriter, r *http.Request, l *phoneLink) {
	s.mu.Lock()
	l.polling++
	l.lastSeen = time.Now()
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		l.polling--
		l.lastSeen = time.Now()
		s.mu.Unlock()
	}()
	t := time.NewTimer(pollWait)
	defer t.Stop()
	for {
		select {
		case j := <-l.jobs:
			select {
			case <-j.gone:
				continue // nobody waits for it any more
			default:
			}
			writeJSON(w, j)
			return
		case <-t.C:
			w.WriteHeader(http.StatusNoContent)
			return
		case <-r.Context().Done():
			return
		}
	}
}

// deviceRoutes: /x/devices and /x/{device}/… (prefix "" on the files
// port, "/api" in the PC's own window).
func (s *fileServer) deviceRoutes(mux *http.ServeMux, prefix string) {
	who := func(r *http.Request) *client {
		c, _ := r.Context().Value(clientKey{}).(*client)
		if c == nil {
			c = &client{} // the PC's own window
		}
		return c
	}
	isLocalWindow := prefix != ""

	mux.HandleFunc("GET "+prefix+"/x/devices", func(w http.ResponseWriter, r *http.Request) {
		c := who(r)
		except := ""
		if c.phone != nil {
			except = c.phone.id
		}
		writeJSON(w, map[string]any{"devices": s.devices(!isLocalWindow, except)})
	})

	// target: "pc" (this PC) or a phone.
	target := func(w http.ResponseWriter, r *http.Request) (*phoneLink, bool) {
		dev := r.PathValue("dev")
		if dev == "pc" {
			if isLocalWindow {
				writeErr(w, "appareil inconnu", nil)
				return nil, false
			}
			return nil, true
		}
		l := s.phoneByShortID(dev)
		if l == nil {
			writeErr(w, "appareil inconnu", nil)
			return nil, false
		}
		return l, true
	}

	mux.HandleFunc("GET "+prefix+"/x/{dev}/roots", func(w http.ResponseWriter, r *http.Request) {
		l, ok := target(w, r)
		if !ok {
			return
		}
		if l == nil {
			writeJSON(w, map[string]any{"roots": fileRoots()})
			return
		}
		data, err := s.phoneJSON(r.Context(), l, &phoneJob{Op: "roots"})
		if err != nil {
			writeErr(w, err.Error(), nil)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(data)
	})

	mux.HandleFunc("GET "+prefix+"/x/{dev}/list", func(w http.ResponseWriter, r *http.Request) {
		l, ok := target(w, r)
		if !ok {
			return
		}
		p := r.URL.Query().Get("path")
		if l == nil {
			out, err := listDir(p)
			if err != nil {
				writeErr(w, err.Error(), nil)
				return
			}
			writeJSON(w, out)
			return
		}
		data, err := s.phoneJSON(r.Context(), l, &phoneJob{Op: "list", Path: p})
		if err != nil {
			writeErr(w, err.Error(), nil)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(data)
	})

	mux.HandleFunc("GET "+prefix+"/x/{dev}/get", func(w http.ResponseWriter, r *http.Request) {
		l, ok := target(w, r)
		if !ok {
			return
		}
		p, inline := r.URL.Query().Get("path"), r.URL.Query().Get("inline") == "1"
		if l == nil {
			serveFile(w, r, p, inline)
			return
		}
		s.phoneGet(w, r, l, p, inline)
	})

	mux.HandleFunc("GET "+prefix+"/x/{dev}/zip", func(w http.ResponseWriter, r *http.Request) {
		l, ok := target(w, r)
		if !ok {
			return
		}
		if l != nil {
			writeErr(w, "un dossier entier ne se télécharge que depuis un PC", nil)
			return
		}
		serveZip(w, r.URL.Query().Get("path"))
	})

	mux.HandleFunc("POST "+prefix+"/x/{dev}/put", func(w http.ResponseWriter, r *http.Request) {
		l, ok := target(w, r)
		if !ok {
			return
		}
		q := r.URL.Query()
		size := r.ContentLength
		if l == nil {
			name, err := saveFile(q.Get("dir"), q.Get("name"), r.Body, size)
			if err != nil {
				writeErr(w, "Envoi impossible : "+err.Error(), nil)
				return
			}
			log.Printf("files: received %q", name)
			writeJSON(w, map[string]string{"name": name})
			return
		}
		name, err := s.phonePut(r.Context(), l, q.Get("dir"), q.Get("name"), size, r.Body)
		if err != nil {
			writeErr(w, "Envoi impossible : "+err.Error(), nil)
			return
		}
		writeJSON(w, map[string]string{"name": name})
	})

	if !isLocalWindow {
		return
	}

	// The PC's own window: a phone's file saved in Downloads\Eclipse.
	mux.HandleFunc("POST "+prefix+"/x/{dev}/fetch", func(w http.ResponseWriter, r *http.Request) {
		l, ok := target(w, r)
		if !ok || l == nil {
			return
		}
		var req struct {
			Path string `json:"path"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		dest := downloadsDir()
		name, err := s.phoneFetch(r.Context(), l, req.Path, dest)
		if err != nil {
			writeErr(w, "Téléchargement impossible : "+err.Error(), nil)
			return
		}
		writeJSON(w, map[string]string{"name": name, "folder": dest, "path": filepath.Join(dest, name)})
	})
	mux.HandleFunc("POST "+prefix+"/files/show", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Path string `json:"path"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		p, err := cleanAbs(req.Path)
		if err == nil && strings.HasPrefix(p, downloadsDir()) {
			showInFolder(p)
		}
		writeJSON(w, map[string]bool{"ok": true})
	})
	mux.HandleFunc("GET "+prefix+"/files/state", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, s.state())
	})
	mux.HandleFunc("POST "+prefix+"/files/web", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			On      *bool `json:"on"`
			NewCode bool  `json:"newCode"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if req.On != nil {
			s.web.setEnabled(*req.On)
		}
		if req.NewCode {
			s.web.newCode()
		}
		writeJSON(w, s.state())
	})
	mux.HandleFunc("POST "+prefix+"/files/background", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			On bool `json:"on"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		_ = updateSettings(func(st *settings) { st.NoBackground = !req.On })
		setAutostart(req.On)
		writeJSON(w, s.state())
	})
}

// downloadsDir is where the PC's window saves files from phones.
func downloadsDir() string {
	if d := os.Getenv("ECLIPSE_DOWNLOADS"); d != "" {
		return d
	}
	return filepath.Join(userDownloads(), "Eclipse")
}

func (s *fileServer) state() map[string]any {
	s.mu.Lock()
	running, lerr := s.running, s.listenErr
	s.mu.Unlock()
	port := filesPort
	if v, err := strconv.Atoi(os.Getenv("ECLIPSE_FILES_PORT")); err == nil && v > 0 {
		port = v
	}
	var addrs []string
	if ip, _ := remoteForQR(); ip != "" {
		addrs = append(addrs, fmt.Sprintf("http://%s:%d", ip, port))
	}
	for _, ip := range localIPs() {
		addrs = append(addrs, fmt.Sprintf("http://%s:%d", ip, port))
	}
	_, certErr := s.sunshineCert()
	cerr := ""
	if certErr != nil {
		cerr = certErr.Error()
	}
	return map[string]any{
		"running": running, "error": lerr, "port": port,
		"web": s.web.enabled(), "code": s.web.code(), "addresses": addrs,
		"background": !loadSettings().NoBackground, "certError": cerr,
		"phones": s.devices(false, ""), "downloads": downloadsDir(),
	}
}

// ---------------------------------------------------------------------
// Browsers: access code and sessions
// ---------------------------------------------------------------------

type webSecrets struct {
	Off      bool             `json:"off,omitempty"`
	Code     string           `json:"code"`
	Sessions map[string]int64 `json:"sessions,omitempty"` // sha256(cookie) -> expiry (unix)
}

type webAuth struct {
	mu       sync.Mutex
	loaded   bool
	sec      webSecrets
	failures map[string][]time.Time // per address
	allFails []time.Time
}

func webSecretsPath() string { return filepath.Join(dataDir(), "files.dat") }

func (wa *webAuth) load() {
	if wa.loaded {
		return
	}
	wa.loaded = true
	wa.failures = map[string][]time.Time{}
	if data, err := os.ReadFile(webSecretsPath()); err == nil {
		if plain, err := unprotect(data); err == nil {
			_ = json.Unmarshal(plain, &wa.sec)
		}
	}
	if len(wa.sec.Code) != webCodeLen {
		wa.sec.Code = randomCode()
		wa.saveLocked()
	}
	if wa.sec.Sessions == nil {
		wa.sec.Sessions = map[string]int64{}
	}
}

func (wa *webAuth) saveLocked() {
	data, _ := json.Marshal(wa.sec)
	enc, err := protect(data)
	if err != nil {
		return
	}
	_ = os.MkdirAll(dataDir(), 0o755)
	tmp := webSecretsPath() + ".tmp"
	if os.WriteFile(tmp, enc, 0o600) == nil {
		_ = os.Rename(tmp, webSecretsPath())
	}
}

func randomCode() string {
	t := randomToken() // 8 symbols out of 32 (no 0/O, 1/l)
	return t[:webCodeLen]
}

func (wa *webAuth) enabled() bool {
	wa.mu.Lock()
	defer wa.mu.Unlock()
	wa.load()
	return !wa.sec.Off
}

func (wa *webAuth) setEnabled(on bool) {
	wa.mu.Lock()
	defer wa.mu.Unlock()
	wa.load()
	wa.sec.Off = !on
	wa.saveLocked()
}

func (wa *webAuth) code() string {
	wa.mu.Lock()
	defer wa.mu.Unlock()
	wa.load()
	return wa.sec.Code[:4] + "-" + wa.sec.Code[4:]
}

// newCode: a new code, and every browser has to sign in again.
func (wa *webAuth) newCode() {
	wa.mu.Lock()
	defer wa.mu.Unlock()
	wa.load()
	wa.sec.Code = randomCode()
	wa.sec.Sessions = map[string]int64{}
	wa.saveLocked()
}

func hashCookie(v string) string {
	h := sha256.Sum256([]byte(v))
	return hex.EncodeToString(h[:])
}

func (wa *webAuth) validCookie(r *http.Request) bool {
	c, err := r.Cookie(cookieName)
	if err != nil || len(c.Value) != 64 {
		return false
	}
	wa.mu.Lock()
	defer wa.mu.Unlock()
	wa.load()
	if wa.sec.Off {
		return false
	}
	exp, ok := wa.sec.Sessions[hashCookie(c.Value)]
	return ok && time.Now().Unix() < exp
}

func peerHost(r *http.Request) string {
	h, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return h
}

func recent(ts []time.Time, d time.Duration) []time.Time {
	out := ts[:0]
	for _, t := range ts {
		if time.Since(t) < d {
			out = append(out, t)
		}
	}
	return out
}

func (wa *webAuth) login(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Code string `json:"code"`
	}
	_ = json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req)
	code := strings.ToLower(strings.NewReplacer("-", "", " ", "").Replace(req.Code))
	host := peerHost(r)

	wa.mu.Lock()
	wa.load()
	if wa.sec.Off {
		wa.mu.Unlock()
		writeErrCode(w, http.StatusForbidden, "L'accès par navigateur est désactivé sur ce PC.")
		return
	}
	wa.failures[host] = recent(wa.failures[host], loginLockout)
	wa.allFails = recent(wa.allFails, loginLockout)
	if len(wa.failures[host]) >= 5 || len(wa.allFails) >= 30 {
		wa.mu.Unlock()
		writeErrCode(w, http.StatusTooManyRequests, "Trop d'essais. Réessaie dans 10 minutes.")
		return
	}
	if subtle.ConstantTimeCompare([]byte(code), []byte(wa.sec.Code)) != 1 {
		wa.failures[host] = append(wa.failures[host], time.Now())
		wa.allFails = append(wa.allFails, time.Now())
		wa.mu.Unlock()
		log.Printf("files: wrong code from %s", host)
		writeErrCode(w, http.StatusUnauthorized, "Code incorrect. Il est affiché dans Eclipse sur le PC, onglet Fichiers.")
		return
	}
	delete(wa.failures, host)
	tok := randHex(32)
	now := time.Now().Unix()
	for k, exp := range wa.sec.Sessions {
		if exp < now {
			delete(wa.sec.Sessions, k)
		}
	}
	wa.sec.Sessions[hashCookie(tok)] = time.Now().Add(sessionLife).Unix()
	wa.saveLocked()
	wa.mu.Unlock()
	log.Printf("files: browser signed in from %s", host)
	http.SetCookie(w, &http.Cookie{Name: cookieName, Value: tok, Path: "/", HttpOnly: true,
		SameSite: http.SameSiteStrictMode, MaxAge: int(sessionLife.Seconds())})
	writeJSON(w, map[string]bool{"ok": true})
}

func (wa *webAuth) logout(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie(cookieName); err == nil {
		wa.mu.Lock()
		wa.load()
		delete(wa.sec.Sessions, hashCookie(c.Value))
		wa.saveLocked()
		wa.mu.Unlock()
	}
	http.SetCookie(w, &http.Cookie{Name: cookieName, Value: "", Path: "/", MaxAge: -1, HttpOnly: true, SameSite: http.SameSiteStrictMode})
}
