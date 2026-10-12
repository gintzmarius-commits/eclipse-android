//go:build linux

package main

import (
	"bufio"
	"context"
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

const platformName = "linux"

//go:embed linuxwin/window.py
var windowPy []byte

func home() string { h, _ := os.UserHomeDir(); return h }

func xdg(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return filepath.Join(home(), def)
}

// ---- Sunshine -------------------------------------------------------

func sunshineInstalled() bool {
	_, err := exec.LookPath("sunshine")
	return err == nil
}

func osRelease() map[string]string {
	m := map[string]string{}
	data, _ := os.ReadFile("/etc/os-release")
	for _, line := range strings.Split(string(data), "\n") {
		k, v, ok := strings.Cut(line, "=")
		if ok {
			m[k] = strings.Trim(v, `"'`)
		}
	}
	return m
}

func installSunshine(rep reporter) error {
	rel := osRelease()
	arch := runtime.GOARCH
	ver := rel["VERSION_ID"]
	codenames := map[string]string{"jammy": "22.04", "noble": "24.04"}
	if rel["ID"] != "ubuntu" {
		if v, ok := codenames[rel["UBUNTU_CODENAME"]]; ok { // Linux Mint, Pop!_OS, Zorin…
			ver = v
		}
	}
	want := "+ubuntu" + ver + "_" + arch + ".deb"
	if rel["ID"] == "debian" {
		want = "+debian" + rel["VERSION_CODENAME"] + "_" + arch + ".deb"
	}

	url, name, size, err := latestSunshineAsset(func(n string) bool { return strings.HasSuffix(n, want) })
	if err != nil {
		return fmt.Errorf("%v (système : %s %s)", err, rel["NAME"], ver)
	}
	dest := filepath.Join(os.TempDir(), name)
	if err := download(url, dest, size, rep); err != nil {
		return err
	}
	_ = os.Chmod(dest, 0o644)
	defer os.Remove(dest)

	rep.Busy("install", "Entre ton mot de passe pour installer Sunshine")
	return aptInstall(dest, rep)
}

// aptInstall installs a local package with apt (as administrator: asks
// for the password), with real progress. Older versions are accepted
// (going back to an earlier Eclipse).
func aptInstall(dest string, rep reporter) error {
	// APT::Status-Fd gives machine-readable progress lines:
	//   dlstatus:<pkg>:<percent>:<text>   (dependencies being downloaded)
	//   pmstatus:<pkg>:<percent>:<text>   (packages being installed)
	c := exec.Command("pkexec", "apt-get", "install", "-y", "--allow-downgrades", "-o", "APT::Status-Fd=1", dest)
	c.Env = append(os.Environ(), "DEBIAN_FRONTEND=noninteractive")
	stdout, err := c.StdoutPipe()
	if err != nil {
		return err
	}
	var errBuf strings.Builder
	c.Stderr = &errBuf
	if err := c.Start(); err != nil {
		return err
	}
	sc := bufio.NewScanner(stdout)
	started := false
	for sc.Scan() {
		line := sc.Text()
		parts := strings.SplitN(line, ":", 4)
		if len(parts) < 3 {
			continue
		}
		pct, perr := strconv.ParseFloat(parts[2], 64)
		if perr != nil {
			continue
		}
		if !started {
			started = true
			rep.Install(0)
		}
		switch parts[0] {
		case "dlstatus":
			rep.Install(0.4 * pct / 100)
		case "pmstatus":
			rep.Install(0.4 + 0.6*pct/100)
		}
	}
	err = c.Wait()
	log.Printf("apt: %v\n%s", err, errBuf.String())
	if err != nil {
		if c.ProcessState != nil && (c.ProcessState.ExitCode() == 126 || c.ProcessState.ExitCode() == 127) {
			return errors.New("installation annulée (mot de passe non saisi)")
		}
		if msg := lastLine(errBuf.String()); msg != "" {
			return errors.New(msg)
		}
		return err
	}
	rep.Install(1)
	return nil
}

// installPackage installs a version of Eclipse (Versions tab).
func installPackage(path string, rep reporter) error { return aptInstall(path, rep) }

func silentUpdate() error { return errors.New("non disponible") }

var windowProc struct {
	sync.Mutex
	p *os.Process
}

// restartApp starts the newly installed Eclipse and ends this one.
func restartApp() {
	if os.Getenv("ECLIPSE_NOWINDOW") == "1" {
		log.Printf("update: restart skipped (no window)")
		return
	}
	exe := "/usr/bin/" + appSlug
	if !exists(exe) {
		exe, _ = os.Executable()
		exe = strings.TrimSuffix(exe, " (deleted)")
	}
	c := exec.Command("sh", append([]string{"-c", `sleep 1; exec "$0" "$@"`, exe}, startArgs()...)...)
	c.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := c.Start(); err != nil {
		log.Printf("update: restart: %v", err)
		return
	}
	windowProc.Lock()
	if windowProc.p != nil {
		_ = windowProc.p.Kill()
	}
	windowProc.Unlock()
	log.Printf("update: restarting")
	clearInstance()
	os.Exit(0)
}

func sunshineConfigDir() string {
	if d := os.Getenv("ECLIPSE_SUNSHINE_CONFIG"); d != "" { // test hook
		return d
	}
	return filepath.Join(xdg("XDG_CONFIG_HOME", ".config"), "sunshine")
}

// runningSunshineExe returns the path of a running Sunshine, if any.
func runningSunshineExe() string {
	entries, _ := os.ReadDir("/proc")
	for _, e := range entries {
		comm, err := os.ReadFile(filepath.Join("/proc", e.Name(), "comm"))
		if err != nil || strings.TrimSpace(string(comm)) != "sunshine" {
			continue
		}
		if p, err := os.Readlink(filepath.Join("/proc", e.Name(), "exe")); err == nil {
			return p
		}
	}
	return ""
}

func setSunshineCreds(user, pass string) error {
	out, err := exec.Command("sunshine", "--creds", user, pass).CombinedOutput()
	log.Printf("creds: %v %s", err, out)
	return err
}

func sunshineUnit() string {
	out, _ := exec.Command("systemctl", "--user", "list-unit-files", "--no-legend", "--type=service").Output()
	for _, line := range strings.Split(string(out), "\n") {
		f := strings.Fields(line)
		if len(f) > 0 && strings.Contains(strings.ToLower(f[0]), "sunshine") {
			return f[0]
		}
	}
	return ""
}

func restartSunshine() error {
	if u := sunshineUnit(); u != "" {
		// A stray copy started by hand would keep the port with an old login.
		_ = exec.Command("systemctl", "--user", "stop", u).Run()
		_ = exec.Command("pkill", "-x", "sunshine").Run()
		_ = exec.Command("systemctl", "--user", "enable", u).Run()
		out, err := exec.Command("systemctl", "--user", "restart", u).CombinedOutput()
		log.Printf("restart %s: %v %s", u, err, out)
		if err == nil {
			return nil
		}
	}
	_ = exec.Command("pkill", "-x", "sunshine").Run()
	time.Sleep(time.Second)
	c := exec.Command("sunshine")
	c.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if f, err := os.Create(filepath.Join(logDir(), "sunshine.log")); err == nil {
		c.Stdout, c.Stderr = f, f
	}
	if err := c.Start(); err != nil {
		return err
	}
	return c.Process.Release()
}

// launchSunshineDirect runs sunshine itself (without its systemd unit).
func launchSunshineDirect(string) error {
	_ = exec.Command("pkill", "-x", "sunshine").Run()
	time.Sleep(500 * time.Millisecond)
	c := exec.Command("sunshine")
	c.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if f, err := os.Create(filepath.Join(logDir(), "sunshine.log")); err == nil {
		c.Stdout, c.Stderr = f, f
	}
	if err := c.Start(); err != nil {
		return err
	}
	return c.Process.Release()
}

func sunshineProcessAlive() bool { return runningSunshineExe() != "" }

func serviceDiagnostics() string {
	return fmt.Sprintf("unit=%q running=%q", sunshineUnit(), runningSunshineExe())
}

func stopSunshine() error {
	if u := sunshineUnit(); u != "" {
		out, err := exec.Command("systemctl", "--user", "stop", u).CombinedOutput()
		log.Printf("stop %s: %v %s", u, err, out)
		_ = exec.Command("systemctl", "--user", "disable", u).Run()
	}
	_ = exec.Command("pkill", "-x", "sunshine").Run()
	return nil
}

// ---- Moonlight ---------------------------------------------------------

const mlDownloadShare = 100.0

func exists(p string) bool { _, err := os.Stat(p); return err == nil }

func appImagePath() string {
	return filepath.Join(xdg("XDG_DATA_HOME", ".local/share"), appSlug, "Moonlight.AppImage")
}

var flatpakCheck struct {
	sync.Mutex
	at time.Time
	ok bool
}

func hasFlatpakMoonlight() bool {
	flatpakCheck.Lock()
	defer flatpakCheck.Unlock()
	if time.Since(flatpakCheck.at) < 30*time.Second {
		return flatpakCheck.ok
	}
	flatpakCheck.at = time.Now()
	flatpakCheck.ok = false
	if fp, err := exec.LookPath("flatpak"); err == nil {
		flatpakCheck.ok = exec.Command(fp, "info", "com.moonlight_stream.Moonlight").Run() == nil
	}
	return flatpakCheck.ok
}

func moonlightPath() string {
	if p := os.Getenv("ECLIPSE_MOONLIGHT"); p != "" {
		return p
	}
	if exists(appImagePath()) {
		return appImagePath()
	}
	for _, n := range []string{"moonlight", "moonlight-qt"} {
		if p, err := exec.LookPath(n); err == nil {
			return p
		}
	}
	if hasFlatpakMoonlight() {
		return "flatpak"
	}
	return ""
}

func hasFuse2() bool {
	for _, p := range []string{"/usr/lib/x86_64-linux-gnu/libfuse.so.2", "/lib/x86_64-linux-gnu/libfuse.so.2", "/usr/lib64/libfuse.so.2", "/usr/lib/libfuse.so.2"} {
		if exists(p) {
			return true
		}
	}
	return false
}

func moonlightCmd(ctx context.Context, args ...string) *exec.Cmd {
	p := moonlightPath()
	if p == "flatpak" {
		return exec.CommandContext(ctx, "flatpak", append([]string{"run", "com.moonlight_stream.Moonlight"}, args...)...)
	}
	c := exec.CommandContext(ctx, p, args...)
	if strings.HasSuffix(p, ".AppImage") && !hasFuse2() {
		// Ubuntu 22.04+ ships without FUSE 2: let the AppImage unpack itself.
		c.Env = append(os.Environ(), "APPIMAGE_EXTRACT_AND_RUN=1")
	}
	return c
}

func installMoonlight(rep reporter) error {
	if runtime.GOARCH != "amd64" {
		return errors.New("installe Moonlight depuis la logithèque (Flatpak) sur ce type de PC")
	}
	url, _, size, err := moonlightAsset(func(n string) bool { return strings.HasSuffix(n, "-x86_64.AppImage") })
	if err != nil {
		return err
	}
	dst := appImagePath()
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	part := dst + ".part"
	if err := download(url, part, size, rep); err != nil {
		_ = os.Remove(part)
		return err
	}
	if err := os.Chmod(part, 0o755); err != nil {
		return err
	}
	if err := os.Rename(part, dst); err != nil {
		return err
	}
	rep.Install(1)
	return nil
}

// ---- storage ---------------------------------------------------------

const appSlug = "eclipse-stream"

func logDir() string { return filepath.Join(xdg("XDG_STATE_HOME", ".local/state"), appSlug) }
func loginPath() string {
	return filepath.Join(xdg("XDG_CONFIG_HOME", ".config"), appSlug, "login.json")
}

// Data of the app's previous name (StreamFacile).
func legacyConfigDir() string {
	return filepath.Join(xdg("XDG_CONFIG_HOME", ".config"), "streamfacile")
}
func legacyStateDir() string {
	return filepath.Join(xdg("XDG_STATE_HOME", ".local/state"), "streamfacile")
}

// migrateLegacy imports the data of StreamFacile (the app's previous name)
// and removes its leftovers. It returns a label like "StreamFacile 1.1.0"
// when something was imported.
func migrateLegacy() string {
	old := legacyConfigDir()
	if _, err := os.Stat(old); err != nil {
		return ""
	}
	label := ""
	if loadLogin() == nil {
		if data, err := os.ReadFile(filepath.Join(old, "login.json")); err == nil {
			_ = os.MkdirAll(dataDir(), 0o700)
			if os.WriteFile(loginPath(), data, 0o600) == nil {
				var st settings
				if raw, err := os.ReadFile(filepath.Join(old, "settings.json")); err == nil {
					_ = os.WriteFile(settingsPath(), raw, 0o644)
					_ = json.Unmarshal(raw, &st)
				}
				v := st.LastVersion
				if v == "" {
					v = "1.0"
				}
				label = "StreamFacile " + v
				log.Printf("migration: imported data from %s", old)
			}
		}
	}
	if loadLogin() != nil {
		_ = os.RemoveAll(old)
		_ = os.RemoveAll(legacyStateDir())
	}
	return label
}

func protect(b []byte) ([]byte, error)   { return b, nil } // file is chmod 600 in the user's home
func unprotect(b []byte) ([]byte, error) { return b, nil }

// ---- interface helpers ----------------------------------------------

func pickProgram() (string, error) {
	if z, err := exec.LookPath("zenity"); err == nil {
		c := exec.Command(z, "--file-selection", "--title=Choisis le jeu à ajouter", "--filename="+home()+"/")
		out, err := c.Output()
		if err != nil {
			if c.ProcessState != nil && c.ProcessState.ExitCode() == 1 {
				return "", nil // cancelled
			}
			return "", err
		}
		return strings.TrimSpace(string(out)), nil
	}
	if k, err := exec.LookPath("kdialog"); err == nil {
		out, err := exec.Command(k, "--getopenfilename", home(), "--title", "Choisis le jeu à ajouter").Output()
		if err != nil {
			return "", nil
		}
		return strings.TrimSpace(string(out)), nil
	}
	return "", errors.New("installe « zenity » pour choisir un fichier")
}

func appFor(path string) appSpec {
	if strings.HasSuffix(strings.ToLower(path), ".desktop") {
		return appSpec{Detached: []string{`gio launch "` + path + `"`}}
	}
	return appSpec{Cmd: `"` + path + `"`, WorkDir: filepath.Dir(path)}
}

func openURL(u string) { _ = exec.Command("xdg-open", u).Start() }

func runWindow(url string, core *Core) {
	f, err := os.CreateTemp("", "eclipse-window-*.py")
	if err == nil {
		_, _ = f.Write(windowPy)
		f.Close()
		defer os.Remove(f.Name())
		start := time.Now()
		c := exec.Command("python3", f.Name(), url)
		var outBuf strings.Builder
		c.Stdout, c.Stderr = &outBuf, &outBuf
		werr := c.Start()
		if werr == nil {
			windowProc.Lock()
			windowProc.p = c.Process
			windowProc.Unlock()
			werr = c.Wait()
		}
		out := outBuf.String()
		if werr == nil || time.Since(start) > 8*time.Second {
			return // the window was shown and then closed
		}
		log.Printf("native window unavailable: %v %s", werr, out)
	}
	// Fallback: a Chromium-based browser in "app" mode, or the default browser.
	for _, b := range []string{"chromium", "chromium-browser", "google-chrome", "google-chrome-stable", "microsoft-edge", "brave-browser"} {
		if p, err := exec.LookPath(b); err == nil {
			if exec.Command(p, "--app="+url, "--class="+appSlug, "--window-size=960,680").Start() == nil {
				core.waitUIClosed()
				return
			}
		}
	}
	openURL(url)
	core.waitUIClosed()
}

func fatal(msg string) {
	if z, err := exec.LookPath("zenity"); err == nil {
		_ = exec.Command(z, "--error", "--title=Eclipse", "--text="+msg).Run()
	}
	fmt.Fprintln(os.Stderr, msg)
	os.Exit(1)
}

func installMode() bool              { return false }
func selfInstall(desktop bool) error { return errors.New("utilise le paquet .deb") }
func launchInstalled() error         { return errors.New("non disponible") }

type installState struct {
	Mode bool `json:"mode"`
}

func installInfo() installState { return installState{} }
func uninstall()                { fmt.Println("Pour désinstaller : sudo apt remove eclipse-stream") }

// ---------------------------------------------------------------------
// Tailscale (playing away from home)
// ---------------------------------------------------------------------

func tailscaleCLI() string {
	if p, err := exec.LookPath("tailscale"); err == nil {
		return p
	}
	for _, p := range []string{"/usr/bin/tailscale", "/usr/local/bin/tailscale"} {
		if exists(p) {
			return p
		}
	}
	return ""
}

func tsCommand(cli string, args ...string) *exec.Cmd { return exec.Command(cli, args...) }

func tsUpArgs() []string { return []string{"up"} }

func currentUser() string {
	if u := os.Getenv("USER"); u != "" {
		return u
	}
	out, _ := exec.Command("id", "-un").Output()
	return strings.TrimSpace(string(out))
}

// tailscaleAllowUser lets this user control Tailscale without sudo.
func tailscaleAllowUser() error {
	out, err := exec.Command("pkexec", "tailscale", "set", "--operator="+currentUser()).CombinedOutput()
	log.Printf("tailscale set --operator: %v %s", err, out)
	if err != nil {
		return errors.New("mot de passe non saisi")
	}
	return nil
}

// installTailscale runs Tailscale's official installer (it adds Tailscale's
// package source, so it is then updated with the rest of the system).
func installTailscale(rep reporter) error {
	dest := filepath.Join(os.TempDir(), "tailscale-install.sh")
	if err := download("https://tailscale.com/install.sh", dest, 0, rep); err != nil {
		return err
	}
	defer os.Remove(dest)
	rep.Busy("install", "Entre ton mot de passe pour installer Tailscale")
	script := `sh "$0" && (tailscale set --operator="$1" || true)`
	out, err := exec.Command("pkexec", "sh", "-c", script, dest, currentUser()).CombinedOutput()
	log.Printf("tailscale install: %v\n%s", err, out)
	if err != nil {
		var ee *exec.ExitError
		if errors.As(err, &ee) && (ee.ExitCode() == 126 || ee.ExitCode() == 127) {
			return errors.New("installation annulée (mot de passe non saisi)")
		}
		return fmt.Errorf("%v : %s", err, lastLine(string(out)))
	}
	rep.Install(1)
	return nil
}

func lastLine(s string) string {
	lines := strings.Split(strings.TrimSpace(s), "\n")
	return strings.TrimSpace(lines[len(lines)-1])
}

func tailscaleStartService() {
	out, err := exec.Command("systemctl", "start", "tailscaled").CombinedOutput()
	log.Printf("systemctl start tailscaled: %v %s", err, strings.TrimSpace(string(out)))
}

var startBackground bool
