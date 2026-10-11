package eclipsenet

// Port forwarding from this phone (127.0.0.1) to the PC over the private
// network: Moonlight talks to 127.0.0.1 exactly as it would to the PC.
// Only the standard library here, so it can be tested on its own.

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

type dialFunc func(ctx context.Context, network, addr string) (net.Conn, error)

type forwarder struct {
	target  string
	dial    dialFunc
	ctx     context.Context
	cancel  context.CancelFunc
	mu      sync.Mutex
	closers map[io.Closer]struct{}
	wg      sync.WaitGroup
}

// Socket buffers of the relay's local UDP sockets: video comes in bursts
// (a whole frame at once), a large buffer avoids losing packets.
const udpBuffer = 4 << 20

const udpIdle = 2 * time.Minute

func parsePorts(s string) ([]int, error) {
	var out []int
	for _, f := range strings.Split(s, ",") {
		f = strings.TrimSpace(f)
		if f == "" {
			continue
		}
		p, err := strconv.Atoi(f)
		if err != nil || p <= 0 || p > 65535 {
			return nil, fmt.Errorf("port invalide : %q", f)
		}
		out = append(out, p)
	}
	return out, nil
}

// startForward listens on local (a 127.x.x.x address) for each port and
// relays to target.
func startForward(dial dialFunc, local net.IP, target string, tcpPorts, udpPorts []int) (*forwarder, error) {
	ctx, cancel := context.WithCancel(context.Background())
	f := &forwarder{target: target, dial: dial, ctx: ctx, cancel: cancel, closers: map[io.Closer]struct{}{}}
	var errs []error
	for _, p := range tcpPorts {
		ln, err := net.Listen("tcp4", net.JoinHostPort(local.String(), strconv.Itoa(p)))
		if err != nil {
			errs = append(errs, err)
			continue
		}
		f.add(ln)
		f.wg.Add(1)
		go f.serveTCP(ln, p)
	}
	for _, p := range udpPorts {
		pc, err := net.ListenUDP("udp4", &net.UDPAddr{IP: local, Port: p})
		if err != nil {
			errs = append(errs, err)
			continue
		}
		_ = pc.SetReadBuffer(udpBuffer)
		_ = pc.SetWriteBuffer(udpBuffer)
		f.add(pc)
		f.wg.Add(1)
		go f.serveUDP(pc, p)
	}
	if len(errs) > 0 {
		f.Close()
		return nil, errors.Join(errs...)
	}
	return f, nil
}

// add registers something to close with the relay (false if it is
// already closed: then it is closed at once).
func (f *forwarder) add(c io.Closer) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.closers == nil {
		_ = c.Close()
		return false
	}
	f.closers[c] = struct{}{}
	return true
}

func (f *forwarder) remove(c io.Closer) {
	f.mu.Lock()
	if f.closers != nil {
		delete(f.closers, c)
	}
	f.mu.Unlock()
}

func (f *forwarder) Close() {
	f.cancel()
	f.mu.Lock()
	cs := f.closers
	f.closers = nil
	f.mu.Unlock()
	for c := range cs {
		_ = c.Close()
	}
	f.wg.Wait()
}

func (f *forwarder) remote(p int) string {
	return net.JoinHostPort(f.target, strconv.Itoa(p))
}

func (f *forwarder) serveTCP(ln net.Listener, port int) {
	defer f.wg.Done()
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go f.pipeTCP(c, port)
	}
}

func (f *forwarder) pipeTCP(local net.Conn, port int) {
	ctx, cancel := context.WithTimeout(f.ctx, 10*time.Second)
	remote, err := f.dial(ctx, "tcp", f.remote(port))
	cancel()
	if err != nil {
		_ = local.Close()
		return
	}
	if !f.add(remote) || !f.add(local) {
		_ = local.Close()
		_ = remote.Close()
		return
	}
	defer f.remove(remote)
	defer f.remove(local)
	if tc, ok := local.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}
	done := make(chan struct{}, 2)
	cp := func(dst, src net.Conn) {
		_, _ = io.Copy(dst, src)
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		} else {
			_ = dst.Close()
		}
		done <- struct{}{}
	}
	go cp(remote, local)
	go cp(local, remote)
	<-done
	<-done
	_ = local.Close()
	_ = remote.Close()
}

type udpSession struct {
	remote net.Conn
	mu     sync.Mutex
	last   time.Time
}

func (s *udpSession) touch() {
	s.mu.Lock()
	s.last = time.Now()
	s.mu.Unlock()
}

func (s *udpSession) idle() time.Duration {
	s.mu.Lock()
	defer s.mu.Unlock()
	return time.Since(s.last)
}

// serveUDP relays datagrams: each local sender gets its own connection to
// the PC, and the PC's answers go back to that sender.
func (f *forwarder) serveUDP(pc *net.UDPConn, port int) {
	defer f.wg.Done()
	var mu sync.Mutex
	sessions := map[string]*udpSession{}
	go func() {
		t := time.NewTicker(30 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-f.ctx.Done():
				return
			case <-t.C:
			}
			mu.Lock()
			for k, s := range sessions {
				if s.idle() > udpIdle {
					_ = s.remote.Close()
					delete(sessions, k)
				}
			}
			mu.Unlock()
		}
	}()
	defer func() {
		mu.Lock()
		for _, s := range sessions {
			_ = s.remote.Close()
		}
		mu.Unlock()
	}()
	buf := make([]byte, 65535)
	for {
		n, from, err := pc.ReadFromUDP(buf)
		if err != nil {
			return
		}
		key := from.String()
		mu.Lock()
		s := sessions[key]
		mu.Unlock()
		if s == nil {
			ctx, cancel := context.WithTimeout(f.ctx, 10*time.Second)
			rc, err := f.dial(ctx, "udp", f.remote(port))
			cancel()
			if err != nil {
				continue
			}
			if rb, ok := rc.(interface{ SetReadBuffer(int) error }); ok {
				_ = rb.SetReadBuffer(udpBuffer)
			}
			s = &udpSession{remote: rc, last: time.Now()}
			mu.Lock()
			sessions[key] = s
			mu.Unlock()
			go func(s *udpSession, to *net.UDPAddr) {
				b := make([]byte, 65535)
				for {
					n, err := s.remote.Read(b)
					if err != nil {
						return
					}
					s.touch()
					_, _ = pc.WriteToUDP(b[:n], to)
				}
			}(s, from)
		}
		s.touch()
		_, _ = s.remote.Write(buf[:n])
	}
}
