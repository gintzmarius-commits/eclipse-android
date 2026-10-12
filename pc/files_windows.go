package main

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"strings"
	"time"

	"golang.org/x/sys/windows"
)

const firewallRule = "Eclipse (partage de fichiers)"

// fileRoots: the disks of this PC, then the person's usual folders.
func fileRoots() []rootEntry {
	out := []rootEntry{}
	mask, _ := windows.GetLogicalDrives()
	for i := 0; i < 26; i++ {
		if mask&(1<<uint(i)) == 0 {
			continue
		}
		letter := string(rune('A' + i))
		root := letter + `:\`
		p, _ := windows.UTF16PtrFromString(root)
		kind := windows.GetDriveType(p)
		if kind == windows.DRIVE_NO_ROOT_DIR || kind == windows.DRIVE_UNKNOWN {
			continue
		}
		if kind == windows.DRIVE_CDROM || kind == windows.DRIVE_REMOVABLE {
			if _, err := os.Stat(root); err != nil {
				continue // no disc / card inside
			}
		}
		label := volumeLabel(root)
		name := "Disque " + letter + ":"
		switch {
		case label != "":
			name = fmt.Sprintf("%s (%s:)", label, letter)
		case kind == windows.DRIVE_FIXED:
			name = fmt.Sprintf("Disque local (%s:)", letter)
		case kind == windows.DRIVE_REMOVABLE:
			name = fmt.Sprintf("Clé USB (%s:)", letter)
		case kind == windows.DRIVE_REMOTE:
			name = fmt.Sprintf("Lecteur réseau (%s:)", letter)
		}
		out = append(out, rootEntry{Name: name, Path: root, Kind: "disk"})
	}
	folders := []struct {
		id   *windows.KNOWNFOLDERID
		name string
	}{
		{windows.FOLDERID_Desktop, "Bureau"},
		{windows.FOLDERID_Documents, "Documents"},
		{windows.FOLDERID_Downloads, "Téléchargements"},
		{windows.FOLDERID_Pictures, "Images"},
		{windows.FOLDERID_Videos, "Vidéos"},
		{windows.FOLDERID_Music, "Musique"},
	}
	for _, f := range folders {
		if p, err := windows.KnownFolderPath(f.id, 0); err == nil && p != "" {
			if st, err := os.Stat(p); err == nil && st.IsDir() {
				out = append(out, rootEntry{Name: f.name, Path: p, Kind: "folder"})
			}
		}
	}
	return out
}

func volumeLabel(root string) string {
	p, _ := windows.UTF16PtrFromString(root)
	buf := make([]uint16, 261)
	if err := windows.GetVolumeInformation(p, &buf[0], uint32(len(buf)), nil, nil, nil, nil, 0); err != nil {
		return ""
	}
	return strings.TrimSpace(windows.UTF16ToString(buf))
}

func userDownloads() string {
	if p, err := windows.KnownFolderPath(windows.FOLDERID_Downloads, 0); err == nil && p != "" {
		return p
	}
	home, _ := os.UserHomeDir()
	return home + `\Downloads`
}

func showInFolder(p string) {
	_ = exec.Command("explorer.exe", "/select,", p).Start()
}

// openFirewall lets phones and browsers of the home network and of
// Tailscale reach the file sharing port (Eclipse runs as administrator).
func openFirewall(port int) {
	if os.Getenv("ECLIPSE_NO_FIREWALL") == "1" {
		return
	}
	show, _ := runTimeout(hidden("netsh.exe", "advfirewall", "firewall", "show", "rule", "name="+firewallRule), 20*time.Second)
	if strings.Contains(show, fmt.Sprint(port)) {
		return
	}
	_, _ = runTimeout(hidden("netsh.exe", "advfirewall", "firewall", "delete", "rule", "name="+firewallRule), 20*time.Second)
	out, err := runTimeout(hidden("netsh.exe", "advfirewall", "firewall", "add", "rule", "name="+firewallRule,
		"dir=in", "action=allow", "protocol=TCP", fmt.Sprintf("localport=%d", port), "profile=any",
		"remoteip=LocalSubnet,100.64.0.0/10", "program="+installedExe), 20*time.Second)
	log.Printf("files: firewall rule: %v %s", err, strings.TrimSpace(out))
}

func removeFirewall() {
	_, _ = runTimeout(hidden("netsh.exe", "advfirewall", "firewall", "delete", "rule", "name="+firewallRule), 20*time.Second)
}

// setAutostart: Eclipse starts (in the background) when the person signs
// in to Windows. A scheduled task, as Eclipse runs as administrator.
func setAutostart(on bool) {
	if installMode() {
		return
	}
	setAutostartAt(installedExe, on)
}

const autostartTask = "Eclipse"

func setAutostartAt(exe string, on bool) {
	if !on {
		out, err := runTimeout(hidden("schtasks.exe", "/Delete", "/TN", autostartTask, "/F"), 20*time.Second)
		log.Printf("autostart off: %v %s", err, strings.TrimSpace(out))
		return
	}
	script := fmt.Sprintf(`$ErrorActionPreference = 'Stop'
$u = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$a = New-ScheduledTaskAction -Execute %s -Argument '--background'
$t = New-ScheduledTaskTrigger -AtLogOn -User $u
$p = New-ScheduledTaskPrincipal -UserId $u -LogonType Interactive -RunLevel Highest
$s = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Seconds 0) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName %s -Action $a -Trigger $t -Principal $p -Settings $s -Force | Out-Null
exit 0`, psQuote(exe), psQuote(autostartTask))
	out, err := runTimeout(powershell(script), time.Minute)
	log.Printf("autostart on: %v %s", err, strings.TrimSpace(out))
}

// ensureAutostart (at each start): the task exists unless turned off.
func ensureAutostart() {
	if installMode() || loadSettings().NoBackground {
		return
	}
	out, err := runTimeout(hidden("schtasks.exe", "/Query", "/TN", autostartTask, "/XML"), 20*time.Second)
	if err == nil && strings.Contains(strings.ToLower(out), strings.ToLower(installedExe)) {
		return
	}
	setAutostartAt(installedExe, true)
}

func closeWindow() {}
