//go:build windows

package main

import (
	"context"
	"encoding/base64"
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
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode/utf16"
	"unsafe"

	"github.com/jchv/go-webview2"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

const platformName = "windows"

const appName = "Eclipse"

const (
	uninstallRoot = `SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\`
	uninstallKey  = uninstallRoot + `EclipseStream`
	legacyKey     = uninstallRoot + `StreamFacile` // the app's previous name
)

func init() { runtime.LockOSThread() } // WebView2 needs the UI on the main thread

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

var (
	programFiles = env("ProgramFiles", `C:\Program Files`)
	programData  = env("ProgramData", `C:\ProgramData`)
	localAppData = env("LOCALAPPDATA", filepath.Join(env("USERPROFILE", `C:\Users\Public`), `AppData\Local`))
	// "Eclipse Stream" rather than "Eclipse" so nothing can be mixed up
	// with other software (e.g. the Eclipse IDE).
	installDir   = filepath.Join(programFiles, "Eclipse Stream")
	installedExe = filepath.Join(installDir, "Eclipse.exe")
	dataRoot     = filepath.Join(programData, "Eclipse Stream")
	cacheRoot    = filepath.Join(localAppData, "Eclipse Stream")

	legacyDir   = filepath.Join(programFiles, "StreamFacile")
	legacyExe   = filepath.Join(legacyDir, "StreamFacile.exe")
	legacyData  = filepath.Join(programData, "StreamFacile")
	legacyCache = filepath.Join(localAppData, "StreamFacile")
)

func hidden(name string, args ...string) *exec.Cmd {
	c := exec.Command(name, args...)
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	return c
}

// powershell runs a script passed as -EncodedCommand (UTF-16LE, base64).
// Feeding it through stdin (-Command -) silently skips multi-line blocks,
// which is what broke stopping and restarting Sunshine in 1.3.2.
func powershell(script string) *exec.Cmd {
	full := "[Console]::OutputEncoding=[Text.Encoding]::UTF8\n" + script + "\nexit $LASTEXITCODE\n"
	u := utf16.Encode([]rune(full))
	b := make([]byte, len(u)*2)
	for i, v := range u {
		b[2*i], b[2*i+1] = byte(v), byte(v>>8)
	}
	return hidden("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-STA",
		"-EncodedCommand", base64.StdEncoding.EncodeToString(b))
}

// sunshineProcessAlive reports whether a sunshine.exe process exists (it may
// still be starting: on slow PCs Sunshine needs about a minute to answer).
func sunshineProcessAlive() bool {
	snap, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPPROCESS, 0)
	if err != nil {
		return false
	}
	defer windows.CloseHandle(snap)
	var e windows.ProcessEntry32
	e.Size = uint32(unsafe.Sizeof(e))
	for err = windows.Process32First(snap, &e); err == nil; err = windows.Process32Next(snap, &e) {
		if strings.EqualFold(windows.UTF16ToString(e.ExeFile[:]), "sunshine.exe") {
			return true
		}
	}
	return false
}

func psQuote(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }

func runTimeout(c *exec.Cmd, d time.Duration) (string, error) {
	var buf strings.Builder
	c.Stdout, c.Stderr = &buf, &buf
	if err := c.Start(); err != nil {
		return "", err
	}
	done := make(chan error, 1)
	go func() { done <- c.Wait() }()
	select {
	case err := <-done:
		return buf.String(), err
	case <-time.After(d):
		_ = c.Process.Kill()
		return buf.String(), errors.New("délai dépassé")
	}
}

// ---- Sunshine -------------------------------------------------------

func sunshineExe() string {
	for _, p := range []string{
		filepath.Join(programFiles, "Sunshine", "sunshine.exe"),
		filepath.Join(env("ProgramW6432", programFiles), "Sunshine", "sunshine.exe"),
	} {
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	if p, err := exec.LookPath("sunshine.exe"); err == nil {
		return p
	}
	// Installed somewhere else: ask the uninstall entries.
	for _, root := range []string{`SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall`, `SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall`} {
		k, err := registry.OpenKey(registry.LOCAL_MACHINE, root, registry.READ)
		if err != nil {
			continue
		}
		names, _ := k.ReadSubKeyNames(-1)
		k.Close()
		for _, n := range names {
			sk, err := registry.OpenKey(registry.LOCAL_MACHINE, root+`\`+n, registry.READ)
			if err != nil {
				continue
			}
			dn, _, _ := sk.GetStringValue("DisplayName")
			loc, _, _ := sk.GetStringValue("InstallLocation")
			sk.Close()
			if strings.Contains(strings.ToLower(dn), "sunshine") && loc != "" {
				p := filepath.Join(loc, "sunshine.exe")
				if _, err := os.Stat(p); err == nil {
					return p
				}
			}
		}
	}
	return ""
}

func sunshineInstalled() bool { return sunshineExe() != "" }

func installSunshine(rep reporter) error {
	arch := "amd64"
	if runtime.GOARCH == "arm64" {
		arch = "arm64"
	}
	url, name, size, err := latestSunshineAsset(func(n string) bool {
		n = strings.ToLower(n)
		return strings.Contains(n, "windows-"+arch) && strings.HasSuffix(n, ".msi")
	})
	if err == nil {
		dest := filepath.Join(os.TempDir(), name)
		if err = download(url, dest, size, rep); err == nil {
			err = installMSI(dest, rep)
			_ = os.Remove(dest)
			if err == nil && sunshineInstalled() {
				return nil
			}
		}
		log.Printf("msi route failed: %v", err)
	} else {
		log.Printf("github: %v", err)
	}

	// Fallback: winget (no measurable progress).
	wg, lerr := exec.LookPath("winget.exe")
	if lerr != nil {
		if err == nil {
			err = errors.New("installation impossible")
		}
		return err
	}
	rep.Busy("install", "Installation de Sunshine avec Windows…")
	out, werr := runTimeout(hidden(wg, "install", "--id", "LizardByte.Sunshine", "-e", "--silent",
		"--accept-source-agreements", "--accept-package-agreements", "--disable-interactivity"), 12*time.Minute)
	log.Printf("winget: %v\n%s", werr, out)
	if sunshineInstalled() {
		return nil
	}
	if err != nil {
		return err
	}
	return werr
}

// ---- MSI install with real progress (Windows Installer external UI) ----

var (
	msiDLL          = windows.NewLazySystemDLL("msi.dll")
	pSetInternalUI  = msiDLL.NewProc("MsiSetInternalUI")
	pSetExternalUI  = msiDLL.NewProc("MsiSetExternalUIW")
	pInstallProduct = msiDLL.NewProc("MsiInstallProductW")

	msiMu      sync.Mutex
	msiState   msiProgress
	msiHandler = windows.NewCallback(msiCallback)
	msiFields  = regexp.MustCompile(`(\d+):\s*(-?\d+)`)
)

type msiProgress struct {
	rep        reporter
	total, pos int64
	forward    bool
	actionData bool
	perTick    int64
	executing  bool
	best       float64
}

func msiCallback(ctx uintptr, msgType uintptr, msg *uint16) uintptr {
	if msg == nil {
		return 0
	}
	kind := uint32(msgType) & 0xFF000000
	msiMu.Lock()
	defer msiMu.Unlock()
	s := &msiState
	switch kind {
	case 0x0A000000: // INSTALLMESSAGE_PROGRESS
		f := map[int]int64{}
		for _, m := range msiFields.FindAllStringSubmatch(windows.UTF16PtrToString(msg), -1) {
			k, _ := strconv.Atoi(m[1])
			v, _ := strconv.ParseInt(m[2], 10, 64)
			f[k] = v
		}
		switch f[1] {
		case 0: // master reset
			s.total = f[2]
			s.forward = f[3] == 0
			if s.forward {
				s.pos = 0
			} else {
				s.pos = s.total
			}
			s.actionData = false
			s.executing = f[4] == 1
		case 1: // action info
			s.actionData = f[3] != 0
			s.perTick = f[2]
		case 2: // progress report
			if s.forward {
				s.pos += f[2]
			} else {
				s.pos -= f[2]
			}
		}
	case 0x09000000: // INSTALLMESSAGE_ACTIONDATA
		if s.actionData {
			if s.forward {
				s.pos += s.perTick
			} else {
				s.pos -= s.perTick
			}
		}
	default:
		return 0
	}
	if s.total > 0 && s.rep != nil {
		frac := float64(s.pos) / float64(s.total)
		if frac < 0 {
			frac = 0
		}
		if frac > 1 {
			frac = 1
		}
		overall := 0.12 * frac // preparing the installation
		if s.executing {
			overall = 0.12 + 0.88*frac // copying files, registering the service…
		}
		if overall > s.best {
			s.best = overall
			s.rep.Install(overall)
		}
	}
	return 0
}

func installMSI(path string, rep reporter) error {
	return installMSIWith(path, "", rep)
}

// installMSIWith installs an MSI with extra properties ("NAME=value …").
func installMSIWith(path, extra string, rep reporter) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()

	msiMu.Lock()
	msiState = msiProgress{rep: rep}
	msiMu.Unlock()
	rep.Install(0)

	const uiNone = 2
	const filter = 0x400 | 0x200 // INSTALLLOGMODE_PROGRESS | INSTALLLOGMODE_ACTIONDATA
	_, _, _ = pSetInternalUI.Call(uiNone, 0)
	prev, _, _ := pSetExternalUI.Call(msiHandler, filter, 0)
	defer pSetExternalUI.Call(prev, 0, 0)

	p, _ := windows.UTF16PtrFromString(path)
	props, _ := windows.UTF16PtrFromString(strings.TrimSpace("REBOOT=ReallySuppress " + extra))
	r, _, _ := pInstallProduct.Call(uintptr(unsafe.Pointer(p)), uintptr(unsafe.Pointer(props)))
	log.Printf("MsiInstallProduct: %d", r)
	switch r {
	case 0, 3010: // success, success but reboot advised
		rep.Install(1)
		return nil
	case 1602:
		return errors.New("installation annulée")
	case 1618:
		return errors.New("une autre installation est en cours sur ce PC, réessaie dans un moment")
	case 1625:
		return errors.New("installation bloquée par une règle de Windows")
	default:
		return fmt.Errorf("Windows Installer a renvoyé le code %d", r)
	}
}

// runningSunshineExe returns the program that is actually answering on
// Sunshine's port (it may not be the one in Program Files).
func runningSunshineExe() string {
	script := `$c = Get-NetTCPConnection -LocalPort 47990 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($c) { $p = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $c.OwningProcess) -ErrorAction SilentlyContinue
  if ($p -and $p.ExecutablePath) { [Console]::Out.Write($p.ExecutablePath) } }
exit 0`
	out, err := runTimeout(powershell(script), 30*time.Second)
	if err != nil {
		return ""
	}
	p := strings.TrimSpace(strings.TrimPrefix(out, "\ufeff"))
	if !strings.EqualFold(filepath.Base(p), "sunshine.exe") {
		return ""
	}
	return p
}

// activeSunshineExe is the Sunshine whose login we must set.
func activeSunshineExe() string {
	if p := runningSunshineExe(); p != "" {
		return p
	}
	return sunshineExe()
}

func sunshineConfigDir() string {
	exe := activeSunshineExe()
	if exe == "" {
		return ""
	}
	return filepath.Join(filepath.Dir(exe), "config")
}

func setSunshineCreds(user, pass string) error {
	exe := activeSunshineExe()
	if exe == "" {
		return errors.New("Sunshine n'est pas installé")
	}
	c := hidden(exe, "--creds", user, pass)
	c.Dir = filepath.Dir(exe)
	out, err := runTimeout(c, time.Minute)
	log.Printf("creds: %v %s", err, out)
	return err
}

const findService = `$s = Get-Service -Name SunshineService -ErrorAction SilentlyContinue
if (-not $s) { $s = Get-Service | Where-Object { $_.Name -like '*sunshine*' -or $_.DisplayName -like '*Sunshine*' } | Select-Object -First 1 }
function WaitStatus($name, $want, $sec) {
  $end = (Get-Date).AddSeconds($sec)
  while ((Get-Date) -lt $end) {
    $st = (Get-Service -Name $name -ErrorAction SilentlyContinue).Status
    if ($want -contains "$st") { return "$st" }
    Start-Sleep -Milliseconds 300
  }
  return "$((Get-Service -Name $name -ErrorAction SilentlyContinue).Status)"
}
# Stops the service (bounded wait), then every leftover copy of Sunshine.
function StopAll($startType) {
  if ($s) {
    Set-Service -Name $s.Name -StartupType $startType -ErrorAction SilentlyContinue
    & sc.exe stop $s.Name | Out-Null
    $st = WaitStatus $s.Name @('Stopped') 25
    Write-Output "service apres arret: $st"
    if ($st -ne 'Stopped') {
      # Stuck in "stopping": end the service process itself.
      Get-Process -Name sunshinesvc -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
      $st = WaitStatus $s.Name @('Stopped') 10
      Write-Output "service apres arret force: $st"
    }
  }
  Get-Process -Name sunshine -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
}
`

// restartSunshine stops the service and every other copy of Sunshine (a
// stray copy would keep the port with an old login), then starts it again.
// It only waits for the service itself; the caller waits for Sunshine.
func restartSunshine() error {
	exe := activeSunshineExe()
	script := findService + `StopAll 'Automatic'
Start-Sleep -Milliseconds 600
if (-not $s) { exit 3 }
& sc.exe start $s.Name | Out-Null
$st = WaitStatus $s.Name @('Running') 20
Write-Output "service apres demarrage: $st"
if ($st -ne 'Running') { exit 4 }
exit 0`
	c := powershell(script)
	out, err := runTimeout(c, 90*time.Second)
	log.Printf("restart: %v %s", err, strings.TrimSpace(out))
	code := -1
	if c.ProcessState != nil {
		code = c.ProcessState.ExitCode()
	}
	if code == 3 || code == 4 { // no service, or it refuses to start: run Sunshine directly
		log.Printf("restart: service unusable (code %d), starting Sunshine directly", code)
		return launchSunshineDirect(exe)
	}
	return err
}

// launchSunshineDirect runs sunshine.exe itself (without its service).
func launchSunshineDirect(exe string) error {
	if exe == "" {
		exe = sunshineExe()
	}
	if exe == "" {
		return errors.New("Sunshine introuvable")
	}
	_ = hidden("taskkill.exe", "/F", "/IM", "sunshine.exe").Run()
	time.Sleep(500 * time.Millisecond)
	c := hidden(exe)
	c.Dir = filepath.Dir(exe)
	if err := c.Start(); err != nil {
		return err
	}
	return c.Process.Release()
}

// stopSunshine turns the Sunshine service off and stops it from starting
// with Windows (restartSunshine turns both back on).
func stopSunshine() error {
	script := findService + `StopAll 'Manual'
exit 0`
	out, err := runTimeout(powershell(script), 70*time.Second)
	log.Printf("stop sunshine: %v %s", err, strings.TrimSpace(out))
	return err
}

// serviceDiagnostics describes the service and processes for the log.
func serviceDiagnostics() string {
	script := findService + `$p = (Get-Process -Name sunshine,sunshinesvc -ErrorAction SilentlyContinue | ForEach-Object { $_.Name + ':' + $_.Id }) -join ','
if ($s) { $c = Get-CimInstance Win32_Service -Filter ("Name='" + $s.Name + "'"); Write-Output ("service=" + $s.Name + " etat=" + $c.State + " demarrage=" + $c.StartMode + " processus=" + $p) } else { Write-Output ("pas de service, processus=" + $p) }
exit 0`
	out, _ := runTimeout(powershell(script), 30*time.Second)
	return strings.TrimSpace(out)
}

// ---- Moonlight ---------------------------------------------------------

const mlDownloadShare = 70.0 // the rest of the bar is the extraction

var moonlightDir = filepath.Join(localAppData, "Programs", "Eclipse Moonlight")

func findFile(dir, name string, depth int) string {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return ""
	}
	for _, e := range entries {
		if !e.IsDir() && strings.EqualFold(e.Name(), name) {
			return filepath.Join(dir, e.Name())
		}
	}
	if depth > 0 {
		for _, e := range entries {
			if e.IsDir() {
				if p := findFile(filepath.Join(dir, e.Name()), name, depth-1); p != "" {
					return p
				}
			}
		}
	}
	return ""
}

func moonlightPath() string {
	if p := os.Getenv("ECLIPSE_MOONLIGHT"); p != "" {
		return p
	}
	if p := findFile(moonlightDir, "Moonlight.exe", 2); p != "" {
		return p
	}
	for _, base := range []string{programFiles, env("ProgramW6432", programFiles), env("ProgramFiles(x86)", "")} {
		if base == "" {
			continue
		}
		if p := filepath.Join(base, "Moonlight Game Streaming", "Moonlight.exe"); exists(p) {
			return p
		}
	}
	return ""
}

func moonlightCmd(ctx context.Context, args ...string) *exec.Cmd {
	p := moonlightPath()
	c := exec.CommandContext(ctx, p, args...)
	c.Dir = filepath.Dir(p)
	c.SysProcAttr = &syscall.SysProcAttr{CreationFlags: 0x08000000} // no console; windows still show
	return c
}

func installMoonlight(rep reporter) error {
	arch := "x64"
	if runtime.GOARCH == "arm64" {
		arch = "arm64"
	}
	url, name, size, err := moonlightAsset(func(n string) bool {
		n = strings.ToLower(n)
		return strings.HasPrefix(n, "moonlightportable-"+arch) && strings.HasSuffix(n, ".zip")
	})
	if err != nil {
		return err
	}
	tmp := filepath.Join(os.TempDir(), name)
	if err := download(url, tmp, size, rep); err != nil {
		return err
	}
	defer os.Remove(tmp)
	stage := moonlightDir + ".new"
	_ = os.RemoveAll(stage)
	if err := extractZip(tmp, stage, rep); err != nil {
		_ = os.RemoveAll(stage)
		return err
	}
	_ = os.RemoveAll(moonlightDir)
	return os.Rename(stage, moonlightDir)
}

// ---- storage ---------------------------------------------------------

func logDir() string    { return dataRoot }
func loginPath() string { return filepath.Join(dataRoot, "login.dat") }

func dpapi(data []byte, encrypt bool) ([]byte, error) {
	if len(data) == 0 {
		return nil, errors.New("vide")
	}
	in := windows.DataBlob{Size: uint32(len(data)), Data: &data[0]}
	var out windows.DataBlob
	var err error
	if encrypt {
		err = windows.CryptProtectData(&in, nil, nil, 0, nil, 1, &out)
	} else {
		err = windows.CryptUnprotectData(&in, nil, nil, 0, nil, 1, &out)
	}
	if err != nil {
		return nil, err
	}
	defer windows.LocalFree(windows.Handle(unsafe.Pointer(out.Data)))
	return append([]byte(nil), unsafe.Slice(out.Data, out.Size)...), nil
}

func protect(data []byte) ([]byte, error)   { return dpapi(data, true) }
func unprotect(data []byte) ([]byte, error) { return dpapi(data, false) }

func decodeUTF16LE(b []byte) string {
	if len(b) >= 2 && b[0] == 0xFF && b[1] == 0xFE {
		b = b[2:]
	}
	u := make([]uint16, len(b)/2)
	for i := range u {
		u[i] = uint16(b[2*i]) | uint16(b[2*i+1])<<8
	}
	return string(utf16.Decode(u))
}

// migrateLegacy imports the data of StreamFacile (the app's previous name,
// including its very first PowerShell version) and removes its leftovers.
// It returns a label like "StreamFacile 1.1.0" when something was imported.
func migrateLegacy() string {
	if !exists(legacyData) {
		return ""
	}
	label := ""
	if loadLogin() == nil {
		if data, err := os.ReadFile(filepath.Join(legacyData, "login.dat")); err == nil {
			if plain, err := unprotect(data); err == nil && len(plain) > 0 {
				_ = os.MkdirAll(dataRoot, 0o755)
				if os.WriteFile(loginPath(), data, 0o600) == nil {
					var st settings
					if raw, err := os.ReadFile(filepath.Join(legacyData, "settings.json")); err == nil {
						_ = os.WriteFile(settingsPath(), raw, 0o644)
						_ = json.Unmarshal(raw, &st)
					}
					v := st.LastVersion
					if v == "" {
						v = "1.0"
					}
					label = "StreamFacile " + v
				}
			}
		}
		if label == "" && importPowerShellLogin(filepath.Join(legacyData, "login.xml")) {
			label = "StreamFacile"
		}
		if label != "" {
			log.Printf("migration: imported %s data", label)
		}
	}
	// Remove the old data once it is safe: ours exists and the old app is gone.
	if loadLogin() != nil && !exists(legacyExe) {
		_ = os.RemoveAll(legacyData)
	}
	return label
}

// importPowerShellLogin reads the login saved by the first PowerShell script.
func importPowerShellLogin(path string) bool {
	raw, err := os.ReadFile(path)
	if err != nil {
		return false
	}
	text := string(raw)
	if len(raw) >= 2 && raw[0] == 0xFF && raw[1] == 0xFE {
		text = decodeUTF16LE(raw)
	}
	user := regexp.MustCompile(`<S N="UserName">([^<]*)</S>`).FindStringSubmatch(text)
	pass := regexp.MustCompile(`<SS N="Password">([0-9a-fA-F]+)</SS>`).FindStringSubmatch(text)
	if user == nil || pass == nil {
		return false
	}
	enc, err := hex.DecodeString(pass[1])
	if err != nil {
		return false
	}
	plain, err := unprotect(enc)
	if err != nil {
		log.Printf("migration: %v", err)
		return false
	}
	return saveLogin(&login{User: user[1], Pass: decodeUTF16LE(plain)}) == nil
}

// ---- interface helpers ----------------------------------------------

func pickProgram() (string, error) {
	script := `Add-Type -AssemblyName System.Windows.Forms
$owner = New-Object System.Windows.Forms.Form -Property @{TopMost = $true; ShowInTaskbar = $false}
$d = New-Object System.Windows.Forms.OpenFileDialog
$d.Title = 'Choisis le jeu à ajouter'
$d.Filter = 'Jeux et raccourcis (*.exe;*.lnk;*.url)|*.exe;*.lnk;*.url'
$d.InitialDirectory = [Environment]::GetFolderPath('Desktop')
if ($d.ShowDialog($owner) -eq 'OK') { [Console]::Out.Write($d.FileName) }
exit 0`
	out, err := powershell(script).Output()
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(strings.TrimPrefix(string(out), "\ufeff")), nil
}

func appFor(path string) appSpec {
	ext := strings.ToLower(filepath.Ext(path))
	if ext == ".lnk" || ext == ".url" {
		return appSpec{Detached: []string{`cmd /c start "" "` + path + `"`}}
	}
	return appSpec{Cmd: `"` + path + `"`, WorkDir: filepath.Dir(path)}
}

func openURL(u string) { _ = hidden("rundll32.exe", "url.dll,FileProtocolHandler", u).Start() }

func runWindow(url string, core *Core) {
	data := filepath.Join(cacheRoot, "WebView2")
	if installMode() {
		// The setup uses its own cache so an update can wipe the app's one.
		data = filepath.Join(os.TempDir(), "Eclipse-Setup-WebView2")
		defer os.RemoveAll(data)
	}
	w := webview2.NewWithOptions(webview2.WebViewOptions{
		AutoFocus: true,
		DataPath:  data,
		WindowOptions: webview2.WindowOptions{
			Title: appName, Width: 980, Height: 700, IconId: 1, Center: true,
		},
	})
	if w == nil {
		openURL(url)
		core.waitUIClosed()
		return
	}
	defer w.Destroy()
	w.SetSize(740, 560, webview2.HintMin)
	w.Navigate(url)
	w.Run()
}

func messageBox(text, title string, flags uint32) int {
	user32 := windows.NewLazySystemDLL("user32.dll")
	proc := user32.NewProc("MessageBoxW")
	t, _ := windows.UTF16PtrFromString(text)
	c, _ := windows.UTF16PtrFromString(title)
	r, _, _ := proc.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), uintptr(flags|0x40000))
	return int(r)
}

func fatal(msg string) {
	messageBox(msg, appName, 0x10)
	os.Exit(1)
}

// ---- installer and updates ---------------------------------------------

func installMode() bool {
	if os.Getenv("ECLIPSE_PORTABLE") == "1" {
		return false
	}
	exe, err := os.Executable()
	if err != nil {
		return false
	}
	return !strings.EqualFold(filepath.Clean(filepath.Dir(exe)), filepath.Clean(installDir))
}

func shortcutPaths(name string) (string, string) {
	start := filepath.Join(programData, `Microsoft\Windows\Start Menu\Programs`, name+".lnk")
	desk := filepath.Join(env("PUBLIC", `C:\Users\Public`), "Desktop", name+".lnk")
	return start, desk
}

func exists(p string) bool { _, err := os.Stat(p); return err == nil }

func regVersion(key string) string {
	k, err := registry.OpenKey(registry.LOCAL_MACHINE, key, registry.READ)
	if err != nil {
		return ""
	}
	defer k.Close()
	v, _, _ := k.GetStringValue("DisplayVersion")
	return v
}

// previousInstall describes what is already on this PC: Eclipse itself, or
// StreamFacile (its previous name). label is "" when nothing is installed.
func previousInstall() (label, ver string, legacy bool) {
	if v := regVersion(uninstallKey); v != "" && exists(installedExe) {
		return v, v, false
	}
	if exists(legacyExe) {
		v := regVersion(legacyKey)
		if v == "" {
			v = "1.0"
		}
		return "StreamFacile " + v, v, true
	}
	return "", "", false
}

type installState struct {
	Mode     bool   `json:"mode"`
	Previous string `json:"previous"`
	Legacy   bool   `json:"legacy"`
	Newer    bool   `json:"newer"`
	Same     bool   `json:"same"`
	Desktop  bool   `json:"desktop"`
}

func installInfo() installState {
	if !installMode() {
		return installState{}
	}
	label, ver, legacy := previousInstall()
	_, desk := shortcutPaths(appName)
	_, oldDesk := shortcutPaths("StreamFacile")
	st := installState{Mode: true, Previous: label, Legacy: legacy,
		Desktop: label == "" || exists(desk) || exists(oldDesk)}
	if label != "" && !legacy {
		c := compareVersions(ver, version)
		st.Newer, st.Same = c > 0, c == 0
	}
	return st
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}

// stopProgram closes running copies of exactly this file (never other
// software that happens to share the name).
func stopProgram(path string) {
	if !exists(path) {
		return
	}
	script := fmt.Sprintf(`Get-Process | Where-Object { $_.Path -and ($_.Path -ieq %s) -and ($_.Id -ne %d) } | Stop-Process -Force -ErrorAction SilentlyContinue
exit 0`, psQuote(path), os.Getpid())
	out, err := runTimeout(powershell(script), 30*time.Second)
	log.Printf("stop %s: %v %s", path, err, out)
	time.Sleep(600 * time.Millisecond)
}

func removeDir(dir, exe string) error {
	if !exists(dir) {
		return nil
	}
	if err := os.RemoveAll(dir); err != nil {
		stopProgram(exe)
		if err := os.RemoveAll(dir); err != nil {
			return fmt.Errorf("ferme %s puis réessaie (%v)", filepath.Base(exe), err)
		}
	}
	return nil
}

// removeLegacy deletes the program files, shortcuts and caches of
// StreamFacile. Its data (accesses, theme) is imported by Eclipse on its
// first launch, then deleted.
func removeLegacy() error {
	if !exists(legacyDir) && regVersion(legacyKey) == "" {
		return nil
	}
	stopProgram(legacyExe)
	if err := removeDir(legacyDir, legacyExe); err != nil {
		return err
	}
	start, desk := shortcutPaths("StreamFacile")
	_ = os.Remove(start)
	_ = os.Remove(desk)
	_ = registry.DeleteKey(registry.LOCAL_MACHINE, legacyKey)
	_ = os.RemoveAll(legacyCache)
	log.Printf("install: StreamFacile removed")
	return nil
}

// selfInstall installs or updates Eclipse. On update, saved data (accesses,
// theme) is kept and every other file of the old version is removed.
func selfInstall(desktop bool) error {
	src, err := os.Executable()
	if err != nil {
		return err
	}
	label, _, _ := previousInstall()
	log.Printf("install: previous=%q new=%s", label, version)

	// Old Eclipse program files and caches (data in ProgramData stays).
	stopProgram(installedExe)
	if err := removeDir(installDir, installedExe); err != nil {
		return err
	}
	_ = os.RemoveAll(cacheRoot)
	start, desk := shortcutPaths(appName)
	_ = os.Remove(start)
	_ = os.Remove(desk)

	// StreamFacile, the app's previous name.
	if err := removeLegacy(); err != nil {
		return err
	}

	if err := os.MkdirAll(installDir, 0o755); err != nil {
		return err
	}
	if err := copyFile(src, installedExe); err != nil {
		return err
	}

	links := []string{start}
	if desktop {
		links = append(links, desk)
	}
	var sb strings.Builder
	sb.WriteString("$sh = New-Object -ComObject WScript.Shell\n")
	for _, l := range links {
		fmt.Fprintf(&sb, "$s = $sh.CreateShortcut(%s); $s.TargetPath = %s; $s.WorkingDirectory = %s; $s.IconLocation = %s; $s.Description = 'Streame tes jeux sur ton téléphone'; $s.Save()\n",
			psQuote(l), psQuote(installedExe), psQuote(installDir), psQuote(installedExe+",0"))
	}
	sb.WriteString("exit 0")
	out, err := runTimeout(powershell(sb.String()), time.Minute)
	log.Printf("shortcuts: %v %s", err, out)

	k, _, err := registry.CreateKey(registry.LOCAL_MACHINE, uninstallKey, registry.ALL_ACCESS)
	if err == nil {
		_ = k.SetStringValue("DisplayName", appName)
		_ = k.SetStringValue("DisplayVersion", version)
		_ = k.SetStringValue("Publisher", appName)
		_ = k.SetStringValue("DisplayIcon", installedExe+",0")
		_ = k.SetStringValue("InstallLocation", installDir)
		_ = k.SetStringValue("UninstallString", `"`+installedExe+`" --uninstall`)
		_ = k.SetDWordValue("NoModify", 1)
		_ = k.SetDWordValue("NoRepair", 1)
		if fi, e := os.Stat(installedExe); e == nil {
			_ = k.SetDWordValue("EstimatedSize", uint32(fi.Size()/1024))
		}
		k.Close()
	}

	setAutostartAt(installedExe, !loadSettings().NoBackground)
	if startBackground {
		return exec.Command(installedExe, "--background").Start()
	}
	return exec.Command(installedExe).Start()
}

// startBackground: the update was started by an Eclipse running in the
// background; the new version starts the same way (no window).
var startBackground bool

func launchInstalled() error {
	if !exists(installedExe) {
		return errors.New("Eclipse n'est pas installé")
	}
	return exec.Command(installedExe).Start()
}

func uninstall() {
	const yesNo, question, info, idYes = 0x4, 0x20, 0x40, 6
	if messageBox("Désinstaller Eclipse de cet ordinateur ?", appName, yesNo|question) != idYes {
		return
	}
	removeSun := messageBox("Désinstaller aussi Sunshine (le serveur de streaming) ?\n\nRéponds Non si tu veux continuer à streamer avec Moonlight.",
		appName, yesNo|question) == idYes

	// Eclipse running in the background (file sharing), its start with
	// Windows and its firewall rule.
	stopProgram(installedExe)
	setAutostartAt("", false)
	removeFirewall()

	start, desk := shortcutPaths(appName)
	_ = os.Remove(start)
	_ = os.Remove(desk)
	_ = registry.DeleteKey(registry.LOCAL_MACHINE, uninstallKey)
	_ = os.RemoveAll(cacheRoot)
	_ = os.RemoveAll(moonlightDir)
	_ = removeLegacy()
	_ = os.RemoveAll(legacyData)

	if removeSun {
		if wg, err := exec.LookPath("winget.exe"); err == nil {
			out, err := runTimeout(hidden(wg, "uninstall", "--id", "LizardByte.Sunshine", "-e", "--silent", "--disable-interactivity"), 10*time.Minute)
			log.Printf("winget uninstall: %v %s", err, out)
		}
		if sunshineInstalled() {
			messageBox("Sunshine n'a pas pu être retiré automatiquement.\nTu peux le désinstaller depuis Paramètres → Applications.", appName, info)
		}
	}
	messageBox("Eclipse a été désinstallé.", appName, info)

	c := hidden("cmd.exe")
	c.SysProcAttr.CmdLine = `cmd.exe /c ping 127.0.0.1 -n 3 > nul & rmdir /s /q "` + installDir +
		`" & rmdir /s /q "` + dataRoot + `"`
	_ = c.Start()
}

// ---------------------------------------------------------------------
// Tailscale (playing away from home)
// ---------------------------------------------------------------------

func tailscaleCLI() string {
	for _, p := range []string{
		filepath.Join(programFiles, "Tailscale", "tailscale.exe"),
		filepath.Join(env("ProgramW6432", programFiles), "Tailscale", "tailscale.exe"),
	} {
		if exists(p) {
			return p
		}
	}
	if p, err := exec.LookPath("tailscale.exe"); err == nil {
		return p
	}
	return ""
}

func tsCommand(cli string, args ...string) *exec.Cmd { return hidden(cli, args...) }

// Unattended: Tailscale stays connected when nobody is logged in on the PC.
func tsUpArgs() []string { return []string{"up", "--unattended"} }

func tailscaleAllowUser() error {
	return errors.New("relance Eclipse en tant qu'administrateur")
}

// installTailscale installs the official Tailscale MSI with real progress:
// started at boot even without a session, accepting incoming connections.
func installTailscale(rep reporter) error {
	arch := "amd64"
	if runtime.GOARCH == "arm64" {
		arch = "arm64"
	}
	var err error
	var name string
	c := &http.Client{Timeout: 20 * time.Second}
	req, _ := http.NewRequest("GET", "https://pkgs.tailscale.com/stable/?mode=json", nil)
	req.Header.Set("User-Agent", "Eclipse/"+version)
	if resp, rerr := c.Do(req); rerr == nil {
		var idx struct {
			MSIs map[string]string
		}
		err = json.NewDecoder(resp.Body).Decode(&idx)
		resp.Body.Close()
		name = idx.MSIs[arch]
		if err == nil && name == "" {
			err = errors.New("aucun installateur Tailscale pour ce PC")
		}
	} else {
		err = rerr
	}
	if err == nil {
		dest := filepath.Join(os.TempDir(), name)
		if err = download("https://pkgs.tailscale.com/stable/"+name, dest, 0, rep); err == nil {
			err = installMSIWith(dest, "TS_NOLAUNCH=1 TS_UNATTENDEDMODE=always TS_ALLOWINCOMINGCONNECTIONS=always", rep)
			_ = os.Remove(dest)
			if err == nil && tailscaleCLI() != "" {
				return nil
			}
		}
		log.Printf("tailscale msi: %v", err)
	}
	// Fallback: winget (no measurable progress).
	wg, lerr := exec.LookPath("winget.exe")
	if lerr != nil {
		return err
	}
	rep.Busy("install", "Installation de Tailscale avec Windows…")
	out, werr := runTimeout(hidden(wg, "install", "--id", "Tailscale.Tailscale", "-e", "--silent",
		"--accept-source-agreements", "--accept-package-agreements", "--disable-interactivity"), 12*time.Minute)
	log.Printf("winget tailscale: %v\n%s", werr, out)
	if tailscaleCLI() != "" {
		return nil
	}
	if werr != nil {
		return werr
	}
	return err
}

func tailscaleStartService() {
	out, err := runTimeout(hidden("sc.exe", "start", "Tailscale"), 30*time.Second)
	log.Printf("sc start Tailscale: %v %s", err, strings.TrimSpace(out))
}

// installPackage starts the downloaded Eclipse-Setup-<version>.exe in
// update mode: it closes this Eclipse, installs itself in place (data,
// shortcuts and paired devices kept) and starts the new version. Eclipse
// runs as administrator, so no extra Windows prompt.
func installPackage(path string, rep reporter) error {
	rep.Install(0.5)
	c := exec.Command(path, append([]string{"--update"}, startArgs()...)...)
	c.Dir = filepath.Dir(path)
	if err := c.Start(); err != nil {
		return err
	}
	log.Printf("update: %s --update started (pid %d)", path, c.Process.Pid)
	_ = c.Process.Release()
	return nil
}

// restartApp: the installer started by installPackage closes this copy
// and starts the new one; leave now so its files can be replaced.
func restartApp() {
	time.Sleep(500 * time.Millisecond)
	clearInstance()
	os.Exit(0)
}

// silentUpdate is the installer run with --update: same as clicking
// "Mettre à jour" in the install window, without any window.
func silentUpdate() error {
	if !installMode() {
		return errors.New("déjà installé à cet endroit")
	}
	_, desk := shortcutPaths(appName)
	// Wait for the old Eclipse to close (it exits right after starting us).
	for i := 0; i < 20 && runningCopies(installedExe) > 0; i++ {
		time.Sleep(500 * time.Millisecond)
	}
	return selfInstall(exists(desk))
}

// runningCopies counts the processes of this exact file (other than us).
func runningCopies(path string) int {
	script := fmt.Sprintf(`@(Get-Process | Where-Object { $_.Path -and ($_.Path -ieq %s) -and ($_.Id -ne %d) }).Count`, psQuote(path), os.Getpid())
	out, err := runTimeout(powershell(script), 20*time.Second)
	if err != nil {
		return 0
	}
	n := 0
	fmt.Sscanf(strings.TrimSpace(out), "%d", &n)
	return n
}
