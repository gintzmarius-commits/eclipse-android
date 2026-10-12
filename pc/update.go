package main

// Versions of Eclipse for PC (Eclipse 2.3.1, Linux): the "Versions" tab
// lists every version published since 2.3.1 and installs the chosen one
// (newer or older) without the terminal, then restarts Eclipse.
//
// The versions are GitHub releases tagged "pc-v<version>" in Eclipse's
// repository, each with the Ubuntu package eclipse-stream_<v>_<arch>.deb.
// (They are marked "pre-release" so that the phone app's download link,
// the repository's latest release, stays the phone app.)

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const releasesURL = "https://api.github.com/repos/gintzmarius-commits/eclipse-android/releases?per_page=100"

type pcVersion struct {
	Version string `json:"version"`
	Date    string `json:"date"`  // RFC 3339
	Notes   string `json:"notes"` // Markdown of the release
	Size    int64  `json:"size"`
	url     string
	sha256  string
}

type updateState struct {
	mu        sync.Mutex
	versions  []pcVersion
	fetchedAt time.Time
	fetchErr  string
	busy      bool
	target    string
	phase     string // download, install, restart, done
	percent   float64
	message   string
	detail    string
	errText   string
	dlStart   time.Time
	auto      bool // started by itself when Eclipse opened
	lastCheck time.Time
	lastText  string // result of the last automatic check, shown in the Versions tab
}

// updateSupported: installing versions from the app (Linux, and the
// installed copy of Eclipse on Windows).
func updateSupported() bool {
	if os.Getenv("ECLIPSE_UPDATE_TEST") == "1" {
		return true
	}
	switch runtime.GOOS {
	case "linux":
		return true
	case "windows":
		return !installMode()
	}
	return false
}

// packageName is the file of a version for this PC.
func packageName(v string) string {
	if runtime.GOOS == "windows" {
		return "Eclipse-Setup-" + v + ".exe"
	}
	return "eclipse-stream_" + v + "_" + debArch() + ".deb"
}

var pcTagRe = regexp.MustCompile(`^pc-v(\d+\.\d+\.\d+)$`)

// versionLess compares "2.3.1"-style versions.
func versionLess(a, b string) bool {
	pa, pb := strings.Split(a, "."), strings.Split(b, ".")
	for i := 0; i < len(pa) || i < len(pb); i++ {
		var x, y int
		if i < len(pa) {
			x, _ = strconv.Atoi(pa[i])
		}
		if i < len(pb) {
			y, _ = strconv.Atoi(pb[i])
		}
		if x != y {
			return x < y
		}
	}
	return false
}

func debArch() string {
	if runtime.GOARCH == "arm64" {
		return "arm64"
	}
	return "amd64"
}

// parseReleases keeps the PC versions that have a package for this PC.
func parseReleases(data []byte) ([]pcVersion, error) {
	var rels []struct {
		Tag       string `json:"tag_name"`
		Body      string `json:"body"`
		Draft     bool   `json:"draft"`
		Published string `json:"published_at"`
		Assets    []struct {
			Name   string `json:"name"`
			Size   int64  `json:"size"`
			URL    string `json:"browser_download_url"`
			Digest string `json:"digest"`
		} `json:"assets"`
	}
	if err := json.Unmarshal(data, &rels); err != nil {
		return nil, err
	}
	var out []pcVersion
	for _, r := range rels {
		m := pcTagRe.FindStringSubmatch(r.Tag)
		if m == nil || r.Draft {
			continue
		}
		want := packageName(m[1])
		for _, as := range r.Assets {
			if as.Name != want || !(strings.HasPrefix(as.URL, "https://") || os.Getenv("ECLIPSE_RELEASES_URL") != "") {
				continue
			}
			v := pcVersion{Version: m[1], Date: r.Published, Notes: r.Body, Size: as.Size, url: as.URL}
			if d, ok := strings.CutPrefix(as.Digest, "sha256:"); ok && len(d) == 64 {
				v.sha256 = strings.ToLower(d)
			}
			out = append(out, v)
		}
	}
	sort.Slice(out, func(i, j int) bool { return versionLess(out[j].Version, out[i].Version) })
	return out, nil
}

// fetchVersions asks GitHub for the versions (kept 10 minutes).
func (a *Core) fetchVersions(fresh bool) {
	u := &a.upd
	u.mu.Lock()
	if !fresh && time.Since(u.fetchedAt) < 10*time.Minute && u.versions != nil {
		u.mu.Unlock()
		return
	}
	u.mu.Unlock()
	src := releasesURL
	if t := os.Getenv("ECLIPSE_RELEASES_URL"); t != "" { // tests
		src = t
	}
	c := &http.Client{Timeout: 15 * time.Second}
	req, _ := http.NewRequest("GET", src, nil)
	req.Header.Set("User-Agent", "Eclipse/"+version)
	req.Header.Set("Accept", "application/vnd.github+json")
	var vs []pcVersion
	resp, err := c.Do(req)
	if err == nil {
		data, rerr := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
		resp.Body.Close()
		switch {
		case rerr != nil:
			err = rerr
		case resp.StatusCode == 403 || resp.StatusCode == 429:
			err = errors.New("trop de demandes à GitHub, réessaie dans un moment")
		case resp.StatusCode != 200:
			err = fmt.Errorf("GitHub a répondu %d", resp.StatusCode)
		default:
			vs, err = parseReleases(data)
		}
	}
	u.mu.Lock()
	defer u.mu.Unlock()
	if err != nil {
		u.fetchErr = "Impossible de récupérer la liste des versions (" + err.Error() + ")."
		log.Printf("versions: %v", err)
		return
	}
	u.versions, u.fetchErr, u.fetchedAt = vs, "", time.Now()
}

func (a *Core) versionsView() map[string]any {
	u := &a.upd
	u.mu.Lock()
	defer u.mu.Unlock()
	list := []map[string]any{}
	newest := ""
	for _, v := range u.versions {
		list = append(list, map[string]any{
			"version": v.Version, "date": v.Date, "notes": v.Notes, "size": v.Size,
			"installed": v.Version == version, "newer": versionLess(version, v.Version),
		})
		if versionLess(version, v.Version) && (newest == "" || versionLess(newest, v.Version)) {
			newest = v.Version
		}
	}
	return map[string]any{
		"supported": updateSupported(), "current": version, "versions": list, "newest": newest,
		"error": u.fetchErr, "checked": !u.fetchedAt.IsZero(),
		"busy": u.busy, "target": u.target, "phase": u.phase, "percent": u.percent,
		"message": u.message, "detail": u.detail, "installError": u.errText, "auto": u.auto,
		"lastCheck": lastCheckText(u.lastCheck), "lastText": u.lastText,
	}
}

// updateReporter shows the download and installation of a version.
type updateReporter struct{ a *Core }

func (r updateReporter) set(phase string, pct float64, msg, detail string) {
	u := &r.a.upd
	u.mu.Lock()
	u.phase, u.percent, u.message, u.detail = phase, pct, msg, detail
	u.mu.Unlock()
}

func (r updateReporter) Download(done, total int64) {
	u := &r.a.upd
	u.mu.Lock()
	if u.dlStart.IsZero() {
		u.dlStart = time.Now()
	}
	start := u.dlStart
	u.mu.Unlock()
	if total <= 0 {
		r.set("download", 0, "Téléchargement…", humanBytes(done))
		return
	}
	detail := humanBytes(done) + " sur " + humanBytes(total)
	if el := time.Since(start).Seconds(); el > 1.5 && done > 0 && done < total {
		detail += " · " + humanDuration(float64(total-done)/(float64(done)/el))
	}
	r.set("download", 50*float64(done)/float64(total), "Téléchargement…", detail)
}

func (r updateReporter) Install(frac float64) {
	r.set("install", 50+48*frac, "Installation…", "Ne ferme pas Eclipse")
}

func (r updateReporter) Busy(phase, message string) {
	u := &r.a.upd
	u.mu.Lock()
	u.message, u.detail = message, ""
	u.mu.Unlock()
}

func (a *Core) updateFail(msg string, err error) {
	u := &a.upd
	u.mu.Lock()
	u.busy, u.phase, u.errText = false, "", msg
	if err != nil {
		u.errText += " (" + err.Error() + ")"
	}
	u.lastText = "échec : " + u.errText
	u.mu.Unlock()
	log.Printf("update: %s: %v", msg, err)
}

// installVersion downloads, checks and installs a version, then restarts.
func (a *Core) installVersion(ver string) {
	u := &a.upd
	u.mu.Lock()
	if u.busy {
		u.mu.Unlock()
		return
	}
	var v *pcVersion
	for i := range u.versions {
		if u.versions[i].Version == ver {
			v = &u.versions[i]
		}
	}
	if v == nil {
		u.errText = "Version introuvable : " + ver
		u.mu.Unlock()
		return
	}
	want := *v
	u.busy, u.target, u.phase, u.percent, u.message, u.detail, u.errText, u.dlStart = true, ver, "download", 0, "Téléchargement…", "", "", time.Time{}
	u.mu.Unlock()
	rep := updateReporter{a}

	dir, err := os.MkdirTemp("", "eclipse-update-*")
	if err != nil {
		a.updateFail("Impossible de préparer la mise à jour.", err)
		return
	}
	if runtime.GOOS != "windows" {
		defer os.RemoveAll(dir) // on Windows the installer runs after Eclipse closes
	}
	_ = os.Chmod(dir, 0o755) // apt reads the package as another user
	dest := filepath.Join(dir, packageName(want.Version))
	if err := download(want.url, dest, want.Size, rep); err != nil {
		a.updateFail("Le téléchargement a échoué.", err)
		return
	}
	_ = os.Chmod(dest, 0o644)
	if err := checkPackage(dest, want); err != nil {
		a.updateFail("Le fichier téléchargé n'est pas valable.", err)
		return
	}
	msg := "Entre ton mot de passe pour installer Eclipse " + want.Version
	if runtime.GOOS == "windows" {
		msg = "Installation d'Eclipse " + want.Version + "…"
	}
	rep.set("install", 50, msg, "")
	if err := installPackage(dest, rep); err != nil {
		if strings.Contains(err.Error(), "annulée") {
			a.updateFail("Mise à jour annulée : elle sera proposée à la prochaine ouverture d'Eclipse.", nil)
			return
		}
		a.updateFail("L'installation a échoué.", err)
		return
	}
	log.Printf("update: Eclipse %s installed (was %s)", want.Version, version)
	rep.set("restart", 100, "Redémarrage d'Eclipse…", "")
	time.Sleep(800 * time.Millisecond) // the window shows it
	restartApp()
	u.mu.Lock()
	u.busy, u.phase = false, "done"
	u.mu.Unlock()
}

// checkPackage: same file as published (SHA-256 given by GitHub), and an
// Eclipse package of the expected version.
func checkPackage(path string, want pcVersion) error {
	if want.sha256 != "" {
		f, err := os.Open(path)
		if err != nil {
			return err
		}
		h := sha256.New()
		_, err = io.Copy(h, f)
		f.Close()
		if err != nil {
			return err
		}
		if got := hex.EncodeToString(h.Sum(nil)); got != want.sha256 {
			return fmt.Errorf("empreinte différente (%s…)", got[:12])
		}
	}
	if p, err := exec.LookPath("dpkg-deb"); err == nil && strings.HasSuffix(path, ".deb") {
		out, err := exec.Command(p, "-f", path, "Package", "Version").Output()
		if err != nil {
			return fmt.Errorf("paquet illisible : %v", err)
		}
		fields := map[string]string{}
		for _, line := range strings.Split(string(out), "\n") {
			if k, v, ok := strings.Cut(line, ":"); ok {
				fields[strings.TrimSpace(k)] = strings.TrimSpace(v)
			}
		}
		if fields["Package"] != "eclipse-stream" || fields["Version"] != want.Version {
			return fmt.Errorf("paquet %q version %q", fields["Package"], fields["Version"])
		}
	}
	return nil
}

// autoUpdate: each time Eclipse opens, a newer version is installed by
// itself, exactly like the "Mettre à jour" button (the system asks for the
// password). Cancelled: asked again at the next opening.
func (a *Core) autoUpdate() {
	if !updateSupported() || os.Getenv("ECLIPSE_NO_AUTO_UPDATE") == "1" {
		return
	}
	// Ubuntu asks for the password: only while the window is shown, never
	// out of the blue when Eclipse runs in the background.
	if runtime.GOOS == "linux" && !windowOpen.Load() && os.Getenv("ECLIPSE_NOWINDOW") != "1" {
		return
	}
	a.fetchVersions(true)
	u := &a.upd
	u.mu.Lock()
	u.lastCheck = time.Now()
	u.lastText = "à jour"
	if u.fetchErr != "" {
		u.lastText = "impossible de joindre GitHub (" + u.fetchErr + ")"
	}
	newest := ""
	for _, v := range u.versions {
		if versionLess(version, v.Version) && (newest == "" || versionLess(newest, v.Version)) {
			newest = v.Version
		}
	}
	busy := u.busy
	if newest != "" && !busy {
		u.auto = true
		u.lastText = "installation d'Eclipse " + newest
	}
	u.mu.Unlock()
	if newest == "" || busy {
		return
	}
	log.Printf("update: Eclipse %s available, installing it by itself", newest)
	a.installVersion(newest)
}

// updating: a version is being installed (the window shows it).
func (a *Core) updating() bool {
	u := &a.upd
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.busy || u.phase == "restart"
}

func (a *Core) updateRoutes(api *http.ServeMux) {
	api.HandleFunc("GET /api/versions", func(w http.ResponseWriter, r *http.Request) {
		if updateSupported() {
			a.fetchVersions(r.URL.Query().Get("fresh") == "1")
		}
		writeJSON(w, a.versionsView())
	})
	api.HandleFunc("POST /api/versions/install", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Version string `json:"version"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		if !updateSupported() {
			writeErr(w, "Non disponible sur ce système.", nil)
			return
		}
		go a.installVersion(req.Version)
		writeJSON(w, map[string]bool{"ok": true})
	})
}

func lastCheckText(t time.Time) string {
	if t.IsZero() {
		return ""
	}
	return t.Format("02/01 15:04")
}
