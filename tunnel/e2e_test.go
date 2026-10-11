package eclipsenet

// End-to-end test over a real (local) Tailscale network: a "PC" node runs
// echo servers on Sunshine's ports, the phone side joins with Start and
// relays 127.0.0.1 to the PC with Forward, exactly like in the app.

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"tailscale.com/ipn/store/mem"
	"tailscale.com/net/netns"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
	"tailscale.com/tstest/integration"
	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/logger"
)

func TestTailnetRelay(t *testing.T) {
	if os.Getenv("ECLIPSE_E2E") == "" {
		t.Skip("set ECLIPSE_E2E=1")
	}
	netns.SetEnabled(false)
	derpMap := integration.RunDERPAndSTUN(t, logger.Discard, "127.0.0.1")
	control := &testcontrol.Server{DERPMap: derpMap, DNSConfig: &tailcfg.DNSConfig{Proxied: true}, MagicDNSDomain: "tail-scale.ts.net", Logf: logger.Discard}
	control.HTTPTestServer = httptest.NewUnstartedServer(control)
	control.HTTPTestServer.Start()
	defer control.HTTPTestServer.Close()
	controlURL = control.HTTPTestServer.URL

	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()

	// The PC
	pc := &tsnet.Server{Dir: filepath.Join(t.TempDir(), "pc"), ControlURL: controlURL, Hostname: "pc", Store: new(mem.Store), Ephemeral: true, Logf: logger.Discard}
	defer pc.Close()
	st, err := pc.Up(ctx)
	if err != nil {
		t.Fatal(err)
	}
	pcIP := st.TailscaleIPs[0].String()
	// Sunshine's HTTP port answers like Sunshine; the "HTTPS" port echoes.
	hl, err := pc.Listen("tcp", ":47989")
	if err != nil {
		t.Fatal(err)
	}
	go http.Serve(hl, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(sunshineServerInfo))
	}))
	ln, err := pc.Listen("tcp", ":47984")
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				b, _ := io.ReadAll(c)
				c.Write(bytes.ToUpper(b))
				c.Close()
			}(c)
		}
	}()
	upc, err := pc.ListenPacket("udp", net.JoinHostPort(pcIP, "47998"))
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		b := make([]byte, 65535)
		for {
			n, a, err := upc.ReadFrom(b)
			if err != nil {
				return
			}
			upc.WriteTo(append([]byte("pc:"), b[:n]...), a)
			if string(b[:n]) == "ping" {
				for i := 0; i < 200; i++ {
					upc.WriteTo(bytes.Repeat([]byte{byte(i)}, 1024), a)
				}
			}
			if string(b[:n]) == "video" {
				// like a video stream at 20 Mbit/s: one frame (~42 KB,
				// 41 packets) every 16 ms, each frame sent at once.
				for f := 0; f < 120; f++ {
					for i := 0; i < 41; i++ {
						upc.WriteTo(bytes.Repeat([]byte{byte(f)}, 1024), a)
					}
					time.Sleep(16 * time.Millisecond)
				}
				// then a burst: a key frame of 400 KB at once
				for i := 0; i < 400; i++ {
					upc.WriteTo(bytes.Repeat([]byte{0xee}, 1024), a)
				}
			}
		}
	}()

	// The phone, in the conditions of an Android app: no home, cache or
	// temporary folder that Go knows of, working directory "/" (Eclipse
	// 2.2.0 stopped there: "no safe place found to store log state").
	phoneDir := filepath.Join(t.TempDir(), "phone")
	t.Setenv("HOME", "")
	t.Setenv("XDG_CACHE_HOME", "")
	t.Setenv("STATE_DIRECTORY", "")
	t.Setenv("TS_LOGS_DIR", "")
	t.Setenv("TMPDIR", "/nonexistent-eclipse-tmp")
	if wd, err := os.Getwd(); err == nil {
		os.Chdir("/")
		defer os.Chdir(wd)
	}
	if err := Start(phoneDir, "eclipse-test-phone"); err != nil {
		t.Fatal(err)
	}
	var s status
	for i := 0; i < 120; i++ {
		json.Unmarshal([]byte(Status()), &s)
		if s.State == "running" {
			break
		}
		time.Sleep(500 * time.Millisecond)
	}
	t.Logf("phone status: %+v", s)
	if s.State != "running" || s.IP == "" {
		t.Fatalf("phone not running: %+v", s)
	}
	if msg := Probe(pcIP, 47989, 20000); msg != "" {
		t.Fatalf("probe: %s", msg)
	}

	// The PC is found on the private network, with its Sunshine id.
	start := time.Now()
	out, err := Discover("47989", 8000)
	if err != nil {
		t.Fatal(err)
	}
	var pcs []sunshinePC
	json.Unmarshal([]byte(out), &pcs)
	t.Logf("discover (%v): %s", time.Since(start), out)
	if len(pcs) != 1 || pcs[0].IP != pcIP || pcs[0].ID != "DE7509DC-6D80-5E2F-7700-BF75D0205FCB" || pcs[0].Port != 47989 {
		t.Fatalf("discover: %s", out)
	}

	if err := Forward(pcIP, "47984,47989", "47998"); err != nil {
		t.Fatal(err)
	}
	if err := Forward(pcIP, "47984,47989", "47998"); err != nil { // same values: nothing to do
		t.Fatal(err)
	}
	// A second PC address on its own local address (here the same PC).
	if err := ForwardAt("127.0.0.2", pcIP, "47984", ""); err != nil {
		t.Fatal(err)
	}
	if err := ForwardAt("10.0.0.1", pcIP, "47984", ""); err == nil {
		t.Fatal("a relay on a network address was accepted")
	}
	for _, local := range []string{"127.0.0.1", "127.0.0.2"} {
		c, err := net.Dial("tcp4", local+":47984")
		if err != nil {
			t.Fatal(err)
		}
		c.Write([]byte("two"))
		c.(*net.TCPConn).CloseWrite()
		got, _ := io.ReadAll(c)
		c.Close()
		if string(got) != "TWO" {
			t.Fatalf("relay on %s: %q", local, got)
		}
	}
	StopForward("127.0.0.2")
	if c, err := net.DialTimeout("tcp4", "127.0.0.2:47984", time.Second); err == nil {
		c.Close()
		t.Fatal("relay still there after StopForward")
	}

	start = time.Now()
	for i := 0; i < 5; i++ {
		c, err := net.Dial("tcp4", "127.0.0.1:47984")
		if err != nil {
			t.Fatal(err)
		}
		c.Write(bytes.Repeat([]byte("abc"), 200000))
		c.(*net.TCPConn).CloseWrite()
		got, _ := io.ReadAll(c)
		c.Close()
		if !bytes.Equal(got, bytes.Repeat([]byte("ABC"), 200000)) {
			t.Fatalf("tcp through the tailnet: got %d bytes", len(got))
		}
	}
	t.Logf("tcp: 5 x 600 KB round trips in %v", time.Since(start))

	c, _ := net.DialUDP("udp4", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 47998})
	defer c.Close()
	b := make([]byte, 2000)
	var n int
	for try := 0; try < 10; try++ { // first packets may race the path setup
		c.Write([]byte("hello"))
		c.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, err = c.Read(b)
		if err == nil {
			break
		}
	}
	if err != nil || string(b[:n]) != "pc:hello" {
		t.Fatalf("udp: %q %v", b[:n], err)
	}
	c.Write([]byte("ping"))
	got := 0
	for {
		c.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, err := c.Read(b)
		if err != nil {
			break
		}
		if n == 1024 {
			got++
		}
	}
	t.Logf("udp burst: %d/200 datagrams of 1024 bytes", got)
	if got < 150 {
		t.Fatalf("udp burst: only %d/200", got)
	}

	// Video-like stream: 120 frames of 41 packets, then a 400-packet key frame.
	c.Write([]byte("video"))
	frames, key := 0, 0
	for {
		c.SetReadDeadline(time.Now().Add(3 * time.Second))
		n, err := c.Read(b)
		if err != nil {
			break
		}
		if n == 1024 && b[0] == 0xee {
			key++
		} else if n == 1024 {
			frames++
		}
	}
	t.Logf("video: %d/4920 frame packets, key frame %d/400", frames, key)
	if frames < 4920*9/10 {
		t.Errorf("video: too many packets lost (%d/4920)", frames)
	}

	if err := Logout(); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 40; i++ {
		json.Unmarshal([]byte(Status()), &s)
		if s.State == "needslogin" {
			break
		}
		time.Sleep(250 * time.Millisecond)
	}
	if s.State != "needslogin" {
		t.Fatalf("after logout: %+v", s)
	}
	_ = strconv.Itoa
}
