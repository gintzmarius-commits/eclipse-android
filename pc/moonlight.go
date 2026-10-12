package main

// Moonlight tab: play on this PC the games of another PC (running Eclipse
// or Sunshine). Moonlight itself does the streaming; Eclipse installs it,
// finds PCs on the network, pairs them and lists their games through
// Moonlight's command line (pair / list / stream).

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/rand"
	"encoding/csv"
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"log"
	"math"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

type mlHost struct {
	Address string `json:"address"`
	Name    string `json:"name"`
	UUID    string `json:"uuid,omitempty"`
}

type mlInstall struct {
	installing bool
	percent    float64
	message    string
	detail     string
	errText    string
	dlStart    time.Time
}

type pairJob struct {
	Address string `json:"address"`
	Name    string `json:"name"`
	Pin     string `json:"pin"`
	Status  string `json:"status"` // waiting, checking, success, failed, cancelled
	Error   string `json:"error"`
	cmd     *exec.Cmd
}

type mlState struct {
	mu   sync.Mutex
	inst mlInstall
	pair *pairJob
}

// ---------------------------------------------------------------------
// Installation with real progress
// ---------------------------------------------------------------------

type mlReporter struct{ s *mlState }

func (r mlReporter) set(pct float64, msg, detail string) {
	r.s.mu.Lock()
	defer r.s.mu.Unlock()
	if pct >= 0 && pct < r.s.inst.percent {
		pct = r.s.inst.percent
	}
	r.s.inst.percent, r.s.inst.message, r.s.inst.detail = pct, msg, detail
}

func (r mlReporter) Download(done, total int64) {
	r.s.mu.Lock()
	if r.s.inst.dlStart.IsZero() {
		r.s.inst.dlStart = time.Now()
	}
	start := r.s.inst.dlStart
	r.s.mu.Unlock()
	if total <= 0 {
		r.set(-1, "Téléchargement de Moonlight…", humanBytes(done))
		return
	}
	detail := humanBytes(done) + " sur " + humanBytes(total)
	if el := time.Since(start).Seconds(); el > 1.5 && done > 0 {
		detail += " · " + humanDuration(float64(total-done)/(float64(done)/el))
	}
	r.set(mlDownloadShare*float64(done)/float64(total), "Téléchargement de Moonlight…", detail)
}

func (r mlReporter) Install(frac float64) {
	r.set(mlDownloadShare+(100-mlDownloadShare)*math.Min(1, frac), "Installation de Moonlight…", "")
}

func (r mlReporter) Busy(phase, message string) { r.set(-1, message, "") }

func (a *Core) installMoonlight() {
	s := &a.ml
	s.mu.Lock()
	if s.inst.installing {
		s.mu.Unlock()
		return
	}
	s.inst = mlInstall{installing: true, message: "Préparation…"}
	s.mu.Unlock()

	err := installMoonlight(mlReporter{s})
	s.mu.Lock()
	defer s.mu.Unlock()
	s.inst.installing = false
	if err == nil && moonlightPath() == "" {
		err = errors.New("Moonlight est introuvable après l'installation")
	}
	if err != nil {
		s.inst.errText = err.Error()
		log.Printf("moonlight install: %v", err)
		return
	}
	s.inst.percent = 100
	log.Printf("moonlight installed: %s", moonlightPath())
}

// moonlightAsset finds the newest Moonlight release file matching match.
func moonlightAsset(match func(string) bool) (url, name string, size int64, err error) {
	if u := os.Getenv("ECLIPSE_MOONLIGHT_URL"); u != "" { // test hook
		return u, filepath.Base(u), 0, nil
	}
	return githubAsset("moonlight-stream/moonlight-qt", match)
}

// extractZip unpacks src into dst, reporting progress by uncompressed bytes.
func extractZip(src, dst string, rep reporter) error {
	zr, err := zip.OpenReader(src)
	if err != nil {
		return err
	}
	defer zr.Close()
	var total, done int64
	for _, f := range zr.File {
		total += int64(f.UncompressedSize64)
	}
	if total == 0 {
		total = 1
	}
	last := time.Time{}
	for _, f := range zr.File {
		p := filepath.Join(dst, filepath.FromSlash(f.Name))
		if !strings.HasPrefix(p, filepath.Clean(dst)+string(os.PathSeparator)) {
			return fmt.Errorf("chemin invalide dans l'archive : %s", f.Name)
		}
		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(p, 0o755); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			return err
		}
		in, err := f.Open()
		if err != nil {
			return err
		}
		out, err := os.Create(p)
		if err != nil {
			in.Close()
			return err
		}
		buf := make([]byte, 256*1024)
		for {
			n, rerr := in.Read(buf)
			if n > 0 {
				if _, werr := out.Write(buf[:n]); werr != nil {
					in.Close()
					out.Close()
					return werr
				}
				done += int64(n)
				if time.Since(last) > 120*time.Millisecond {
					last = time.Now()
					rep.Install(float64(done) / float64(total))
				}
			}
			if rerr == io.EOF {
				break
			}
			if rerr != nil {
				in.Close()
				out.Close()
				return rerr
			}
		}
		in.Close()
		if err := out.Close(); err != nil {
			return err
		}
	}
	rep.Install(1)
	return nil
}

// ---------------------------------------------------------------------
// Talking to other PCs
// ---------------------------------------------------------------------

type serverInfo struct {
	Hostname string `xml:"hostname"`
	UniqueID string `xml:"uniqueid"`
}

func withPort(addr string) string {
	if _, _, err := net.SplitHostPort(addr); err == nil {
		return addr
	}
	return net.JoinHostPort(strings.Trim(addr, "[]"), "47989")
}

// fetchServerInfo asks a PC running Sunshine for its name (no pairing needed).
func fetchServerInfo(addr string, timeout time.Duration) (serverInfo, bool) {
	c := &http.Client{Timeout: timeout, Transport: &http.Transport{Proxy: nil}}
	resp, err := c.Get("http://" + withPort(addr) + "/serverinfo?uniqueid=0123456789ABCDEF")
	if err != nil {
		return serverInfo{}, false
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	var si serverInfo
	if xml.Unmarshal(data, &si) != nil || si.Hostname == "" {
		return serverInfo{}, false
	}
	return si, true
}

type foundPC struct {
	Address string `json:"address"`
	Name    string `json:"name"`
	Saved   bool   `json:"saved"`
}

// scanLAN looks for PCs running Sunshine on the local network.
func scanLAN() []foundPC {
	own := map[string]bool{"127.0.0.1": true}
	var targets []string
	ifaces, _ := net.Interfaces()
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := ifc.Addrs()
		for _, ad := range addrs {
			ipn, ok := ad.(*net.IPNet)
			if !ok || ipn.IP.To4() == nil || !ipn.IP.IsPrivate() {
				continue
			}
			ip := ipn.IP.To4()
			own[ip.String()] = true
			ones, _ := ipn.Mask.Size()
			if ones < 16 {
				continue
			}
			for i := 1; i < 255; i++ { // the /24 around this address
				targets = append(targets, fmt.Sprintf("%d.%d.%d.%d", ip[0], ip[1], ip[2], i))
			}
		}
	}
	if extra := os.Getenv("ECLIPSE_SCAN_EXTRA"); extra != "" { // test hook
		targets = append(targets, strings.Split(extra, ",")...)
	}
	ownInfo, _ := fetchServerInfo("127.0.0.1", 800*time.Millisecond)

	saved := map[string]bool{}
	for _, h := range loadSettings().Hosts {
		saved[h.Address] = true
	}

	var mu sync.Mutex
	var out []foundPC
	seen := map[string]bool{}
	sem := make(chan struct{}, 64)
	var wg sync.WaitGroup
	for _, t := range targets {
		if own[t] || seen[t] {
			continue
		}
		seen[t] = true
		wg.Add(1)
		sem <- struct{}{}
		go func(addr string) {
			defer wg.Done()
			defer func() { <-sem }()
			si, ok := fetchServerInfo(addr, 900*time.Millisecond)
			if !ok || (ownInfo.UniqueID != "" && si.UniqueID == ownInfo.UniqueID) {
				return
			}
			mu.Lock()
			out = append(out, foundPC{Address: addr, Name: si.Hostname, Saved: saved[addr]})
			mu.Unlock()
		}(t)
	}
	wg.Wait()
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	return out
}

var (
	errNotPaired = errors.New("pas encore connecté")
	errOffline   = errors.New("PC éteint ou injoignable")
)

// listGames asks Moonlight for the games of a paired PC.
func listGames(addr string) ([]string, error) {
	if moonlightPath() == "" {
		return nil, errors.New("Moonlight n'est pas installé")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	cmd := moonlightCmd(ctx, "list", addr, "--csv")
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	err := cmd.Run()
	if err != nil {
		msg := stderr.String() + stdout.String()
		log.Printf("moonlight list %s: %v %s", addr, err, msg)
		switch {
		case strings.Contains(msg, "not been paired"):
			return nil, errNotPaired
		case strings.Contains(msg, "Failed to connect"), ctx.Err() != nil:
			return nil, errOffline
		}
		return nil, fmt.Errorf("Moonlight : %s", strings.TrimSpace(firstLine(msg, err.Error())))
	}
	text := stdout.String()
	i := strings.Index(text, "Name, ID")
	if i < 0 {
		return []string{}, nil
	}
	rest := text[i:]
	if nl := strings.IndexByte(rest, '\n'); nl >= 0 {
		rest = rest[nl+1:]
	} else {
		rest = ""
	}
	r := csv.NewReader(strings.NewReader(rest))
	r.LazyQuotes = true
	r.FieldsPerRecord = -1
	r.TrimLeadingSpace = true
	games := []string{}
	for {
		rec, err := r.Read()
		if err == io.EOF {
			break
		}
		if err != nil || len(rec) == 0 || rec[0] == "" {
			continue
		}
		if len(rec) > 4 && strings.TrimSpace(rec[4]) == "true" { // hidden
			continue
		}
		games = append(games, rec[0])
	}
	return games, nil
}

func firstLine(s, def string) string {
	for _, l := range strings.Split(s, "\n") {
		if l = strings.TrimSpace(l); l != "" && !strings.HasPrefix(l, "Establishing") && !strings.HasPrefix(l, "Loading") {
			return l
		}
	}
	return def
}

func randomPin() string {
	n, _ := rand.Int(rand.Reader, big.NewInt(10000))
	return fmt.Sprintf("%04d", n.Int64())
}

func saveHost(h mlHost) {
	_ = updateSettings(func(s *settings) {
		for i, x := range s.Hosts {
			if x.Address == h.Address {
				s.Hosts[i] = h
				return
			}
		}
		s.Hosts = append(s.Hosts, h)
	})
}

func (a *Core) startPairing(addr string) (*pairJob, error) {
	if moonlightPath() == "" {
		return nil, errors.New("Moonlight n'est pas installé")
	}
	a.cancelPairing()
	name := addr
	si, ok := fetchServerInfo(addr, 2*time.Second)
	if ok {
		name = si.Hostname
	}
	job := &pairJob{Address: addr, Name: name, Pin: randomPin(), Status: "waiting"}
	cmd := moonlightCmd(context.Background(), "pair", addr, "--pin", job.Pin)
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	job.cmd = cmd
	a.ml.mu.Lock()
	a.ml.pair = job
	a.ml.mu.Unlock()

	go func() {
		werr := cmd.Wait()
		a.ml.mu.Lock()
		if job.Status == "cancelled" {
			a.ml.mu.Unlock()
			return
		}
		job.Status = "checking"
		a.ml.mu.Unlock()
		log.Printf("moonlight pair %s ended: %v", addr, werr)

		_, err := listGames(addr)
		a.ml.mu.Lock()
		defer a.ml.mu.Unlock()
		if job.Status == "cancelled" {
			return
		}
		if err != nil {
			job.Status = "failed"
			if errors.Is(err, errNotPaired) {
				job.Error = "Le code n'a pas été validé sur l'autre PC."
			} else if errors.Is(err, errOffline) {
				job.Error = "Ce PC ne répond pas. Vérifie qu'il est allumé, sur le même Wi-Fi, et que Sunshine tourne dessus."
			} else {
				job.Error = err.Error()
			}
			return
		}
		job.Status = "success"
		h := mlHost{Address: addr, Name: name}
		if ok {
			h.UUID = si.UniqueID
		}
		saveHost(h)
	}()
	return job, nil
}

func (a *Core) cancelPairing() {
	a.ml.mu.Lock()
	job := a.ml.pair
	a.ml.mu.Unlock()
	if job == nil || job.cmd == nil || job.cmd.Process == nil {
		return
	}
	a.ml.mu.Lock()
	if job.Status == "waiting" {
		job.Status = "cancelled"
		_ = job.cmd.Process.Kill()
	}
	a.ml.mu.Unlock()
}

// ---------------------------------------------------------------------
// Local API
// ---------------------------------------------------------------------

func (a *Core) mlRoutes(api *http.ServeMux) {
	api.HandleFunc("GET /api/ml/status", func(w http.ResponseWriter, r *http.Request) {
		a.ml.mu.Lock()
		i := a.ml.inst
		a.ml.mu.Unlock()
		writeJSON(w, map[string]any{
			"installed": moonlightPath() != "", "installing": i.installing,
			"percent": i.percent, "message": i.message, "detail": i.detail, "error": i.errText,
		})
	})

	api.HandleFunc("POST /api/ml/install", func(w http.ResponseWriter, r *http.Request) {
		go a.installMoonlight()
		writeJSON(w, map[string]bool{"ok": true})
	})

	api.HandleFunc("POST /api/ml/open", func(w http.ResponseWriter, r *http.Request) {
		if moonlightPath() == "" {
			writeErr(w, "Moonlight n'est pas installé.", nil)
			return
		}
		if err := moonlightCmd(context.Background()).Start(); err != nil {
			writeErr(w, "Impossible d'ouvrir Moonlight.", err)
			return
		}
		writeJSON(w, map[string]bool{"ok": true})
	})

	api.HandleFunc("GET /api/ml/hosts", func(w http.ResponseWriter, r *http.Request) {
		hosts := loadSettings().Hosts
		type row struct {
			mlHost
			Online bool `json:"online"`
		}
		out := make([]row, len(hosts))
		var wg sync.WaitGroup
		for i, h := range hosts {
			wg.Add(1)
			go func(i int, h mlHost) {
				defer wg.Done()
				si, ok := fetchServerInfo(h.Address, 1500*time.Millisecond)
				if ok && si.Hostname != "" {
					h.Name = si.Hostname
				}
				out[i] = row{h, ok}
			}(i, h)
		}
		wg.Wait()
		writeJSON(w, map[string]any{"hosts": out})
	})

	api.HandleFunc("GET /api/ml/scan", func(w http.ResponseWriter, r *http.Request) {
		found := scanLAN()
		if found == nil {
			found = []foundPC{}
		}
		writeJSON(w, map[string]any{"pcs": found})
	})

	api.HandleFunc("GET /api/ml/games", func(w http.ResponseWriter, r *http.Request) {
		games, err := listGames(r.URL.Query().Get("address"))
		switch {
		case errors.Is(err, errNotPaired):
			writeJSON(w, map[string]any{"state": "notpaired"})
		case errors.Is(err, errOffline):
			writeJSON(w, map[string]any{"state": "offline"})
		case err != nil:
			writeJSON(w, map[string]any{"state": "error", "error": err.Error()})
		default:
			writeJSON(w, map[string]any{"state": "ok", "games": games})
		}
	})

	api.HandleFunc("POST /api/ml/pair", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Address string `json:"address"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		addr := strings.TrimSpace(req.Address)
		if addr == "" {
			writeErr(w, "Entre l'adresse du PC.", nil)
			return
		}
		job, err := a.startPairing(addr)
		if err != nil {
			writeErr(w, "Impossible de lancer la connexion.", err)
			return
		}
		writeJSON(w, job)
	})

	api.HandleFunc("GET /api/ml/pair", func(w http.ResponseWriter, r *http.Request) {
		a.ml.mu.Lock()
		defer a.ml.mu.Unlock()
		if a.ml.pair == nil {
			writeJSON(w, map[string]string{"status": "none"})
			return
		}
		writeJSON(w, a.ml.pair)
	})

	api.HandleFunc("POST /api/ml/pair/cancel", func(w http.ResponseWriter, r *http.Request) {
		a.cancelPairing()
		writeJSON(w, map[string]bool{"ok": true})
	})

	api.HandleFunc("POST /api/ml/stream", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Address string `json:"address"`
			Game    string `json:"game"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if err := moonlightCmd(context.Background(), "stream", req.Address, req.Game).Start(); err != nil {
			writeErr(w, "Impossible de lancer le jeu.", err)
			return
		}
		writeJSON(w, map[string]bool{"ok": true})
	})

	api.HandleFunc("POST /api/ml/remove", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Address string `json:"address"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		err := updateSettings(func(s *settings) {
			kept := s.Hosts[:0]
			for _, h := range s.Hosts {
				if h.Address != req.Address {
					kept = append(kept, h)
				}
			}
			s.Hosts = kept
		})
		if err != nil {
			writeErr(w, "Impossible de retirer ce PC.", err)
			return
		}
		writeJSON(w, map[string]bool{"ok": true})
	})
}
