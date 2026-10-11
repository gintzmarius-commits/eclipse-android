// Package eclipsenet is Eclipse's built-in Tailscale: it joins the
// user's private network (tailnet) from inside the app, without the
// Tailscale app nor a VPN, and relays Moonlight's traffic from 127.0.0.1
// to the PC's Tailscale address. Built into an Android library with
// gomobile (Java class eclipsenet.Eclipsenet).
package eclipsenet

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/envknob"
	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

// controlURL is the coordination server (tests use a local one).
var controlURL string

var (
	mu       sync.Mutex
	srv      *tsnet.Server
	startErr error
	fwds     = map[string]*forwarder{} // by local address (127.x.x.x)
	fwdKeys  = map[string]string{}
	lastLog  string
)

func init() {
	// Android does not let apps list network interfaces (golang/go#40569):
	// report the addresses the system uses to reach the Internet instead.
	netmon.RegisterInterfaceGetter(androidInterfaces)
}

// writable reports whether files can be created in dir.
func writable(dir string) bool {
	f, err := os.CreateTemp(dir, ".eclipse-*")
	if err != nil {
		return false
	}
	f.Close()
	os.Remove(f.Name())
	return true
}

// SetCrashFile makes the Go runtime write the report of a fatal error to
// this file (shown by Eclipse after a crash of the network engine).
func SetCrashFile(path string) (err error) {
	defer catch(&err)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0o600)
	if err != nil {
		return err
	}
	return debug.SetCrashOutput(f, debug.CrashOptions{})
}

// catch turns a panic in a function called by the app into an error.
func catch(err *error) {
	if r := recover(); r != nil {
		*err = fmt.Errorf("erreur interne : %v", r)
		log.Printf("eclipsenet: panic: %v\n%s", r, debug.Stack())
	}
}

// Start joins the private network (state kept in dir). Calling it again
// does nothing. Signing in is needed the first time (see Status).
func Start(dir, hostname string) (err error) {
	defer catch(&err)
	mu.Lock()
	defer mu.Unlock()
	if srv != nil {
		return startErr
	}
	envknob.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	// An Android app has no home, cache or temporary folder that Go knows
	// of: Tailscale would stop the whole app ("no safe place found to store
	// log state", Eclipse 2.2.0). Give it folders inside the app's own.
	for env, sub := range map[string]string{"TS_LOGS_DIR": "logs", "TMPDIR": "tmp", "HOME": "home"} {
		d := filepath.Join(dir, sub)
		if err := os.MkdirAll(d, 0o700); err != nil {
			return err
		}
		if env == "TS_LOGS_DIR" || os.Getenv(env) == "" || !writable(os.Getenv(env)) {
			os.Setenv(env, d)
		}
	}
	control := controlURL
	// Tests on an emulator point the app to a local test network (this
	// file can only be written by the app itself or by root).
	if b, err := os.ReadFile(filepath.Join(dir, "test-control-url")); err == nil {
		control = strings.TrimSpace(string(b))
	}
	logf := func(format string, args ...any) {
		s := fmt.Sprintf(format, args...)
		mu2.Lock()
		lastLog = s
		mu2.Unlock()
		log.Print("eclipsenet: " + s)
	}
	// Tests on an emulator check that Eclipse survives a failure of the
	// network engine (same protection: only the app or root can write it).
	if _, err := os.Stat(filepath.Join(dir, "test-crash")); err == nil {
		go func() {
			time.Sleep(3 * time.Second)
			panic("eclipse: test crash of the network engine")
		}()
	}
	srv = &tsnet.Server{
		Dir:        filepath.Clean(dir),
		Hostname:   hostname,
		ControlURL: control,
		Logf:       logf,
		UserLogf:   logf,
	}
	startErr = srv.Start()
	return startErr
}

var mu2 sync.Mutex

type status struct {
	State   string `json:"state"` // stopped, starting, needslogin, approval, running, error
	AuthURL string `json:"authUrl,omitempty"`
	IP      string `json:"ip,omitempty"`
	Account string `json:"account,omitempty"`
	Error   string `json:"error,omitempty"`
}

// Status returns the state as JSON.
func Status() (out string) {
	defer func() {
		if r := recover(); r != nil {
			b, _ := json.Marshal(status{State: "error", Error: fmt.Sprintf("erreur interne : %v", r)})
			out = string(b)
		}
	}()
	b, _ := json.Marshal(getStatus())
	return string(b)
}

func getStatus() status {
	mu.Lock()
	s, serr := srv, startErr
	mu.Unlock()
	if s == nil {
		return status{State: "stopped"}
	}
	if serr != nil {
		return status{State: "error", Error: serr.Error()}
	}
	lc, err := s.LocalClient()
	if err != nil {
		return status{State: "error", Error: err.Error()}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil {
		return status{State: "starting"}
	}
	out := status{AuthURL: st.AuthURL}
	switch st.BackendState {
	case "Running":
		out.State = "running"
	case "NeedsLogin", "NoState":
		out.State = "needslogin"
	case "NeedsMachineAuth":
		out.State = "approval"
	default:
		out.State = "starting"
	}
	if st.Self != nil {
		for _, ip := range st.Self.TailscaleIPs {
			if ip.Is4() {
				out.IP = ip.String()
			}
		}
		if u, ok := st.User[st.Self.UserID]; ok {
			out.Account = u.LoginName
		}
	}
	if out.State == "running" && out.IP == "" {
		out.State = "starting"
	}
	return out
}

// Login asks for a sign-in page (Status then gives its address).
func Login() (err error) {
	defer catch(&err)
	lc, err := client()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	return lc.StartLoginInteractive(ctx)
}

// Logout disconnects this phone from the account.
func Logout() (err error) {
	defer catch(&err)
	lc, err := client()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	return lc.Logout(ctx)
}

func client() (interface {
	StartLoginInteractive(context.Context) error
	Logout(context.Context) error
}, error) {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return nil, errors.New("not started")
	}
	return s.LocalClient()
}

// Forward relays 127.0.0.1 ports to the PC's Tailscale address (see
// ForwardAt).
func Forward(target, tcpPorts, udpPorts string) error {
	return ForwardAt("127.0.0.1", target, tcpPorts, udpPorts)
}

// ForwardAt relays ports of local (a 127.x.x.x address, one per PC) to the
// PC's Tailscale address. Ports are comma-separated lists. Calling it again
// with other values replaces that relay; with the same values it does
// nothing.
func ForwardAt(local, target, tcpPorts, udpPorts string) (err error) {
	defer catch(&err)
	lip, err := loopback(local)
	if err != nil {
		return err
	}
	if _, err := netip.ParseAddr(target); err != nil {
		return fmt.Errorf("adresse invalide : %q", target)
	}
	tp, err := parsePorts(tcpPorts)
	if err != nil {
		return err
	}
	up, err := parsePorts(udpPorts)
	if err != nil {
		return err
	}
	mu.Lock()
	defer mu.Unlock()
	if srv == nil {
		return errors.New("not started")
	}
	key := target + "|" + tcpPorts + "|" + udpPorts
	if fwds[local] != nil && fwdKeys[local] == key {
		return nil
	}
	if old := fwds[local]; old != nil {
		old.Close()
		delete(fwds, local)
		delete(fwdKeys, local)
	}
	f, err := startForward(srv.Dial, lip, target, tp, up)
	if err != nil {
		return err
	}
	fwds[local], fwdKeys[local] = f, key
	return nil
}

// StopForward ends the relay of local (nothing if there is none).
func StopForward(local string) {
	mu.Lock()
	f := fwds[local]
	delete(fwds, local)
	delete(fwdKeys, local)
	mu.Unlock()
	if f != nil {
		f.Close()
	}
}

// loopback checks that a relay address is one of this phone's own
// (127.0.0.0/8): the relay never listens on a network.
func loopback(s string) (net.IP, error) {
	a, err := netip.ParseAddr(s)
	if err != nil || !a.Is4() || !a.IsLoopback() {
		return nil, fmt.Errorf("adresse locale invalide : %q", s)
	}
	return net.IP(a.AsSlice()), nil
}

// Probe tries a TCP connection to target:port over the private network
// and returns "" when it works, or the error.
func Probe(target string, port int, timeoutMs int) (out string) {
	defer func() {
		if r := recover(); r != nil {
			out = fmt.Sprint("erreur interne : ", r)
		}
	}()
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return "not started"
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	c, err := s.Dial(ctx, "tcp", net.JoinHostPort(target, strconv.Itoa(port)))
	if err != nil {
		return err.Error()
	}
	c.Close()
	return ""
}

// LastLog is the last message of the network engine (for diagnostics).
func LastLog() string {
	mu2.Lock()
	defer mu2.Unlock()
	return lastLog
}

// androidInterfaces reports one interface carrying the addresses the
// system uses for outgoing traffic (no packet is sent to find them).
func androidInterfaces() ([]netmon.Interface, error) {
	var addrs []net.Addr
	if ip, ok := outboundIP("udp4", "8.8.8.8:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(32, 32)})
	}
	if ip, ok := outboundIP("udp6", "[2001:4860:4860::8888]:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(128, 128)})
	}
	if len(addrs) == 0 {
		return nil, nil // offline for now: no interfaces
	}
	return []netmon.Interface{{
		Interface: &net.Interface{Index: 1, MTU: 1500, Name: "android", Flags: net.FlagUp | net.FlagRunning | net.FlagMulticast},
		AltAddrs:  addrs,
	}}, nil
}

func outboundIP(network, addr string) (netip.Addr, bool) {
	d := net.Dialer{Timeout: 2 * time.Second}
	c, err := d.Dial(network, addr)
	if err != nil {
		return netip.Addr{}, false
	}
	defer c.Close()
	ua, ok := c.LocalAddr().(*net.UDPAddr)
	if !ok {
		return netip.Addr{}, false
	}
	ip, ok := netip.AddrFromSlice(ua.IP)
	if !ok {
		return netip.Addr{}, false
	}
	ip = ip.Unmap()
	if ip.IsLoopback() || ip.IsUnspecified() {
		return netip.Addr{}, false
	}
	return ip, true
}
