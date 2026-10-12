package eclipsenet

// A "PC" on a private Tailscale network of its own, for the tests of the
// app on the Android emulator (scripts/smoke_test.sh, part 3): coordination
// server, relay (DERP) and a PC answering like Sunshine. The emulator
// reaches this machine at 10.0.2.2; the CI gives this machine the address
// 10.0.2.2 too, so both sides use the same addresses.
//
//	ECLIPSE_FAKE_PC=/tmp/fakepc go test -run TestEmulatorPC -timeout 60m .
//
// writes "<control URL> <PC's Tailscale address>" to $ECLIPSE_FAKE_PC, then
// runs until the file $ECLIPSE_FAKE_PC.stop exists.

import (
	"context"
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"tailscale.com/derp/derpserver"
	"tailscale.com/ipn/store/mem"
	"tailscale.com/net/netns"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
)

const emulatorHost = "10.0.2.2"

func TestEmulatorPC(t *testing.T) {
	out := os.Getenv("ECLIPSE_FAKE_PC")
	if out == "" {
		t.Skip("set ECLIPSE_FAKE_PC")
	}
	netns.SetEnabled(false)

	// Relay (DERP), on every address of this machine.
	d := derpserver.New(key.NewNode(), t.Logf)
	dln, err := net.Listen("tcp", "0.0.0.0:0")
	if err != nil {
		t.Fatal(err)
	}
	derp := httptest.NewUnstartedServer(derpserver.Handler(d))
	derp.Listener.Close()
	derp.Listener = dln
	derp.Config.TLSNextProto = make(map[string]func(*http.Server, *tls.Conn, http.Handler))
	derp.StartTLS()
	defer derp.Close()
	derpMap := &tailcfg.DERPMap{Regions: map[tailcfg.DERPRegionID]*tailcfg.DERPRegion{
		1: {RegionID: 1, RegionCode: "test", Nodes: []*tailcfg.DERPNode{{
			Name: "t1", RegionID: 1, HostName: emulatorHost, IPv4: emulatorHost, IPv6: "none",
			STUNPort: -1, DERPPort: dln.Addr().(*net.TCPAddr).Port, InsecureForTests: true,
		}}},
	}}

	// Coordination server
	control := &testcontrol.Server{DERPMap: derpMap, DNSConfig: &tailcfg.DNSConfig{Proxied: true}, MagicDNSDomain: "tail-scale.ts.net", Logf: logger.Discard}
	cln, err := net.Listen("tcp", "0.0.0.0:0")
	if err != nil {
		t.Fatal(err)
	}
	control.HTTPTestServer = httptest.NewUnstartedServer(control)
	control.HTTPTestServer.Listener.Close()
	control.HTTPTestServer.Listener = cln
	control.HTTPTestServer.Start()
	defer control.HTTPTestServer.Close()
	url := "http://" + net.JoinHostPort(emulatorHost, strconv.Itoa(cln.Addr().(*net.TCPAddr).Port))

	// The PC
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	pc := &tsnet.Server{Dir: filepath.Join(t.TempDir(), "pc"), ControlURL: url, Hostname: "lenovo-500w", Store: new(mem.Store), Ephemeral: true, Logf: logger.Discard}
	defer pc.Close()
	st, err := pc.Up(ctx)
	if err != nil {
		t.Fatal(err)
	}
	pcIP := st.TailscaleIPs[0].String()
	// Sunshine, pairing included (PIN of the test QR code); its folder is
	// shared with Eclipse for PC run by the test (file sharing).
	sun := newFakeSunshine(t, os.Getenv("ECLIPSE_FAKE_SUNSHINE"), "1234")
	hl, err := pc.Listen("tcp", ":47989")
	if err != nil {
		t.Fatal(err)
	}
	go http.Serve(hl, sun.httpHandler())
	sl, err := pc.Listen("tcp", ":47984")
	if err != nil {
		t.Fatal(err)
	}
	go http.Serve(tls.NewListener(sl, sun.tlsConfig()), sun.httpsHandler())
	// Eclipse's file sharing: Eclipse for PC listens on this machine.
	fl, err := pc.Listen("tcp", ":48150")
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			c, err := fl.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				d, err := net.DialTimeout("tcp", "127.0.0.1:48150", 5*time.Second)
				if err != nil {
					t.Logf("PC: file sharing not reachable: %v", err)
					return
				}
				defer d.Close()
				go io.Copy(d, c)
				io.Copy(c, d)
			}(c)
		}
	}()

	if err := os.WriteFile(out+".tmp", []byte(url+" "+pcIP+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	os.Rename(out+".tmp", out)
	t.Logf("fake PC ready: control %s, PC %s", url, pcIP)
	deadline := time.Now().Add(55 * time.Minute)
	for time.Now().Before(deadline) {
		if _, err := os.Stat(out + ".stop"); err == nil {
			return
		}
		time.Sleep(time.Second)
	}
}
