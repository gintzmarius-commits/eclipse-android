package eclipsenet

import (
	"bytes"
	"context"
	"io"
	"net"
	"strconv"
	"testing"
	"time"
)

func freePort(t *testing.T, network string) int {
	if network == "tcp" {
		l, _ := net.Listen("tcp4", "127.0.0.1:0")
		defer l.Close()
		return l.Addr().(*net.TCPAddr).Port
	}
	c, _ := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).Port
}

// The "PC" lives on 127.0.0.2 and the relay listens on 127.0.0.1 with the
// same port numbers, like on the phone.
func TestRelay(t *testing.T) {
	tcpPort, udpPort := freePort(t, "tcp"), freePort(t, "udp")
	// PC side: TCP echo (uppercased) and UDP echo with prefix
	ln, err := net.Listen("tcp4", "127.0.0.2:"+strconv.Itoa(tcpPort))
	if err != nil {
		t.Skip("127.0.0.2 unavailable:", err)
	}
	defer ln.Close()
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
	upc, _ := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 2), Port: udpPort})
	defer upc.Close()
	go func() {
		b := make([]byte, 65535)
		for {
			n, a, err := upc.ReadFromUDP(b)
			if err != nil {
				return
			}
			upc.WriteToUDP(append([]byte("pc:"), b[:n]...), a)
			if string(b[:n]) == "ping" { // the PC also sends on its own (like video)
				for i := 0; i < 50; i++ {
					upc.WriteToUDP(bytes.Repeat([]byte{byte(i)}, 1400), a)
				}
			}
		}
	}()
	var d net.Dialer
	f, err := startForward(func(ctx context.Context, n, a string) (net.Conn, error) { return d.DialContext(ctx, n, a) },
		net.IPv4(127, 0, 0, 1), "127.0.0.2", []int{tcpPort}, []int{udpPort})
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()

	// TCP, with half-close like HTTP
	for i := 0; i < 20; i++ {
		c, err := net.Dial("tcp4", "127.0.0.1:"+strconv.Itoa(tcpPort))
		if err != nil {
			t.Fatal(err)
		}
		c.Write(bytes.Repeat([]byte("abc"), 100000))
		c.(*net.TCPConn).CloseWrite()
		got, _ := io.ReadAll(c)
		c.Close()
		if !bytes.Equal(got, bytes.Repeat([]byte("ABC"), 100000)) {
			t.Fatalf("tcp: got %d bytes", len(got))
		}
	}
	// finished connections are forgotten (no leak over a long session)
	time.Sleep(200 * time.Millisecond)
	f.mu.Lock()
	open := len(f.closers)
	f.mu.Unlock()
	if open != 2 { // the two listeners
		t.Fatalf("%d things still open after the connections ended", open)
	}

	// UDP: two local senders get their own answers
	for _, name := range []string{"a", "b"} {
		c, _ := net.DialUDP("udp4", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: udpPort})
		c.SetReadDeadline(time.Now().Add(3 * time.Second))
		c.Write([]byte(name))
		b := make([]byte, 100)
		n, err := c.Read(b)
		if err != nil || string(b[:n]) != "pc:"+name {
			t.Fatalf("udp %s: %q %v", name, b[:n], err)
		}
		c.Write([]byte("ping"))
		n, _ = c.Read(b)
		got := 0
		big := make([]byte, 2000)
		for {
			n, err = c.Read(big)
			if err != nil {
				break
			}
			if n == 1400 {
				got++
			}
		}
		if got < 45 {
			t.Fatalf("udp burst: %d/50 big datagrams", got)
		}
		c.Close()
	}
}

func TestPortsInUse(t *testing.T) {
	ln, _ := net.Listen("tcp4", "127.0.0.1:0")
	defer ln.Close()
	p := ln.Addr().(*net.TCPAddr).Port
	if _, err := startForward(nil, net.IPv4(127, 0, 0, 1), "127.0.0.2", []int{p}, nil); err == nil {
		t.Fatal("expected an error for a port in use")
	}
}

func TestParsePorts(t *testing.T) {
	p, err := parsePorts(" 47984,47989 ,48010,")
	if err != nil || len(p) != 3 || p[2] != 48010 {
		t.Fatal(p, err)
	}
	if _, err := parsePorts("1,x"); err == nil {
		t.Fatal("expected error")
	}
}

// Two PCs relayed at once, each on its own local address with the same
// ports (127.0.0.1 and 127.0.0.3 -> "PCs" on 127.0.0.2 and 127.0.0.4).
func TestTwoRelays(t *testing.T) {
	port := freePort(t, "tcp")
	var d net.Dialer
	dial := func(ctx context.Context, n, a string) (net.Conn, error) { return d.DialContext(ctx, n, a) }
	for i, pc := range []string{"127.0.0.2", "127.0.0.4"} {
		ln, err := net.Listen("tcp4", net.JoinHostPort(pc, strconv.Itoa(port)))
		if err != nil {
			t.Skip("loopback address unavailable:", err)
		}
		defer ln.Close()
		go func(ln net.Listener, tag string) {
			for {
				c, err := ln.Accept()
				if err != nil {
					return
				}
				c.Write([]byte(tag))
				c.Close()
			}
		}(ln, "pc"+strconv.Itoa(i+1))
	}
	f1, err := startForward(dial, net.IPv4(127, 0, 0, 1), "127.0.0.2", []int{port}, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer f1.Close()
	f2, err := startForward(dial, net.IPv4(127, 0, 0, 3), "127.0.0.4", []int{port}, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer f2.Close()
	for local, want := range map[string]string{"127.0.0.1": "pc1", "127.0.0.3": "pc2"} {
		c, err := net.Dial("tcp4", net.JoinHostPort(local, strconv.Itoa(port)))
		if err != nil {
			t.Fatal(err)
		}
		got, _ := io.ReadAll(c)
		c.Close()
		if string(got) != want {
			t.Fatalf("%s: got %q, want %q", local, got, want)
		}
	}
}

func TestLoopbackFor(t *testing.T) {
	for in, ok := range map[string]bool{"127.0.0.1": true, "127.0.0.42": true, "127.255.0.1": true, "10.0.0.1": false, "::1": false, "x": false, "": false} {
		if _, err := loopback(in); (err == nil) != ok {
			t.Errorf("loopback(%q): %v", in, err)
		}
	}
}
