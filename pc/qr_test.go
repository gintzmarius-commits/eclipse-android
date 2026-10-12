package main

import (
	"fmt"
	"math/rand"
	"net/url"
	"os"
	"strings"
	"testing"
)

func TestParseDeviceName(t *testing.T) {
	cases := []struct{ raw, kind, token, name string }{
		{"Eclipse.k3f9xq2a.Galaxy S23", "qr", "k3f9xq2a", "Galaxy S23"},
		{"Eclipse.k3f9xq2a.Nord 2.5 (Pro)", "qr", "k3f9xq2a", "Nord 2.5 (Pro)"},
		{"Eclipse..Pixel 7", "eclipse", "", "Pixel 7"},
		{"Eclipse..", "eclipse", "", ""},
		{"Eclipse.abc", "other", "", "Eclipse.abc"},
		{"roth", "other", "", ""},
		{"", "other", "", ""},
		{"Mon PC", "other", "", "Mon PC"},
		{"Eclipse.k3f9xq2a.Téléphone de Marié", "qr", "k3f9xq2a", "Téléphone de Marié"},
		{"Eclipse.k3f9xq2a.  a\tb\x00c  ", "qr", "k3f9xq2a", "a b c"},
	}
	for _, c := range cases {
		k, tok, n := parseDeviceName(c.raw)
		if k != c.kind || tok != c.token || n != c.name {
			t.Errorf("parseDeviceName(%q) = %q %q %q, want %q %q %q", c.raw, k, tok, n, c.kind, c.token, c.name)
		}
	}
}

func TestCleanDeviceNameLimit(t *testing.T) {
	long := strings.Repeat("é", 300)
	n := cleanDeviceName(long)
	if len(n) > 100 || len(n) == 0 || !strings.HasPrefix(long, n) {
		t.Fatalf("bad length %d", len(n))
	}
	if cleanDeviceName("   ") != "" {
		t.Fatal("blank name should stay blank")
	}
}

func TestRandomToken(t *testing.T) {
	seen := map[string]bool{}
	for i := 0; i < 2000; i++ {
		tok := randomToken()
		if len(tok) != qrTokenLen || strings.Trim(tok, "abcdefghijkmnpqrstuvwxyz23456789") != "" {
			t.Fatalf("bad token %q", tok)
		}
		seen[tok] = true
	}
	if len(seen) < 1990 {
		t.Fatal("tokens repeat")
	}
}

// TestQRDump writes QR codes for many realistic payloads; they are decoded
// by an independent decoder (ZXing port) in the test scripts.
func TestQRDump(t *testing.T) {
	out := os.Getenv("QR_DUMP")
	if out == "" {
		t.Skip("QR_DUMP not set")
	}
	f, err := os.Create(out)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	r := rand.New(rand.NewSource(1))
	names := []string{"DESKTOP-4F2K9QL", "PC-de-Marius", "Lenovo 500w Gen 5", "Ordinateur de Zoé", "vm", strings.Repeat("x", 63)}
	for i := 0; i < 400; i++ {
		var ips []string
		for j := 0; j <= r.Intn(4); j++ {
			ips = append(ips, fmt.Sprintf("%d.%d.%d.%d", []int{192, 10, 172}[r.Intn(3)], r.Intn(256), r.Intn(256), 1+r.Intn(254)))
		}
		v := url.Values{}
		v.Set("v", "1")
		v.Set("n", names[r.Intn(len(names))])
		v.Set("a", strings.Join(ips, ","))
		v.Set("port", "47989")
		if r.Intn(4) > 0 {
			v.Set("id", "DE7509DC-6D80-5E2F-7700-BF75D0205FCB")
		}
		v.Set("t", randomToken())
		v.Set("p", fmt.Sprintf("%04d", r.Intn(10000)))
		text := "eclipse://pair?" + v.Encode()
		if i == 0 {
			text = phoneAppURL
		}
		svg, err := qrSVG(text)
		if err != nil {
			t.Fatal(err)
		}
		fmt.Fprintf(f, "%s\t%s\n", text, svg)
	}
}

// Eclipse 2.3 phones add the key of their Tailscale sign-in page to the token.
func TestSplitToken(t *testing.T) {
	cases := []struct{ in, token, key string }{
		{"k3f9xq2a", "k3f9xq2a", ""},
		{"k3f9xq2a~1a2b3c4d5e6f", "k3f9xq2a", "1a2b3c4d5e6f"},
		{"k3f9xq2a~", "k3f9xq2a", ""},
		{"k3f9xq2a~ab/../x", "k3f9xq2a", ""},
		{"k3f9xq2a~abc", "k3f9xq2a", ""}, // too short to be a key
		{"k3f9xq2a~" + strings.Repeat("a", 65), "k3f9xq2a", ""},
	}
	for _, c := range cases {
		tok, key := splitToken(c.in)
		if tok != c.token || key != c.key {
			t.Errorf("splitToken(%q) = %q %q", c.in, tok, key)
		}
	}
	// The whole name sent by the phone
	k, tok, n := parseDeviceName("Eclipse.k3f9xq2a~f00d1234.Pixel 7")
	tok, key := splitToken(tok)
	if k != "qr" || tok != "k3f9xq2a" || key != "f00d1234" || n != "Pixel 7" {
		t.Fatalf("%q %q %q %q", k, tok, key, n)
	}
	if displayDeviceName("Eclipse.k3f9xq2a~f00d1234.Pixel 7") != "Pixel 7" {
		t.Fatal("the key must not appear in the device name")
	}
	if phoneLoginURL("f00d1234") != "https://login.tailscale.com/a/f00d1234" {
		t.Fatal(phoneLoginURL("f00d1234"))
	}
}
