package main

// Playing away from home (Eclipse 2.1) with Tailscale.
//
// Tailscale links this PC and the phone in a private network that works
// from anywhere, even on 4G, without opening anything on the home router.
// Eclipse installs Tailscale on the PC, starts the sign-in (once, with a
// Google account for example) and puts the PC's Tailscale address in the
// QR code: the Eclipse phone app keeps it and uses it when away from home.

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"sync"
	"time"
)

const tailscaleAdminURL = "https://login.tailscale.com/admin/machines"

type tsStatus struct {
	State   string `json:"state"` // absent, starting, needslogin, approval, stopped, running, error
	IP      string `json:"ip,omitempty"`
	Name    string `json:"name,omitempty"`
	Account string `json:"account,omitempty"`
	Expiry  string `json:"expiry,omitempty"` // RFC 3339, "" when the key never expires
	AuthURL string `json:"authUrl,omitempty"`
	Detail  string `json:"detail,omitempty"`
}

// tsCLI is the tailscale command (tests use a fake one).
func tsCLI() string {
	if p := os.Getenv("ECLIPSE_TAILSCALE"); p != "" {
		return p
	}
	return tailscaleCLI()
}

func tsRun(timeout time.Duration, args ...string) (string, error) {
	cli := tsCLI()
	if cli == "" {
		return "", errors.New("Tailscale n'est pas installé")
	}
	c := tsCommand(cli, args...)
	done := make(chan struct{})
	var out []byte
	var err error
	go func() {
		out, err = c.CombinedOutput()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(timeout):
		if c.Process != nil {
			_ = c.Process.Kill()
		}
		<-done
		return string(out), fmt.Errorf("tailscale %s : pas de réponse", args[0])
	}
	return string(out), err
}

// parseTSStatus reads `tailscale status --json`.
func parseTSStatus(out string) (tsStatus, bool) {
	i := strings.Index(out, "{")
	if i < 0 {
		return tsStatus{}, false
	}
	var r struct {
		BackendState string
		AuthURL      string
		Self         *struct {
			HostName     string
			DNSName      string
			UserID       int64
			TailscaleIPs []string
			KeyExpiry    *string
		}
		CurrentTailnet *struct{ Name string }
		User           map[string]struct{ LoginName string }
	}
	if json.NewDecoder(strings.NewReader(out[i:])).Decode(&r) != nil {
		return tsStatus{}, false
	}
	st := tsStatus{AuthURL: r.AuthURL}
	switch r.BackendState {
	case "Running":
		st.State = "running"
	case "NeedsLogin", "NoState":
		st.State = "needslogin"
	case "NeedsMachineAuth":
		st.State = "approval"
	case "Stopped":
		st.State = "stopped"
	case "Starting":
		st.State = "starting"
	default:
		st.State = "starting"
	}
	if r.Self != nil {
		for _, ip := range r.Self.TailscaleIPs {
			if p := net.ParseIP(ip); p != nil && p.To4() != nil {
				st.IP = ip
				break
			}
		}
		st.Name = strings.TrimSuffix(r.Self.DNSName, ".")
		if r.Self.KeyExpiry != nil {
			st.Expiry = *r.Self.KeyExpiry
		}
		for id, u := range r.User {
			if id == fmt.Sprint(r.Self.UserID) {
				st.Account = u.LoginName
			}
		}
	}
	if st.Account == "" && r.CurrentTailnet != nil {
		st.Account = r.CurrentTailnet.Name
	}
	if st.State == "running" && st.IP == "" {
		st.State = "starting"
	}
	return st, true
}

var tsCache struct {
	sync.Mutex
	st tsStatus
	at time.Time
}

// tsQuery returns the Tailscale state of this PC (cached a moment: the
// window asks every second while the panel is open).
func tsQuery(fresh bool) tsStatus {
	tsCache.Lock()
	defer tsCache.Unlock()
	if !fresh && time.Since(tsCache.at) < 1500*time.Millisecond {
		return tsCache.st
	}
	st := tsStatus{State: "absent"}
	if tsCLI() != "" {
		out, err := tsRun(8*time.Second, "status", "--json")
		if s, ok := parseTSStatus(out); ok {
			st = s
		} else if err != nil {
			low := strings.ToLower(out)
			if strings.Contains(low, "failed to connect") || strings.Contains(low, "is tailscaled running") ||
				strings.Contains(low, "not running") || strings.Contains(low, "connection refused") || strings.Contains(low, "no such file") {
				st = tsStatus{State: "starting"}
			} else {
				st = tsStatus{State: "error", Detail: strings.TrimSpace(firstLine(out, err.Error()))}
			}
		}
	}
	tsCache.st, tsCache.at = st, time.Now()
	return st
}

// remoteForQR is what the QR code says about playing away from home.
func remoteForQR() (ip, account string) {
	if st := tsQuery(false); st.State == "running" {
		return st.IP, st.Account
	}
	return "", ""
}

// ---------------------------------------------------------------------
// Turning it on: install, then sign in
// ---------------------------------------------------------------------

type remoteState struct {
	mu       sync.Mutex
	busy     bool
	phase    string // install, wait, login
	percent  float64
	message  string
	detail   string
	errText  string
	loginURL string
	opened   string // login page already opened in the browser
	login    *exec.Cmd
	dlStart  time.Time
}

// remoteReporter shows the installation of Tailscale with real progress.
type remoteReporter struct{ a *Core }

func (r remoteReporter) set(pct float64, msg, detail string) {
	s := &r.a.remote
	s.mu.Lock()
	if pct >= 0 && pct < s.percent {
		pct = s.percent
	}
	s.percent, s.message, s.detail = pct, msg, detail
	s.mu.Unlock()
}

func (r remoteReporter) Download(done, total int64) {
	s := &r.a.remote
	s.mu.Lock()
	if s.dlStart.IsZero() {
		s.dlStart = time.Now()
	}
	start := s.dlStart
	s.mu.Unlock()
	if total <= 0 {
		r.set(-1, "Téléchargement de Tailscale…", humanBytes(done))
		return
	}
	detail := humanBytes(done) + " sur " + humanBytes(total)
	if el := time.Since(start).Seconds(); el > 1.5 && done > 0 {
		detail += " · " + humanDuration(float64(total-done)/(float64(done)/el))
	}
	r.set(60*float64(done)/float64(total), "Téléchargement de Tailscale…", detail)
}

func (r remoteReporter) Install(frac float64) {
	r.set(60+38*frac, "Installation de Tailscale…", "Ne ferme pas la fenêtre")
}

func (r remoteReporter) Busy(phase, message string) {
	s := &r.a.remote
	s.mu.Lock()
	s.message, s.detail = message, ""
	s.mu.Unlock()
}

func (a *Core) remoteFail(msg string, err error) {
	s := &a.remote
	s.mu.Lock()
	s.busy, s.phase, s.errText = false, "", msg
	if err != nil {
		s.errText += " (" + err.Error() + ")"
	}
	s.mu.Unlock()
	log.Printf("[remote] %s: %v", msg, err)
}

// remoteAuto sets up playing away from home without being asked (Eclipse
// 2.3): Tailscale is installed if needed and started; its sign-in page
// opens once, the very first time. Paused by the person: left alone.
func (a *Core) remoteAuto() {
	if os.Getenv("ECLIPSE_NO_REMOTE_AUTO") == "1" {
		return
	}
	s := loadSettings()
	if s.RemoteOff {
		return
	}
	st := tsQuery(true)
	first := s.RemoteAuto == ""
	if first {
		_ = updateSettings(func(s *settings) { s.RemoteAuto = version })
	}
	switch {
	case st.State == "running" || st.State == "approval":
		return
	case first:
		log.Printf("[remote] automatic setup (Tailscale %s)", st.State)
		a.remoteEnable()
	case st.State == "stopped" || st.State == "starting":
		// Already signed in once: back on without any page.
		log.Printf("[remote] Tailscale %s: starting it", st.State)
		a.remoteEnable()
	}
}

// remoteEnable installs Tailscale if needed, then connects this PC.
func (a *Core) remoteEnable() {
	s := &a.remote
	s.mu.Lock()
	if s.busy {
		s.mu.Unlock()
		return
	}
	s.busy, s.errText, s.percent, s.message, s.detail, s.dlStart = true, "", 0, "", "", time.Time{}
	s.mu.Unlock()

	if tsCLI() == "" {
		s.mu.Lock()
		s.phase, s.message = "install", "Préparation…"
		s.mu.Unlock()
		var err error
		if sim := os.Getenv("ECLIPSE_TAILSCALE_SIMULATE"); sim != "" { // test hook
			err = simulateTailscaleInstall(sim, remoteReporter{a})
		} else {
			err = installTailscale(remoteReporter{a})
		}
		if err != nil || tsCLI() == "" {
			if err == nil {
				err = errors.New("Tailscale introuvable après l'installation")
			}
			a.remoteFail("L'installation de Tailscale a échoué.", err)
			return
		}
		log.Printf("[remote] Tailscale installed")
	}

	// Wait for the Tailscale service (it starts with the installation).
	s.mu.Lock()
	s.phase, s.message, s.detail, s.percent = "wait", "Démarrage de Tailscale…", "", 100
	s.mu.Unlock()
	var st tsStatus
	for i := 0; i < 60; i++ {
		st = tsQuery(true)
		if st.State != "starting" {
			break
		}
		if i == 15 {
			tailscaleStartService() // not started by the installation?
		}
		time.Sleep(time.Second)
	}
	switch st.State {
	case "running":
		a.remoteDone()
		return
	case "starting":
		a.remoteFail("Tailscale ne démarre pas.", errors.New("redémarre le PC puis réessaie"))
		return
	case "error":
		a.remoteFail("Tailscale ne répond pas.", errors.New(st.Detail))
		return
	}
	a.remoteLogin(false)
}

func (a *Core) remoteDone() {
	s := &a.remote
	s.mu.Lock()
	s.busy, s.phase, s.loginURL, s.opened = false, "", "", ""
	s.mu.Unlock()
	// A QR code on screen does not have the Tailscale address yet: the
	// window draws a new one.
	a.qrStop()
	log.Printf("[remote] connected")
}

var authURLRe = regexp.MustCompile(`https://\S+`)

// remoteLogin connects this PC to Tailscale (asking to sign in if needed).
func (a *Core) remoteLogin(relogin bool) {
	s := &a.remote
	s.mu.Lock()
	s.busy, s.phase, s.message, s.detail, s.errText, s.loginURL, s.opened = true, "login", "Connexion à Tailscale…", "", "", "", ""
	s.mu.Unlock()

	if relogin {
		out, err := tsRun(20*time.Second, "logout")
		log.Printf("[remote] logout: %v %s", err, strings.TrimSpace(out))
	}
	args := tsUpArgs()
	for attempt := 0; attempt < 3; attempt++ {
		out, err := a.runLogin(args)
		low := strings.ToLower(out)
		switch {
		case err == nil:
			if st := tsQuery(true); st.State == "running" {
				a.remoteDone()
				return
			}
		case strings.Contains(low, "requires mentioning all") || strings.Contains(low, "non-default"):
			// Tailscale was already set up with other options: keep them.
			args = append([]string{"login"}, args[1:]...)
			continue
		case strings.Contains(low, "access denied") || strings.Contains(low, "permission denied") || strings.Contains(low, "must be root"):
			if perr := tailscaleAllowUser(); perr != nil {
				a.remoteFail("Tailscale demande les droits administrateur.", perr)
				return
			}
			continue
		}
		if st := tsQuery(true); st.State == "running" {
			a.remoteDone()
			return
		} else if st.State == "approval" {
			a.remoteFail("Ce PC attend d'être approuvé dans la console Tailscale.", errors.New(tailscaleAdminURL))
			return
		}
		s.mu.Lock()
		cancelled := !s.busy
		s.mu.Unlock()
		if cancelled {
			return
		}
		a.remoteFail("La connexion à Tailscale n'a pas abouti.", errors.New(strings.TrimSpace(firstLine(out, fmt.Sprint(err)))))
		return
	}
	a.remoteFail("La connexion à Tailscale n'a pas abouti.", nil)
}

// runLogin runs `tailscale up` until signed in, showing the sign-in page.
func (a *Core) runLogin(args []string) (string, error) {
	cli := tsCLI()
	c := tsCommand(cli, args...)
	pr, pw := io.Pipe()
	c.Stdout, c.Stderr = pw, pw
	if err := c.Start(); err != nil {
		return "", err
	}
	s := &a.remote
	s.mu.Lock()
	s.login = c
	s.mu.Unlock()

	var buf strings.Builder
	var bufMu sync.Mutex
	readerDone := make(chan struct{})
	go func() {
		defer close(readerDone)
		b := make([]byte, 4096)
		for {
			n, err := pr.Read(b)
			if n > 0 {
				bufMu.Lock()
				buf.Write(b[:n])
				text := buf.String()
				bufMu.Unlock()
				if m := authURLRe.FindString(text); m != "" {
					a.showLoginURL(m)
				}
			}
			if err != nil {
				return
			}
		}
	}()
	// The sign-in page can also be read from the status.
	stop := make(chan struct{})
	go func() {
		for {
			select {
			case <-stop:
				return
			case <-time.After(2 * time.Second):
			}
			if st := tsQuery(true); st.AuthURL != "" {
				a.showLoginURL(st.AuthURL)
			} else if st.State == "running" {
				return
			}
		}
	}()
	err := c.Wait()
	close(stop)
	_ = pw.Close()
	<-readerDone
	s.mu.Lock()
	s.login = nil
	s.mu.Unlock()
	bufMu.Lock()
	defer bufMu.Unlock()
	log.Printf("[remote] tailscale %s: %v %s", strings.Join(args, " "), err, strings.TrimSpace(buf.String()))
	return buf.String(), err
}

func (a *Core) showLoginURL(u string) {
	u = strings.TrimRight(u, ".,;)\"'")
	s := &a.remote
	s.mu.Lock()
	s.loginURL = u
	open := s.opened != u
	s.opened = u
	s.message = "Connecte-toi à Tailscale"
	s.mu.Unlock()
	if open {
		log.Printf("[remote] sign-in page opened")
		openURL(u)
	}
}

func (a *Core) remoteCancel() {
	s := &a.remote
	s.mu.Lock()
	c := s.login
	s.busy, s.phase, s.loginURL = false, "", ""
	s.mu.Unlock()
	if c != nil && c.Process != nil {
		_ = c.Process.Kill()
	}
}

// ---------------------------------------------------------------------
// Routes
// ---------------------------------------------------------------------

func (a *Core) remoteView() map[string]any {
	st := tsQuery(false)
	s := &a.remote
	s.mu.Lock()
	defer s.mu.Unlock()
	v := map[string]any{
		"state": st.State, "ip": st.IP, "name": st.Name, "account": st.Account, "expiry": st.Expiry,
		"busy": s.busy, "phase": s.phase, "percent": s.percent, "message": s.message, "detail": s.detail,
		"error": s.errText, "loginUrl": s.loginURL, "admin": tailscaleAdminURL,
	}
	if s.loginURL != "" {
		if svg, err := qrSVG(s.loginURL); err == nil {
			v["loginQr"] = svg
		}
	}
	if st.Expiry != "" {
		if t, err := time.Parse(time.RFC3339, st.Expiry); err == nil && t.Year() > 2000 {
			v["expiryDays"] = int(time.Until(t).Hours() / 24)
		}
	}
	return v
}

func (a *Core) remoteRoutes(api *http.ServeMux) {
	api.HandleFunc("GET /api/remote", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, a.remoteView())
	})
	api.HandleFunc("POST /api/remote/enable", func(w http.ResponseWriter, r *http.Request) {
		_ = updateSettings(func(s *settings) { s.RemoteOff = false })
		go a.remoteEnable()
		writeJSON(w, map[string]bool{"ok": true})
	})
	api.HandleFunc("POST /api/remote/relogin", func(w http.ResponseWriter, r *http.Request) {
		go a.remoteLogin(true)
		writeJSON(w, map[string]bool{"ok": true})
	})
	api.HandleFunc("POST /api/remote/cancel", func(w http.ResponseWriter, r *http.Request) {
		a.remoteCancel()
		writeJSON(w, map[string]bool{"ok": true})
	})
	api.HandleFunc("POST /api/remote/disable", func(w http.ResponseWriter, r *http.Request) {
		out, err := tsRun(20*time.Second, "down")
		tsQuery(true)
		if err != nil {
			writeErr(w, "Impossible de couper Tailscale.", errors.New(strings.TrimSpace(firstLine(out, err.Error()))))
			return
		}
		_ = updateSettings(func(s *settings) { s.RemoteOff = true })
		a.qrStop()
		writeJSON(w, map[string]bool{"ok": true})
	})
	api.HandleFunc("POST /api/remote/open", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Target string `json:"target"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		switch req.Target {
		case "login":
			a.remote.mu.Lock()
			u := a.remote.loginURL
			a.remote.mu.Unlock()
			if u != "" {
				openURL(u)
			}
		case "admin":
			openURL(tailscaleAdminURL)
		}
		writeJSON(w, map[string]bool{"ok": true})
	})
}

// simulateTailscaleInstall is a test hook: downloads a file for real
// progress, then installs a fake tailscale command.
func simulateTailscaleInstall(spec string, rep reporter) error {
	parts := strings.SplitN(spec, "|", 2) // "<url>|<fake cli path>"
	dest := os.TempDir() + "/ts-sim.bin"
	if err := download(parts[0], dest, 0, rep); err != nil {
		return err
	}
	os.Remove(dest)
	for i := 0; i <= 10; i++ {
		rep.Install(float64(i) / 10)
		time.Sleep(100 * time.Millisecond)
	}
	if len(parts) == 2 {
		return os.Setenv("ECLIPSE_TAILSCALE", parts[1])
	}
	return nil
}
