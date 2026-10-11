package eclipsenet

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"
)

// What Sunshine 2026.914 answers to /serverinfo over HTTP (shortened).
const sunshineServerInfo = `<?xml version="1.0" encoding="utf-8"?>
<root status_code="200"><hostname>Lenovo 500w Gen 5</hostname><appversion>7.1.431.-1</appversion><GfeVersion>3.23.0.74</GfeVersion><uniqueid>DE7509DC-6D80-5E2F-7700-BF75D0205FCB</uniqueid><HttpsPort>47984</HttpsPort><ExternalPort>47989</ExternalPort><MaxLumaPixelsHEVC>1869449984</MaxLumaPixelsHEVC><mac>00:00:00:00:00:00</mac><LocalIP>192.168.1.20</LocalIP><ServerCodecModeSupport>3</ServerCodecModeSupport><PairStatus>0</PairStatus><currentgame>0</currentgame><state>SUNSHINE_SERVER_FREE</state></root>`

func TestAskServerInfo(t *testing.T) {
	sun := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/serverinfo" || r.URL.Query().Get("uniqueid") == "" {
			http.NotFound(w, r)
			return
		}
		w.Write([]byte(sunshineServerInfo))
	}))
	defer sun.Close()
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("<html>a router</html>"))
	}))
	defer other.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	hc := &http.Client{}
	host, port := split(t, sun.URL)
	si, err := askServerInfo(ctx, hc, host, port)
	if err != nil {
		t.Fatal(err)
	}
	if si.UniqueID != "DE7509DC-6D80-5E2F-7700-BF75D0205FCB" || si.Hostname != "Lenovo 500w Gen 5" {
		t.Fatalf("%+v", si)
	}
	host, port = split(t, other.URL)
	if _, err := askServerInfo(ctx, hc, host, port); err == nil {
		t.Fatal("a web page that is not Sunshine was taken for a PC")
	}
	ln, _ := net.Listen("tcp4", "127.0.0.1:0")
	p := ln.Addr().(*net.TCPAddr).Port
	ln.Close()
	if _, err := askServerInfo(ctx, hc, "127.0.0.1", p); err == nil {
		t.Fatal("nothing listening, yet found")
	}
}

func split(t *testing.T, u string) (string, int) {
	h, p, err := net.SplitHostPort(u[len("http://"):])
	if err != nil {
		t.Fatal(err)
	}
	n, _ := strconv.Atoi(p)
	return h, n
}

func TestDiscoverNotStarted(t *testing.T) {
	if out, err := Discover("47989", 100); err == nil || out != "[]" {
		t.Fatalf("%q %v", out, err)
	}
	if _, err := Discover("x", 100); err == nil {
		t.Fatal("bad ports accepted")
	}
}
