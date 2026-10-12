package main

import (
	"strings"
	"testing"
)

func TestVersionLess(t *testing.T) {
	cases := []struct {
		a, b string
		less bool
	}{
		{"2.3.1", "2.3.2", true}, {"2.3.2", "2.3.1", false}, {"2.3.1", "2.10.0", true},
		{"2.9.9", "3.0.0", true}, {"2.3.1", "2.3.1", false}, {"2.3", "2.3.1", true},
	}
	for _, c := range cases {
		if versionLess(c.a, c.b) != c.less {
			t.Errorf("versionLess(%s, %s) != %v", c.a, c.b, c.less)
		}
	}
}

func TestParseReleases(t *testing.T) {
	data := `[
	 {"tag_name":"v2.3.0","body":"phone","published_at":"2026-10-11T20:47:00Z","assets":[{"name":"Eclipse.apk","size":44149099,"browser_download_url":"https://github.com/x/Eclipse.apk"}]},
	 {"tag_name":"pc-v2.3.1","body":"**Eclipse 2.3.1**","published_at":"2026-10-11T21:30:00Z","assets":[
	   {"name":"eclipse-stream_2.3.1_amd64.deb","size":2716090,"browser_download_url":"https://github.com/x/eclipse-stream_2.3.1_amd64.deb","digest":"sha256:` + strings.Repeat("ab", 32) + `"},
	   {"name":"Eclipse-Setup-2.3.1.exe","size":8347648,"browser_download_url":"https://github.com/x/Eclipse-Setup-2.3.1.exe"}]},
	 {"tag_name":"pc-v2.10.0","body":"later","published_at":"2027-01-01T00:00:00Z","assets":[
	   {"name":"eclipse-stream_2.10.0_amd64.deb","size":1,"browser_download_url":"https://github.com/x/eclipse-stream_2.10.0_amd64.deb"}]},
	 {"tag_name":"pc-v2.4.0","draft":true,"assets":[{"name":"eclipse-stream_2.4.0_amd64.deb","browser_download_url":"https://github.com/x/a.deb"}]},
	 {"tag_name":"pc-v2.5.0","assets":[{"name":"eclipse-stream_2.5.0_amd64.deb","browser_download_url":"http://insecure/a.deb"}]},
	 {"tag_name":"pc-vX","assets":[]}
	]`
	vs, err := parseReleases([]byte(data))
	if err != nil {
		t.Fatal(err)
	}
	if len(vs) != 2 || vs[0].Version != "2.10.0" || vs[1].Version != "2.3.1" {
		t.Fatalf("%+v", vs)
	}
	if vs[1].sha256 != strings.Repeat("ab", 32) || vs[1].Size != 2716090 || vs[1].Notes != "**Eclipse 2.3.1**" {
		t.Fatalf("%+v", vs[1])
	}
	if vs[0].sha256 != "" {
		t.Fatal("no digest given")
	}
}
