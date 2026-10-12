package main

import (
	"crypto/rand"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"math"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const version = "3.0.0"

//go:embed ui
var uiFiles embed.FS

type appSpec struct {
	Name     string
	Cmd      string
	WorkDir  string
	Detached []string
}

type login struct {
	User string `json:"user"`
	Pass string `json:"pass"`
}

// ---------------------------------------------------------------------
// Progress reporting for the first-run setup
// ---------------------------------------------------------------------

// reporter is what platform installers use to report real progress.
type reporter interface {
	Download(done, total int64)
	Install(frac float64)       // 0..1, measured
	Busy(phase, message string) // no measurable progress available
}

type Core struct {
	sun *Sun

	mu          sync.Mutex
	phase       string // check, download, install, config, start, ready, error
	message     string
	detail      string
	errText     string
	percent     float64 // 0..100, or -1 when unknown
	needInstall bool
	running     bool
	updatedFrom string
	starting    bool   // Sunshine being turned back on by the user
	startErr    string // why turning it back on failed
	sunRunning  bool   // last answer of Sunshine (refreshed in the background)

	ml mlState
	qr qrState

	remote remoteState

	upd updateState

	files *fileServer
	show  chan struct{} // another launch of Eclipse asks for the window
	quit  chan struct{} // a newer Eclipse takes over

	dlStart time.Time

	lastPing time.Time
	pingMu   sync.Mutex
}

func newAppState() *Core {
	c := &Core{sun: newSun(), phase: "check", message: "Vérification…",
		show: make(chan struct{}, 1), quit: make(chan struct{})}
	c.files = newFileServer(c)
	go c.monitor()
	return c
}

// monitor keeps track of whether Sunshine answers, so the interface never
// waits on a slow network check.
func (a *Core) monitor() {
	for {
		a.mu.Lock()
		ready := a.phase == "ready"
		a.mu.Unlock()
		r := ready && a.sun.Ready()
		a.mu.Lock()
		a.sunRunning = r
		a.mu.Unlock()
		time.Sleep(1200 * time.Millisecond)
	}
}

// Ranges of the overall bar for each phase.
func (a *Core) span(phase string) (float64, float64) {
	if a.needInstall {
		switch phase {
		case "download":
			return 0, 50
		case "install":
			return 50, 88
		case "config":
			return 88, 93
		case "start":
			return 93, 100
		}
	}
	switch phase {
	case "config":
		return 0, 30
	case "start":
		return 30, 100
	}
	return 0, 0
}

func (a *Core) update(phase, msg, detail string, frac float64) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.phase, a.message, a.detail, a.errText = phase, msg, detail, ""
	if frac < 0 {
		a.percent = -1
		return
	}
	lo, hi := a.span(phase)
	p := lo + (hi-lo)*math.Min(1, frac)
	if a.percent >= 0 && p < a.percent {
		p = a.percent // never go backwards
	}
	a.percent = p
}

func (a *Core) fail(msg string, err error) {
	a.mu.Lock()
	a.phase, a.message, a.detail = "error", msg, ""
	if err != nil {
		a.errText = err.Error()
	}
	a.running = false
	a.mu.Unlock()
	log.Printf("[error] %s: %v", msg, err)
}

// reporter implementation
func (a *Core) Download(done, total int64) {
	if a.dlStart.IsZero() {
		a.dlStart = time.Now()
	}
	if total <= 0 {
		a.update("download", "Téléchargement de Sunshine…", humanBytes(done), -1)
		return
	}
	frac := float64(done) / float64(total)
	detail := humanBytes(done) + " sur " + humanBytes(total)
	if el := time.Since(a.dlStart).Seconds(); el > 1.5 && done > 0 {
		rest := (float64(total-done) / (float64(done) / el))
		detail += " · " + humanDuration(rest)
	}
	a.update("download", "Téléchargement de Sunshine…", detail, frac)
}

func (a *Core) Install(frac float64) {
	a.update("install", "Installation de Sunshine…", "Ne ferme pas la fenêtre", frac)
}

func (a *Core) Busy(phase, message string) {
	a.mu.Lock()
	cur := a.percent
	a.mu.Unlock()
	a.update(phase, message, "", -1)
	if cur >= 0 {
		a.mu.Lock()
		a.percent = cur // keep the bar where it was, just animate
		a.mu.Unlock()
	}
}

func humanBytes(n int64) string {
	mo := float64(n) / (1024 * 1024)
	return strings.Replace(fmt.Sprintf("%.1f Mo", mo), ".", ",", 1)
}

func humanDuration(s float64) string {
	switch {
	case s < 5:
		return "presque fini"
	case s < 60:
		return fmt.Sprintf("environ %d s restantes", int(math.Ceil(s/5)*5))
	default:
		return fmt.Sprintf("environ %d min restantes", int(math.Ceil(s/60)))
	}
}

// ---------------------------------------------------------------------
// Setup: install Sunshine, create credentials, start it, check access.
// ---------------------------------------------------------------------

func (a *Core) runSetup() {
	a.mu.Lock()
	if a.running {
		a.mu.Unlock()
		return
	}
	a.running = true
	a.percent = 0
	a.dlStart = time.Time{}
	a.mu.Unlock()
	skip := os.Getenv("ECLIPSE_SKIP_PLATFORM") == "1"

	if sim := os.Getenv("ECLIPSE_SIMULATE_URL"); sim != "" { // test hook
		a.mu.Lock()
		a.needInstall = true
		a.mu.Unlock()
		dest := filepath.Join(os.TempDir(), "sf-sim.bin")
		if err := download(sim, dest, 0, a); err != nil {
			a.fail("L'installation de Sunshine a échoué.", err)
			return
		}
		os.Remove(dest)
		for i := 0; i <= 20; i++ {
			a.Install(float64(i) / 20)
			time.Sleep(150 * time.Millisecond)
		}
	}

	if !skip && !sunshineInstalled() {
		a.mu.Lock()
		a.needInstall = true
		a.mu.Unlock()
		a.update("download", "Préparation du téléchargement…", "", 0)
		err := installSunshine(a)
		if err != nil || !sunshineInstalled() {
			if err == nil {
				err = errors.New("Sunshine n'a pas été trouvé après l'installation")
			}
			a.fail("L'installation de Sunshine a échoué.", err)
			return
		}
		a.update("install", "Sunshine est installé", "", 1)
	}

	a.update("config", "Réglage de ton PC…", "", 0)
	lg := loadLogin()
	fresh := false
	if lg == nil && !skip {
		a.update("config", "Création de tes accès sécurisés…", "", 0.3)
		var err error
		if lg, err = makeLogin(); err != nil {
			a.fail("Impossible de créer les accès Sunshine.", err)
			return
		}
		fresh = true
	}
	if lg != nil {
		a.sun.setLogin(lg.User, lg.Pass)
	}
	a.update("config", "Réglage de ton PC…", "", 1)

	if loadSettings().SunshineStopped && !fresh {
		// The user turned streaming off: respect it until they turn it back on.
		a.update("start", "Streaming arrêté", "", 1)
		a.mu.Lock()
		a.phase = "ready"
		a.running = false
		a.mu.Unlock()
		log.Printf("ready (Sunshine stopped by the user)")
		go a.autoUpdate()
		return
	}

	a.update("start", "Démarrage du streaming…", "", 0)
	if !skip && (fresh || !a.sun.Ready()) {
		a.update("start", "Démarrage du streaming…", "", 0.1)
		if err := restartSunshine(); err != nil {
			log.Printf("restart: %v", err)
		}
	}
	if !a.waitReady(4 * time.Minute) {
		a.fail("Sunshine ne répond pas.", errors.New("redémarre l'ordinateur puis relance Eclipse"))
		return
	}

	if err := a.sun.CheckLogin(); errors.Is(err, errAuth) && !skip {
		log.Printf("check login: %v", err)
		if err := a.repairLogin(); err != nil {
			a.fail("Sunshine refuse la connexion.", err)
			return
		}
	} else if err != nil && !errors.Is(err, errAuth) {
		log.Printf("check login: %v", err)
	}

	a.update("start", "Prêt à streamer", "", 1)
	a.mu.Lock()
	a.phase = "ready"
	a.running = false
	a.mu.Unlock()
	log.Printf("ready")
	go a.dedupeClients("")
	go a.remoteAuto()
	go a.autoUpdate()
}

// stopStreaming turns Sunshine off (and keeps it off until turned back on).
func (a *Core) stopStreaming() error {
	var err error
	if os.Getenv("ECLIPSE_SKIP_PLATFORM") == "1" {
		_, err = http.Get(a.sun.base + "/mock/stop") // test hook
	} else {
		err = stopSunshine()
	}
	if err != nil {
		return err
	}
	a.mu.Lock()
	a.sunRunning = false
	a.startErr = ""
	a.mu.Unlock()
	log.Printf("Sunshine stopped by the user")
	return updateSettings(func(s *settings) { s.SunshineStopped = true })
}

// startStreaming turns Sunshine back on. Every wait is bounded: if Sunshine
// does not come back, it is started directly, and if that fails too the
// interface gets a clear message instead of spinning forever.
func (a *Core) startStreaming() {
	a.mu.Lock()
	if a.starting {
		a.mu.Unlock()
		return
	}
	a.starting = true
	a.startErr = ""
	a.mu.Unlock()
	defer func() {
		a.mu.Lock()
		a.starting = false
		a.mu.Unlock()
	}()
	_ = updateSettings(func(s *settings) { s.SunshineStopped = false })

	skip := os.Getenv("ECLIPSE_SKIP_PLATFORM") == "1"
	max, noProc := 4*time.Minute, 25*time.Second
	if skip {
		max, noProc = 20*time.Second, 3*time.Second
		if os.Getenv("ECLIPSE_TEST_START_FAIL") == "" {
			go func() {
				time.Sleep(1500 * time.Millisecond)
				_, _ = http.Get(a.sun.base + "/mock/start") // test hook
			}()
		}
	} else {
		log.Printf("start requested | %s", serviceDiagnostics())
		if err := restartSunshine(); err != nil {
			log.Printf("start: %v", err)
		}
	}
	if a.waitSunshine(max, noProc, nil) {
		a.markStarted()
		return
	}
	// No Sunshine process came up at all: start it directly.
	if !skip {
		log.Printf("start: Sunshine did not come up (%s), starting it directly", serviceDiagnostics())
		if err := launchSunshineDirect(""); err != nil {
			log.Printf("start direct: %v", err)
		}
	}
	if a.waitSunshine(max, noProc, nil) {
		a.markStarted()
		return
	}
	if !skip {
		log.Printf("start: failed | %s | %s", serviceDiagnostics(), sunshineDiagnostics())
	}
	a.mu.Lock()
	a.startErr = "Sunshine n'a pas redémarré. Réessaie, ou redémarre l'ordinateur."
	a.mu.Unlock()
}

func (a *Core) markStarted() {
	a.mu.Lock()
	a.sunRunning = true
	a.mu.Unlock()
	log.Printf("Sunshine started by the user")
}

// processAlive tells whether Sunshine is at least running (maybe still
// starting up). Test runs simulate it.
func processAlive() bool {
	if os.Getenv("ECLIPSE_SKIP_PLATFORM") == "1" {
		return os.Getenv("ECLIPSE_TEST_START_FAIL") == ""
	}
	return sunshineProcessAlive()
}

// waitSunshine waits until Sunshine answers. On slow PCs it needs about a
// minute (it tests the graphics chip at every start), so it keeps waiting as
// long as the Sunshine process is alive, up to max. It gives up early when
// no Sunshine process has existed for noProc. tick is called every second.
func (a *Core) waitSunshine(max, noProc time.Duration, tick func(elapsed float64)) bool {
	start := time.Now()
	var missingSince time.Time
	for time.Since(start) < max {
		if a.sun.Ready() {
			return true
		}
		if processAlive() {
			missingSince = time.Time{}
		} else if missingSince.IsZero() {
			missingSince = time.Now()
		} else if time.Since(missingSince) > noProc {
			return false
		}
		if tick != nil {
			tick(time.Since(start).Seconds())
		}
		time.Sleep(800 * time.Millisecond)
	}
	return false
}

// waitReady is the first-run version, which moves the progress bar.
func (a *Core) waitReady(d time.Duration) bool {
	return a.waitSunshine(d, 25*time.Second, func(el float64) {
		a.update("start", "Démarrage du streaming…", "", 0.15+0.8*(1-math.Exp(-el/25)))
	})
}

func makeLogin() (*login, error) {
	lg := &login{User: "eclipse", Pass: randomPassword(20)}
	if err := applyLogin(lg); err != nil {
		return nil, err
	}
	if err := saveLogin(lg); err != nil {
		return nil, err
	}
	return lg, nil
}

// applyLogin gives Sunshine our login: officially through `sunshine --creds`,
// then checked (and written if needed) directly in its file.
func applyLogin(lg *login) error {
	if err := setSunshineCreds(lg.User, lg.Pass); err != nil {
		log.Printf("sunshine --creds: %v", err)
	}
	return ensureCreds(lg.User, lg.Pass)
}

// repairLogin is used when Sunshine refuses our login: it writes the login
// again, restarts Sunshine cleanly (stray copies included) and checks.
func (a *Core) repairLogin() error {
	lg := loadLogin()
	if lg == nil {
		lg = &login{User: "eclipse", Pass: randomPassword(20)}
		if err := saveLogin(lg); err != nil {
			return err
		}
	}
	a.sun.setLogin(lg.User, lg.Pass)
	var last error
	for attempt := 1; attempt <= 2; attempt++ {
		a.update("start", "Mise à jour des accès…", "", 0.2)
		if err := applyLogin(lg); err != nil {
			log.Printf("repair %d: %v", attempt, err)
			return err
		}
		if err := restartSunshine(); err != nil {
			log.Printf("repair %d restart: %v", attempt, err)
		}
		if !a.waitReady(4 * time.Minute) {
			return errors.New("Sunshine ne redémarre pas, redémarre l'ordinateur puis relance Eclipse")
		}
		last = a.sun.CheckLogin()
		log.Printf("repair %d: check login: %v | %s", attempt, last, sunshineDiagnostics())
		if last == nil || !errors.Is(last, errAuth) {
			return nil
		}
	}
	return fmt.Errorf("%v. Redémarre l'ordinateur puis clique sur Réessayer", last)
}

func randomPassword(n int) string {
	const chars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	b := make([]byte, n)
	_, _ = rand.Read(b)
	for i := range b {
		b[i] = chars[int(b[i])%len(chars)]
	}
	return string(b)
}

func randHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// ---------------------------------------------------------------------
// Saved data (kept across updates)
// ---------------------------------------------------------------------

func dataDir() string      { return filepath.Dir(loginPath()) }
func settingsPath() string { return filepath.Join(dataDir(), "settings.json") }

type settings struct {
	LastVersion     string   `json:"lastVersion"`
	SunshineStopped bool     `json:"sunshineStopped,omitempty"`
	Hosts           []mlHost `json:"hosts,omitempty"` // PCs added in the Moonlight tab
	// Playing away from home is set up by itself once (Eclipse 2.3);
	// RemoteOff: the person paused it, it stays paused.
	RemoteAuto string `json:"remoteAuto,omitempty"`
	RemoteOff  bool   `json:"remoteOff,omitempty"`
	// Eclipse 2.4: Eclipse stays on in the background (file sharing) when
	// its window is closed and starts with the computer, unless turned off.
	NoBackground bool `json:"noBackground,omitempty"`
}

var settingsMu sync.Mutex

func loadSettings() settings {
	settingsMu.Lock()
	defer settingsMu.Unlock()
	return readSettings()
}

func readSettings() settings {
	var s settings
	data, err := os.ReadFile(settingsPath())
	if err == nil {
		_ = json.Unmarshal(data, &s)
	}
	return s
}

func writeSettings(s settings) error {
	if err := os.MkdirAll(dataDir(), 0o755); err != nil {
		return err
	}
	data, _ := json.MarshalIndent(s, "", "  ")
	return os.WriteFile(settingsPath(), data, 0o644)
}

func saveSettings(s settings) error {
	settingsMu.Lock()
	defer settingsMu.Unlock()
	return writeSettings(s)
}

// updateSettings applies f to the saved settings atomically.
func updateSettings(f func(*settings)) error {
	settingsMu.Lock()
	defer settingsMu.Unlock()
	s := readSettings()
	f(&s)
	return writeSettings(s)
}

func loadLogin() *login {
	data, err := os.ReadFile(loginPath())
	if err != nil {
		return nil
	}
	data, err = unprotect(data)
	if err != nil {
		return nil
	}
	var lg login
	if json.Unmarshal(data, &lg) != nil || lg.User == "" {
		return nil
	}
	return &lg
}

func saveLogin(lg *login) error {
	data, _ := json.Marshal(lg)
	data, err := protect(data)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dataDir(), 0o700); err != nil {
		return err
	}
	return os.WriteFile(loginPath(), data, 0o600)
}

// migrateData brings data from any earlier version up to date. It returns
// a label of the previous version ("1.1.0", "StreamFacile 1.1.0"…) or "".
func migrateData() string {
	if label := migrateLegacy(); label != "" { // platform-specific: older app names
		s := loadSettings()
		s.LastVersion = version
		_ = saveSettings(s)
		return label
	}
	s := loadSettings()
	prev := s.LastVersion
	if s.LastVersion != version {
		s.LastVersion = version
		_ = saveSettings(s)
	}
	if prev != "" && compareVersions(prev, version) < 0 {
		return prev
	}
	return ""
}

// compareVersions returns -1, 0 or 1.
func compareVersions(a, b string) int {
	pa, pb := strings.Split(a, "."), strings.Split(b, ".")
	for i := 0; i < 3; i++ {
		var x, y int
		if i < len(pa) {
			x, _ = strconv.Atoi(pa[i])
		}
		if i < len(pb) {
			y, _ = strconv.Atoi(pb[i])
		}
		if x != y {
			if x < y {
				return -1
			}
			return 1
		}
	}
	return 0
}

// ---------------------------------------------------------------------
// Network helpers
// ---------------------------------------------------------------------

// localIP is the address of this PC on the local network ("" if none).
func localIP() string {
	if ips := localIPs(); len(ips) > 0 {
		return ips[0]
	}
	return ""
}

// localIPs lists the IPv4 addresses of this PC, the most likely to be the
// home network first (virtual adapters last, at most 4).
func localIPs() []string {
	ifaces, _ := net.Interfaces()
	type cand struct {
		ip    string
		score int
	}
	var cs []cand
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		name := strings.ToLower(ifc.Name)
		bad := false
		for _, v := range []string{"docker", "veth", "br-", "virbr", "vethernet", "vmware", "virtualbox", "tailscale", "zt", "wg"} {
			if strings.Contains(name, v) {
				bad = true
			}
		}
		addrs, _ := ifc.Addrs()
		for _, ad := range addrs {
			ipn, ok := ad.(*net.IPNet)
			if !ok || ipn.IP.To4() == nil || ipn.IP.IsLinkLocalUnicast() {
				continue
			}
			if v4 := ipn.IP.To4(); v4[0] == 100 && v4[1]&0xC0 == 64 {
				continue // 100.64.0.0/10: Tailscale (its address goes in the QR code apart)
			}
			s := 1
			if ipn.IP.IsPrivate() {
				s = 2
			}
			if strings.HasPrefix(ipn.IP.String(), "192.168.") {
				s = 3
			}
			if bad {
				s = 0
			}
			cs = append(cs, cand{ipn.IP.String(), s})
		}
	}
	sort.SliceStable(cs, func(i, j int) bool { return cs[i].score > cs[j].score })
	var out []string
	seen := map[string]bool{}
	for _, c := range cs {
		if seen[c.ip] || (c.score == 0 && len(out) > 0) || len(out) == 4 {
			continue
		}
		seen[c.ip] = true
		out = append(out, c.ip)
	}
	return out
}

// latestSunshineAsset finds the download URL and size of the newest Sunshine
// release file whose name satisfies match.
func latestSunshineAsset(match func(string) bool) (url, name string, size int64, err error) {
	return githubAsset("LizardByte/Sunshine", match)
}

// githubAsset finds a file of the latest GitHub release of repo.
func githubAsset(repo string, match func(string) bool) (url, name string, size int64, err error) {
	c := &http.Client{Timeout: 20 * time.Second}
	req, _ := http.NewRequest("GET", "https://api.github.com/repos/"+repo+"/releases/latest", nil)
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "Eclipse/"+version)
	resp, err := c.Do(req)
	if err != nil {
		return "", "", 0, err
	}
	defer resp.Body.Close()
	var r struct {
		Assets []struct {
			Name string `json:"name"`
			URL  string `json:"browser_download_url"`
			Size int64  `json:"size"`
		} `json:"assets"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&r); err != nil {
		return "", "", 0, err
	}
	for _, as := range r.Assets {
		if match(as.Name) {
			return as.URL, as.Name, as.Size, nil
		}
	}
	return "", "", 0, errors.New("aucun fichier compatible avec ce système (" + repo + ")")
}

// download saves url to dest, reporting real byte progress.
func download(url, dest string, sizeHint int64, rep reporter) error {
	c := &http.Client{Timeout: 30 * time.Minute}
	req, _ := http.NewRequest("GET", url, nil)
	req.Header.Set("User-Agent", "Eclipse/"+version)
	resp, err := c.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("téléchargement : erreur %d", resp.StatusCode)
	}
	f, err := os.Create(dest)
	if err != nil {
		return err
	}
	defer f.Close()
	total := resp.ContentLength
	if total <= 0 {
		total = sizeHint
	}
	var done int64
	buf := make([]byte, 256*1024)
	last := time.Time{}
	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := f.Write(buf[:n]); werr != nil {
				return werr
			}
			done += int64(n)
			if time.Since(last) > 150*time.Millisecond {
				last = time.Now()
				rep.Download(done, total)
			}
		}
		if rerr == io.EOF {
			rep.Download(done, done)
			if total > 0 && done < total {
				return errors.New("téléchargement incomplet")
			}
			return nil
		}
		if rerr != nil {
			return rerr
		}
	}
}

// ---------------------------------------------------------------------
// Local API used by the interface
// ---------------------------------------------------------------------

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, msg string, err error) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(400)
	detail := ""
	if err != nil {
		detail = err.Error()
	}
	_ = json.NewEncoder(w).Encode(map[string]string{"error": msg, "detail": detail})
}

func (a *Core) routes(token string) http.Handler {
	mux := http.NewServeMux()
	sub, _ := fs.Sub(uiFiles, "ui")
	static := http.FileServer(http.FS(sub))
	mux.Handle("/", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		static.ServeHTTP(w, r)
	}))

	api := http.NewServeMux()

	api.HandleFunc("GET /api/state", func(w http.ResponseWriter, r *http.Request) {
		a.pingMu.Lock()
		a.lastPing = time.Now()
		a.pingMu.Unlock()
		host, _ := os.Hostname()
		inst := installInfo()
		stopped := loadSettings().SunshineStopped
		a.mu.Lock()
		running := a.phase == "ready" && a.sunRunning
		st := map[string]any{
			"phase": a.phase, "message": a.message, "detail": a.detail, "error": a.errText,
			"percent": a.percent, "needInstall": a.needInstall, "updatedFrom": a.updatedFrom,
			"host": host, "ip": localIP(), "version": version, "platform": platformName,
			"install": inst, "updating": a.updating(), "running": running, "stopped": stopped, "starting": a.starting, "startError": a.startErr,
		}
		a.mu.Unlock()
		writeJSON(w, st)
	})

	api.HandleFunc("POST /api/sunshine/stop", func(w http.ResponseWriter, r *http.Request) {
		if err := a.stopStreaming(); err != nil {
			writeErr(w, "Impossible d'arrêter Sunshine.", err)
			return
		}
		writeJSON(w, map[string]bool{"ok": true})
	})

	api.HandleFunc("POST /api/sunshine/start", func(w http.ResponseWriter, r *http.Request) {
		go a.startStreaming()
		writeJSON(w, map[string]bool{"ok": true})
	})

	a.mlRoutes(api)

	api.HandleFunc("POST /api/retry", func(w http.ResponseWriter, r *http.Request) {
		go a.runSetup()
		writeJSON(w, map[string]bool{"ok": true})
	})

	api.HandleFunc("POST /api/install", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Desktop bool `json:"desktop"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if err := selfInstall(req.Desktop); err != nil {
			writeErr(w, "L'installation a échoué.", err)
			return
		}
		writeJSON(w, map[string]bool{"ok": true})
		go func() { time.Sleep(1500 * time.Millisecond); os.Exit(0) }()
	})

	api.HandleFunc("POST /api/launch-installed", func(w http.ResponseWriter, r *http.Request) {
		if err := launchInstalled(); err != nil {
			writeErr(w, "Impossible d'ouvrir Eclipse.", err)
			return
		}
		writeJSON(w, map[string]bool{"ok": true})
		go func() { time.Sleep(800 * time.Millisecond); os.Exit(0) }()
	})

	api.HandleFunc("GET /api/pending", func(w http.ResponseWriter, r *http.Request) {
		ps, supported, err := a.sun.Pending()
		if err != nil {
			writeErr(w, "Impossible de lire les demandes.", err)
			return
		}
		// Requests from a QR code are answered by Eclipse itself (qr.go);
		// the window only asks for the code of the others.
		out := []Pairing{}
		for _, p := range ps {
			if kind, _, name := parseDeviceName(p.Name); kind != "qr" {
				p.Name = name
				out = append(out, p)
			}
		}
		writeJSON(w, map[string]any{"supported": supported, "pairings": out})
	})

	api.HandleFunc("POST /api/pending/cancel", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			ID string `json:"id"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if _, err := a.sun.CancelPairing(req.ID); err != nil {
			writeErr(w, "Impossible d'annuler la demande.", err)
			return
		}
		writeJSON(w, map[string]bool{"ok": true})
	})

	a.qrRoutes(api)
	a.remoteRoutes(api)
	a.updateRoutes(api)

	api.HandleFunc("POST /api/pair", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			ID   string `json:"id"`
			Pin  string `json:"pin"`
			Name string `json:"name"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if len(req.Pin) != 4 {
			writeErr(w, "Le code doit faire 4 chiffres.", nil)
			return
		}
		if _, err := strconv.Atoi(req.Pin); err != nil {
			writeErr(w, "Le code doit faire 4 chiffres.", nil)
			return
		}
		name := cleanDeviceName(req.Name)
		if name == "" {
			name = "Appareil Moonlight"
		}
		ok, err := a.pairAndTidy(req.ID, req.Pin, name)
		if err != nil {
			writeErr(w, "L'appairage a échoué.", err)
			return
		}
		writeJSON(w, map[string]bool{"ok": ok})
	})

	api.HandleFunc("GET /api/apps", func(w http.ResponseWriter, r *http.Request) {
		apps, err := a.sun.Apps()
		if err != nil {
			writeErr(w, "Impossible de lire la liste des jeux.", err)
			return
		}
		writeJSON(w, map[string]any{"apps": apps})
	})

	api.HandleFunc("POST /api/apps/pick", func(w http.ResponseWriter, r *http.Request) {
		p, err := pickProgram()
		if err != nil {
			writeErr(w, "Impossible d'ouvrir le sélecteur de fichiers.", err)
			return
		}
		if p == "" {
			writeJSON(w, map[string]any{"cancelled": true})
			return
		}
		writeJSON(w, map[string]any{"path": p, "name": prettyName(p)})
	})

	api.HandleFunc("POST /api/apps", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Name string `json:"name"`
			Path string `json:"path"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if req.Path == "" {
			writeErr(w, "Aucun fichier choisi.", nil)
			return
		}
		name := strings.TrimSpace(req.Name)
		if name == "" {
			name = prettyName(req.Path)
		}
		na := appFor(req.Path)
		na.Name = name
		if err := a.sun.AddApp(na); err != nil {
			writeErr(w, "Impossible d'ajouter le jeu.", err)
			return
		}
		writeJSON(w, map[string]bool{"ok": true})
	})

	api.HandleFunc("DELETE /api/apps/{i}", func(w http.ResponseWriter, r *http.Request) {
		i, err := strconv.Atoi(r.PathValue("i"))
		if err != nil {
			writeErr(w, "Jeu inconnu.", err)
			return
		}
		if err := a.sun.DeleteApp(i); err != nil {
			writeErr(w, "Impossible de retirer ce jeu.", err)
			return
		}
		writeJSON(w, map[string]bool{"ok": true})
	})

	api.HandleFunc("GET /api/devices", func(w http.ResponseWriter, r *http.Request) {
		a.dedupeClients("")
		ds, err := a.sun.Devices()
		if err != nil {
			writeErr(w, "Impossible de lire les appareils.", err)
			return
		}
		if ds == nil {
			ds = []Device{}
		}
		writeJSON(w, map[string]any{"devices": ds})
	})

	api.HandleFunc("POST /api/devices/remove", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			UUID string `json:"uuid"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if err := a.sun.RemoveDevice(req.UUID); err != nil {
			writeErr(w, "Impossible de retirer cet appareil.", err)
			return
		}
		writeJSON(w, map[string]bool{"ok": true})
	})

	api.HandleFunc("GET /api/advanced", func(w http.ResponseWriter, r *http.Request) {
		lg := loadLogin()
		if lg == nil {
			lg = &login{}
		}
		writeJSON(w, lg)
	})

	api.HandleFunc("POST /api/open", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Target string `json:"target"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		links := map[string]string{
			"moonlight-android": "https://play.google.com/store/apps/details?id=com.limelight",
			"sunshine":          a.sun.base,
		}
		if u, ok := links[req.Target]; ok {
			openURL(u)
		}
		writeJSON(w, map[string]bool{"ok": true})
	})

	a.files.deviceRoutes(api, "/api")
	a.instanceRoutes(api)

	mux.Handle("/api/", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Token") != token {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		api.ServeHTTP(w, r)
	}))
	return mux
}

func prettyName(p string) string {
	base := filepath.Base(strings.ReplaceAll(p, "\\", "/"))
	base = strings.TrimSuffix(base, filepath.Ext(base))
	base = strings.NewReplacer("_", " ", "-", " ", ".", " ").Replace(base)
	words := strings.Fields(base)
	for i, w := range words {
		if len(w) > 0 && strings.ToLower(w) == w {
			words[i] = strings.ToUpper(w[:1]) + w[1:]
		}
	}
	if len(words) == 0 {
		return "Mon jeu"
	}
	return strings.Join(words, " ")
}

// sinceLastPing is used by browser-based windows to know when the UI closed.
func (a *Core) sinceLastPing() (time.Duration, bool) {
	a.pingMu.Lock()
	defer a.pingMu.Unlock()
	if a.lastPing.IsZero() {
		return 0, false
	}
	return time.Since(a.lastPing), true
}

func (a *Core) waitUIClosed() {
	start := time.Now()
	for {
		time.Sleep(2 * time.Second)
		d, seen := a.sinceLastPing()
		if seen && d > 20*time.Second {
			return
		}
		if !seen && time.Since(start) > 3*time.Minute {
			return
		}
	}
}
