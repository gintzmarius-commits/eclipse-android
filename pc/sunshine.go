package main

// Client for the Sunshine configuration API (https://localhost:47990).

import (
	"bytes"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

var errAuth = errors.New("identifiants refusés par Sunshine")
var errNotFound = errors.New("introuvable")

type Sun struct {
	base string
	user string
	pass string
	hc   *http.Client
}

func newSun() *Sun {
	base := os.Getenv("ECLIPSE_SUNSHINE_URL")
	if base == "" {
		base = "https://localhost:47990"
	}
	tr := &http.Transport{
		// Sunshine uses a self-signed certificate on localhost only.
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
		Proxy:           nil,
	}
	hc := &http.Client{Transport: tr, Timeout: 10 * time.Second,
		// Sunshine redirects to /welcome when it has no login yet: report it
		// as an access problem instead of following it to an HTML page.
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	return &Sun{base: strings.TrimRight(base, "/"), hc: hc}
}

func (s *Sun) setLogin(user, pass string) { s.user, s.pass = user, pass }

func (s *Sun) do(method, path string, body any, out any) error {
	return s.doTimeout(method, path, body, out, 0)
}

// doTimeout is do with a longer wait than usual (0 keeps the usual one).
func (s *Sun) doTimeout(method, path string, body any, out any, timeout time.Duration) error {
	var rdr io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return err
		}
		rdr = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, s.base+path, rdr)
	if err != nil {
		return err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if s.user != "" {
		req.SetBasicAuth(s.user, s.pass)
	}
	hc := s.hc
	if timeout > 0 {
		c := *s.hc
		c.Timeout = timeout
		hc = &c
	}
	resp, err := hc.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	switch {
	case resp.StatusCode == 401:
		return errAuth
	case resp.StatusCode >= 300 && resp.StatusCode < 400:
		return fmt.Errorf("%w (aucun identifiant enregistré dans Sunshine)", errAuth)
	case resp.StatusCode == 404:
		return errNotFound
	case resp.StatusCode >= 300:
		var e struct {
			Error string `json:"error"`
		}
		_ = json.Unmarshal(data, &e)
		if e.Error != "" {
			return fmt.Errorf("Sunshine : %s", e.Error)
		}
		return fmt.Errorf("Sunshine a répondu %d", resp.StatusCode)
	}
	if out != nil && len(data) > 0 {
		return json.Unmarshal(data, out)
	}
	return nil
}

// Ready reports whether the Sunshine web server answers at all.
func (s *Sun) Ready() bool {
	req, _ := http.NewRequest("GET", s.base+"/api/configLocale", nil)
	c := *s.hc
	c.Timeout = 1500 * time.Millisecond
	resp, err := c.Do(req)
	if err != nil {
		return false
	}
	resp.Body.Close()
	return resp.StatusCode < 500
}

func (s *Sun) CheckLogin() error {
	var v map[string]any
	return s.do("GET", "/api/config", nil, &v)
}

// ---- pairing --------------------------------------------------------

type Pairing struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Address string `json:"address"`
}

// Pending lists pairing requests waiting for a PIN.
// supported=false means this Sunshine version predates the list API.
func (s *Sun) Pending() (pairings []Pairing, supported bool, err error) {
	var r struct {
		Pairings *[]Pairing `json:"pairings"`
	}
	err = s.do("GET", "/api/pin", nil, &r)
	if errors.Is(err, errNotFound) || (err != nil && strings.Contains(err.Error(), "40")) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, err
	}
	if r.Pairings == nil {
		return nil, false, nil
	}
	return *r.Pairings, true, nil
}

// Pair gives Sunshine the PIN for a request. Recent versions answer once
// the device has finished connecting (up to Sunshine's ping_timeout, 10 s
// by default), hence the long wait.
func (s *Sun) Pair(id, pin, name string) (bool, error) {
	body := map[string]string{"pin": pin, "name": name}
	if id != "" {
		body["pairing_id"] = id
	}
	var r struct {
		Status any `json:"status"`
	}
	if err := s.doTimeout("POST", "/api/pin", body, &r, 45*time.Second); err != nil {
		return false, err
	}
	return r.Status == true || r.Status == "true", nil
}

// CancelPairing refuses a request waiting for its PIN.
func (s *Sun) CancelPairing(id string) (bool, error) {
	var r struct {
		Status any `json:"status"`
	}
	if err := s.do("DELETE", "/api/pin", map[string]string{"pairing_id": id}, &r); err != nil {
		return false, err
	}
	return r.Status == true || r.Status == "true", nil
}

// ---- applications ---------------------------------------------------

type App struct {
	Index int    `json:"index"`
	Name  string `json:"name"`
	Cmd   string `json:"cmd"`
}

func (s *Sun) Apps() ([]App, error) {
	var r struct {
		Apps []map[string]any `json:"apps"`
	}
	if err := s.do("GET", "/api/apps", nil, &r); err != nil {
		return nil, err
	}
	out := make([]App, 0, len(r.Apps))
	for i, a := range r.Apps {
		name, _ := a["name"].(string)
		cmd, _ := a["cmd"].(string)
		if cmd == "" {
			if d, ok := a["detached"].([]any); ok && len(d) > 0 {
				cmd, _ = d[0].(string)
			}
		}
		out = append(out, App{Index: i, Name: name, Cmd: cmd})
	}
	return out, nil
}

func (s *Sun) AddApp(a appSpec) error {
	body := map[string]any{
		"name":                    a.Name,
		"index":                   -1,
		"output":                  "",
		"cmd":                     a.Cmd,
		"working-dir":             a.WorkDir,
		"exclude-global-prep-cmd": false,
		"elevated":                false,
		"auto-detach":             true,
		"wait-all":                true,
		"exit-timeout":            5,
		"prep-cmd":                []any{},
		"detached":                a.Detached,
		"image-path":              "",
	}
	if a.Detached == nil {
		body["detached"] = []string{}
	}
	return s.do("POST", "/api/apps", body, nil)
}

func (s *Sun) DeleteApp(index int) error {
	return s.do("DELETE", fmt.Sprintf("/api/apps/%d", index), nil, nil)
}

// ---- paired devices -------------------------------------------------

type Device struct {
	Name string `json:"name"`
	UUID string `json:"uuid"`
}

func (s *Sun) Devices() ([]Device, error) {
	var r struct {
		NamedCerts []Device `json:"named_certs"`
	}
	if err := s.do("GET", "/api/clients/list", nil, &r); err != nil {
		return nil, err
	}
	return r.NamedCerts, nil
}

func (s *Sun) RemoveDevice(uuid string) error {
	return s.do("POST", "/api/clients/unpair", map[string]string{"uuid": uuid}, nil)
}
