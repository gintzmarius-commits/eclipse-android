package main

// One Eclipse at a time (Eclipse 2.4).
//
// Eclipse stays on in the background when its window is closed (file
// sharing) and starts with the computer. Opening Eclipse again only asks
// the one already running to show its window. A newer Eclipse (just
// installed) asks an older one to leave and takes its place.

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sync/atomic"
	"time"
)

// windowOpen: the window is shown right now (else Eclipse runs in the
// background); an update restarts Eclipse the same way.
var windowOpen atomic.Bool

// startArgs are the arguments to start Eclipse again like it runs now.
func startArgs() []string {
	if windowOpen.Load() {
		return nil
	}
	return []string{"--background"}
}

type instanceInfo struct {
	Port    int    `json:"port"`
	Token   string `json:"token"`
	PID     int    `json:"pid"`
	Version string `json:"version"`
}

func instancePath() string { return filepath.Join(dataDir(), "instance.json") }

func writeInstance(port int, token string) {
	data, _ := json.Marshal(instanceInfo{Port: port, Token: token, PID: os.Getpid(), Version: version})
	enc, err := protect(data)
	if err != nil {
		return
	}
	_ = os.MkdirAll(dataDir(), 0o755)
	tmp := instancePath() + ".tmp"
	if os.WriteFile(tmp, enc, 0o600) == nil {
		_ = os.Rename(tmp, instancePath())
	}
}

func readInstance() (instanceInfo, bool) {
	var in instanceInfo
	data, err := os.ReadFile(instancePath())
	if err != nil {
		return in, false
	}
	plain, err := unprotect(data)
	if err != nil || json.Unmarshal(plain, &in) != nil || in.Port == 0 {
		return in, false
	}
	return in, true
}

func clearInstance() {
	if in, ok := readInstance(); ok && in.PID == os.Getpid() {
		_ = os.Remove(instancePath())
	}
}

func instanceCall(in instanceInfo, path string) (map[string]any, error) {
	req, _ := http.NewRequest("POST", fmt.Sprintf("http://127.0.0.1:%d%s", in.Port, path), bytes.NewReader(nil))
	req.Header.Set("X-Token", in.Token)
	c := &http.Client{Timeout: 4 * time.Second}
	resp, err := c.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("status %d", resp.StatusCode)
	}
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return out, nil
}

// handOff returns true when another Eclipse of this version runs: it was
// asked to show its window (unless background), and this one can leave.
// An Eclipse of another version is asked to leave first.
func handOff(background bool) bool {
	in, ok := readInstance()
	if !ok || in.PID == os.Getpid() {
		return false
	}
	if in.Version == version {
		path := "/api/show"
		if background {
			path = "/api/ping"
		}
		if _, err := instanceCall(in, path); err == nil {
			log.Printf("instance: Eclipse already running (pid %d), window shown there", in.PID)
			return true
		}
		return false // not answering: it is gone
	}
	log.Printf("instance: Eclipse %s running (pid %d): asking it to leave", in.Version, in.PID)
	if _, err := instanceCall(in, "/api/quit"); err == nil {
		for i := 0; i < 40; i++ {
			c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", in.Port), 300*time.Millisecond)
			if err != nil {
				break
			}
			c.Close()
			time.Sleep(250 * time.Millisecond)
		}
		time.Sleep(500 * time.Millisecond) // its file sharing port closes too
	}
	return false
}

func (a *Core) instanceRoutes(api *http.ServeMux) {
	api.HandleFunc("POST /api/show", func(w http.ResponseWriter, r *http.Request) {
		select {
		case a.show <- struct{}{}:
		default:
		}
		writeJSON(w, map[string]string{"version": version})
	})
	api.HandleFunc("POST /api/ping", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]string{"version": version})
	})
	api.HandleFunc("POST /api/quit", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]string{"version": version})
		go func() {
			time.Sleep(200 * time.Millisecond)
			log.Printf("instance: a newer Eclipse takes over")
			a.leave()
		}()
	})
}

// leave closes the window if any and ends this Eclipse.
func (a *Core) leave() {
	clearInstance()
	closeWindow()
	os.Exit(0)
}

// keepRunning: stay on in the background after the window closed?
func keepRunning() bool {
	if os.Getenv("ECLIPSE_NO_BACKGROUND") == "1" {
		return false
	}
	return !loadSettings().NoBackground
}

// waitShow waits (in the background) until someone opens Eclipse again.
func (a *Core) waitShow() {
	<-a.show
}

// updateTimer: Eclipse may stay open (or in the background) for days: new
// versions are looked for every 30 minutes. Windows installs them silently;
// Ubuntu only while the window is shown (it asks for the password).
func (a *Core) updateTimer() {
	for {
		time.Sleep(30 * time.Minute)
		a.autoUpdate()
	}
}
