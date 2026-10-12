package main

// Paired devices: Sunshine 2026.914 (the current release) adds a new record
// each time the same device pairs again, and then refuses that device
// because it has two records. Eclipse removes the older copies, so pairing
// again (after removing the PC in the phone app, for example) always works.

import (
	"encoding/json"
	"errors"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

var dedupeMu sync.Mutex

// Finding Sunshine's folder can take seconds on Windows (PowerShell): it
// is remembered for a while.
var confDir struct {
	sync.Mutex
	dir string
	at  time.Time
}

func cachedConfigDir() string {
	confDir.Lock()
	defer confDir.Unlock()
	if confDir.dir != "" && time.Since(confDir.at) < 2*time.Minute {
		return confDir.dir
	}
	if d := sunshineConfigDir(); d != "" {
		confDir.dir, confDir.at = d, time.Now()
	}
	return confDir.dir
}

// stateFile is where Sunshine keeps its paired devices.
func stateFile(dir string) string {
	name := "sunshine_state.json"
	if v := readSunshineConf(dir)["file_state"]; v != "" {
		name = v
	}
	if !filepath.IsAbs(name) {
		name = filepath.Join(dir, name)
	}
	return name
}

type storedDevice struct {
	Cert string `json:"cert"`
	UUID string `json:"uuid"`
}

func readStoredDevices() ([]storedDevice, error) {
	dir := cachedConfigDir()
	if dir == "" {
		return nil, errors.New("dossier de Sunshine introuvable")
	}
	data, err := os.ReadFile(stateFile(dir))
	if err != nil {
		return nil, err
	}
	var st struct {
		Root struct {
			NamedDevices []storedDevice `json:"named_devices"`
		} `json:"root"`
	}
	if err := json.Unmarshal(data, &st); err != nil {
		return nil, err
	}
	return st.Root.NamedDevices, nil
}

// olderCopies returns the records that repeat the certificate of a later
// record (same device paired more than once).
func olderCopies(devs []storedDevice) []string {
	norm := func(c string) string { return strings.Join(strings.Fields(c), "") }
	last := map[string]int{}
	for i, d := range devs {
		if c := norm(d.Cert); c != "" {
			last[c] = i
		}
	}
	var out []string
	for i, d := range devs {
		if c := norm(d.Cert); c != "" && d.UUID != "" && last[c] != i {
			out = append(out, d.UUID)
		}
	}
	return out
}

// dedupeClients removes older copies of devices paired several times.
// When newUUID is given, it first waits (briefly) for Sunshine to save it.
func (a *Core) dedupeClients(newUUID string) {
	if os.Getenv("ECLIPSE_SKIP_PLATFORM") == "1" {
		return
	}
	dedupeMu.Lock()
	defer dedupeMu.Unlock()
	var devs []storedDevice
	var err error
	for i := 0; i < 15; i++ {
		devs, err = readStoredDevices()
		if err != nil {
			return
		}
		if newUUID == "" || hasUUID(devs, newUUID) {
			break
		}
		time.Sleep(200 * time.Millisecond)
	}
	drop := olderCopies(devs)
	if len(drop) == 0 {
		return
	}
	live, err := a.sun.Devices() // only remove what Sunshine itself still lists
	if err != nil {
		return
	}
	for _, u := range drop {
		for _, d := range live {
			if strings.EqualFold(d.UUID, u) {
				if err := a.sun.RemoveDevice(d.UUID); err != nil {
					log.Printf("devices: duplicate record not removed: %v", err)
				} else {
					log.Printf("devices: removed an older record of %q (same device paired again)", d.Name)
				}
			}
		}
	}
}

func hasUUID(devs []storedDevice, u string) bool {
	for _, d := range devs {
		if strings.EqualFold(d.UUID, u) {
			return true
		}
	}
	return false
}

// pairAndTidy gives the PIN to Sunshine and reports whether the device is
// really connected (recent Sunshine answers once the device has finished
// connecting, older ones at once: the list of devices tells in both cases).
func (a *Core) pairAndTidy(id, pin, name string) (bool, error) {
	before := map[string]bool{}
	devs, listErr := a.sun.Devices()
	for _, d := range devs {
		before[strings.ToUpper(d.UUID)] = true
	}
	ok, err := a.sun.Pair(id, pin, name)
	if !ok || err != nil || listErr != nil {
		return ok, err
	}
	for i := 0; i < 24; i++ {
		if i > 0 {
			time.Sleep(500 * time.Millisecond)
		}
		after, err := a.sun.Devices()
		if err != nil {
			continue
		}
		for _, d := range after {
			if !before[strings.ToUpper(d.UUID)] {
				a.dedupeClients(d.UUID)
				return true, nil
			}
		}
	}
	return false, nil
}
