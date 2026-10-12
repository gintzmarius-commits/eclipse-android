package main

import (
	"bufio"
	"log"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// xdgUserDirs reads ~/.config/user-dirs.dirs (XDG_DESKTOP_DIR="$HOME/Bureau"…).
func xdgUserDirs() map[string]string {
	home, _ := os.UserHomeDir()
	out := map[string]string{}
	f, err := os.Open(filepath.Join(xdg("XDG_CONFIG_HOME", ".config"), "user-dirs.dirs"))
	if err != nil {
		return out
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		k, v, ok := strings.Cut(line, "=")
		if !ok || strings.HasPrefix(line, "#") {
			continue
		}
		v = strings.Trim(v, `"`)
		v = strings.Replace(v, "$HOME", home, 1)
		if filepath.IsAbs(v) && filepath.Clean(v) != filepath.Clean(home) {
			out[strings.TrimSuffix(strings.TrimPrefix(k, "XDG_"), "_DIR")] = v
		}
	}
	return out
}

func fileRoots() []rootEntry {
	home, _ := os.UserHomeDir()
	out := []rootEntry{{Name: "Tout l'ordinateur", Path: "/", Kind: "disk"}}
	if home != "" {
		out = append(out, rootEntry{Name: "Dossier personnel", Path: home, Kind: "home"})
	}
	// Disks and USB keys plugged in.
	user := os.Getenv("USER")
	for _, base := range []string{"/media/" + user, "/run/media/" + user, "/mnt"} {
		des, err := os.ReadDir(base)
		if err != nil {
			continue
		}
		for _, d := range des {
			p := filepath.Join(base, d.Name())
			if st, err := os.Stat(p); err == nil && st.IsDir() {
				out = append(out, rootEntry{Name: d.Name(), Path: p, Kind: "disk"})
			}
		}
	}
	dirs := xdgUserDirs()
	for _, f := range []struct{ key, def, name string }{
		{"DESKTOP", "Desktop", "Bureau"},
		{"DOCUMENTS", "Documents", "Documents"},
		{"DOWNLOAD", "Downloads", "Téléchargements"},
		{"PICTURES", "Pictures", "Images"},
		{"VIDEOS", "Videos", "Vidéos"},
		{"MUSIC", "Music", "Musique"},
	} {
		p := dirs[f.key]
		if p == "" && home != "" {
			p = filepath.Join(home, f.def)
		}
		if st, err := os.Stat(p); err == nil && st.IsDir() {
			out = append(out, rootEntry{Name: f.name, Path: p, Kind: "folder"})
		}
	}
	return out
}

func userDownloads() string {
	if p := xdgUserDirs()["DOWNLOAD"]; p != "" {
		return p
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, "Downloads")
}

func showInFolder(p string) {
	uri := (&url.URL{Scheme: "file", Path: p}).String()
	if err := exec.Command("gdbus", "call", "--session", "--dest", "org.freedesktop.FileManager1",
		"--object-path", "/org/freedesktop/FileManager1", "--method", "org.freedesktop.FileManager1.ShowItems",
		"['"+strings.ReplaceAll(uri, "'", "%27")+"']", "").Run(); err != nil {
		_ = exec.Command("xdg-open", filepath.Dir(p)).Start()
	}
}

// openFirewall: Ubuntu's firewall is off unless the person turned it on;
// changing it needs the administrator password, so it is only reported.
func openFirewall(port int) {
	out, err := exec.Command("ufw", "status").CombinedOutput()
	if err == nil && strings.Contains(string(out), "Status: active") {
		log.Printf("files: ufw is active: port %d may need `sudo ufw allow %d/tcp`", port, port)
	}
}

func removeFirewall() {}

func autostartFile() string {
	return filepath.Join(xdg("XDG_CONFIG_HOME", ".config"), "autostart", appSlug+".desktop")
}

// setAutostart: Eclipse starts (in the background) with the session.
func setAutostart(on bool) {
	if !on {
		_ = os.Remove(autostartFile())
		return
	}
	exe := "/usr/bin/" + appSlug
	if !exists(exe) {
		exe, _ = os.Executable()
	}
	content := "[Desktop Entry]\nType=Application\nName=Eclipse\nComment=Partage de fichiers et streaming de jeux\n" +
		"TryExec=" + exe + "\nExec=" + exe + " --background\nIcon=" + appSlug + "\nTerminal=false\nNoDisplay=true\n" +
		"X-GNOME-Autostart-enabled=true\nX-GNOME-Autostart-Delay=5\n"
	_ = os.MkdirAll(filepath.Dir(autostartFile()), 0o755)
	if old, err := os.ReadFile(autostartFile()); err == nil && string(old) == content {
		return
	}
	if err := os.WriteFile(autostartFile(), []byte(content), 0o644); err != nil {
		log.Printf("autostart: %v", err)
	}
}

func ensureAutostart() {
	if os.Getenv("ECLIPSE_NOWINDOW") == "1" {
		return
	}
	setAutostart(!loadSettings().NoBackground)
}

func closeWindow() {
	windowProc.Lock()
	if windowProc.p != nil {
		_ = windowProc.p.Kill()
	}
	windowProc.Unlock()
}
