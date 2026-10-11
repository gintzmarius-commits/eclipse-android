package eclipsenet

// Finding the PCs of the private network: every device of the user's
// tailnet that is online is asked, like Moonlight does on a Wi-Fi network,
// whether it runs Sunshine (Eclipse on a PC). Nothing to set up.

import (
	"context"
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// sunshinePC is a PC found on the private network.
type sunshinePC struct {
	IP   string `json:"ip"`   // its Tailscale address
	Port int    `json:"port"` // Sunshine's HTTP port
	ID   string `json:"id"`   // Sunshine's unique id (the PC's uuid in Moonlight)
	Name string `json:"name"` // Sunshine's host name
	Peer string `json:"peer"` // its name in Tailscale
}

type serverInfo struct {
	XMLName  xml.Name `xml:"root"`
	Status   string   `xml:"status_code,attr"`
	Hostname string   `xml:"hostname"`
	UniqueID string   `xml:"uniqueid"`
}

// Discover lists, as JSON, the PCs of the private network that run
// Sunshine on one of the given HTTP ports ("47989" or "47989,48989").
func Discover(ports string, timeoutMs int) (out string, err error) {
	defer catch(&err)
	ps, err := parsePorts(ports)
	if err != nil || len(ps) == 0 {
		return "[]", fmt.Errorf("ports invalides : %q", ports)
	}
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return "[]", errors.New("not started")
	}
	lc, err := s.LocalClient()
	if err != nil {
		return "[]", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil {
		return "[]", err
	}
	type target struct {
		ip   netip.Addr
		peer string
	}
	var targets []target
	for _, p := range st.Peer {
		if p == nil || !p.Online {
			continue
		}
		switch strings.ToLower(p.OS) {
		case "android", "ios", "tvos":
			continue // phones and TVs do not run Sunshine
		}
		for _, ip := range p.TailscaleIPs {
			if ip.Is4() {
				targets = append(targets, target{ip, p.HostName})
				break
			}
		}
	}
	hc := &http.Client{Transport: &http.Transport{
		DialContext:       s.Dial,
		DisableKeepAlives: true,
	}}
	var wg sync.WaitGroup
	var fmu sync.Mutex
	found := []sunshinePC{}
	for _, tg := range targets {
		for _, port := range ps {
			wg.Add(1)
			go func(tg target, port int) {
				defer wg.Done()
				si, err := askServerInfo(ctx, hc, tg.ip.String(), port)
				if err != nil {
					return
				}
				fmu.Lock()
				found = append(found, sunshinePC{IP: tg.ip.String(), Port: port, ID: si.UniqueID, Name: si.Hostname, Peer: tg.peer})
				fmu.Unlock()
			}(tg, port)
		}
	}
	wg.Wait()
	sort.Slice(found, func(i, j int) bool { return found[i].Name+found[i].IP < found[j].Name+found[j].IP })
	b, _ := json.Marshal(found)
	return string(b), nil
}

// askServerInfo asks Sunshine who it is (the request Moonlight makes to
// find PCs; answered without pairing).
func askServerInfo(ctx context.Context, hc *http.Client, ip string, port int) (*serverInfo, error) {
	u := "http://" + net.JoinHostPort(ip, strconv.Itoa(port)) + "/serverinfo?uniqueid=0123456789ABCDEF&uuid=" + strconv.FormatInt(time.Now().UnixNano(), 16)
	req, err := http.NewRequestWithContext(ctx, "GET", u, nil)
	if err != nil {
		return nil, err
	}
	resp, err := hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if err != nil {
		return nil, err
	}
	var si serverInfo
	if err := xml.Unmarshal(body, &si); err != nil {
		return nil, err
	}
	if si.UniqueID == "" || (si.Status != "" && si.Status != "200") {
		return nil, fmt.Errorf("not Sunshine (%s)", si.Status)
	}
	return &si, nil
}
