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
	"strconv"
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
	fwd      *forwarder
	fwdKey   string
	lastLog  string
)

func init() {
	// Android does not let apps list network interfaces (golang/go#40569):
	// report the addresses the system uses to reach the Internet instead.
	netmon.RegisterInterfaceGetter(androidInterfaces)
}

// Start joins the private network (state kept in dir). Calling it again
// does nothing. Signing in is needed the first time (see Status).
func Start(dir, hostname string) error {
	mu.Lock()
	defer mu.Unlock()
	if srv != nil {
		return startErr
	}
	envknob.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	logf := func(format string, args ...any) {
		s := fmt.Sprintf(format, args...)
		mu2.Lock()
		lastLog = s
		mu2.Unlock()
		log.Print("eclipsenet: " + s)
	}
	srv = &tsnet.Server{
		Dir:        filepath.Clean(dir),
		Hostname:   hostname,
		ControlURL: controlURL,
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
func Status() string {
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
func Login() error {
	lc, err := client()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	return lc.StartLoginInteractive(ctx)
}

// Logout disconnects this phone from the account.
func Logout() error {
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

// Forward relays 127.0.0.1 ports to the PC's Tailscale address. Ports
// are comma-separated lists. Calling it again with other values replaces
// the previous relay; with the same values it does nothing.
func Forward(target, tcpPorts, udpPorts string) error {
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
	if fwd != nil && fwdKey == key {
		return nil
	}
	if fwd != nil {
		fwd.Close()
		fwd, fwdKey = nil, ""
	}
	s := srv
	f, err := startForward(s.Dial, target, tp, up)
	if err != nil {
		return err
	}
	fwd, fwdKey = f, key
	return nil
}

// Probe tries a TCP connection to target:port over the private network
// and returns "" when it works, or the error.
func Probe(target string, port int, timeoutMs int) string {
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
