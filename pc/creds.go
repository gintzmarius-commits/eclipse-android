package main

// Sunshine keeps its Web UI login in sunshine_state.json:
//   "username": "...", "salt": "<16 chars>",
//   "password": UPPERCASE_HEX(SHA-256(password + salt))
// Eclipse writes it through `sunshine --creds`, then checks the file itself
// and writes it directly if needed, so a silent failure cannot go unnoticed.

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
)

// sunshineHash reproduces util::hex(crypto::hash(password + salt)):
// SHA-256, bytes written from last to first, uppercase hexadecimal.
// (Checked against the output of a real `sunshine --creds`.)
func sunshineHash(pass, salt string) string {
	h := sha256.Sum256([]byte(pass + salt))
	for i, j := 0, len(h)-1; i < j; i, j = i+1, j-1 {
		h[i], h[j] = h[j], h[i]
	}
	return strings.ToUpper(hex.EncodeToString(h[:]))
}

// readSunshineConf returns the key = value pairs of sunshine.conf.
func readSunshineConf(dir string) map[string]string {
	out := map[string]string{}
	f, err := os.Open(filepath.Join(dir, "sunshine.conf"))
	if err != nil {
		return out
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if k, v, ok := strings.Cut(line, "="); ok {
			out[strings.TrimSpace(k)] = strings.TrimSpace(v)
		}
	}
	return out
}

// credsFile is the file the running Sunshine reads its login from.
func credsFile(dir string) string {
	conf := readSunshineConf(dir)
	name := "sunshine_state.json"
	if v := conf["credentials_file"]; v != "" {
		name = v
	} else if v := conf["file_state"]; v != "" {
		name = v
	}
	if !filepath.IsAbs(name) {
		name = filepath.Join(dir, name)
	}
	return name
}

func readStateFile(file string) (map[string]any, error) {
	data, err := os.ReadFile(file)
	if err != nil {
		return nil, err
	}
	m := map[string]any{}
	if err := json.Unmarshal(data, &m); err != nil {
		return nil, err
	}
	return m, nil
}

func credsMatch(file, user, pass string) bool {
	m, err := readStateFile(file)
	if err != nil {
		return false
	}
	u, _ := m["username"].(string)
	salt, _ := m["salt"].(string)
	hash, _ := m["password"].(string)
	return strings.EqualFold(u, user) && salt != "" && hash == sunshineHash(pass, salt)
}

func writeCredsFile(file, user, pass string) error {
	m, err := readStateFile(file)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			// Unreadable file: keep a copy, then start from a clean one.
			_ = os.Rename(file, file+".bak")
			log.Printf("creds: %s unreadable (%v), saved as .bak", file, err)
		}
		m = map[string]any{}
	}
	salt := randomPassword(16)
	m["username"] = user
	m["salt"] = salt
	m["password"] = sunshineHash(pass, salt)

	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	enc.SetIndent("", "    ")
	if err := enc.Encode(m); err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(file), 0o755); err != nil {
		return err
	}
	tmp := file + ".eclipse-tmp"
	if err := os.WriteFile(tmp, buf.Bytes(), 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, file)
}

// ensureCreds makes sure Sunshine's login file holds user / pass.
func ensureCreds(user, pass string) error {
	dir := sunshineConfigDir()
	if dir == "" {
		return errors.New("dossier de configuration de Sunshine introuvable")
	}
	file := credsFile(dir)
	if credsMatch(file, user, pass) {
		log.Printf("creds: %s OK", file)
		return nil
	}
	log.Printf("creds: %s does not hold our login, writing it directly", file)
	if err := writeCredsFile(file, user, pass); err != nil {
		return fmt.Errorf("impossible d'écrire les identifiants de Sunshine (%v)", err)
	}
	if !credsMatch(file, user, pass) {
		return fmt.Errorf("les identifiants n'ont pas pu être enregistrés dans %s", file)
	}
	return nil
}

// sunshineDiagnostics describes the situation for the log file.
func sunshineDiagnostics() string {
	dir := sunshineConfigDir()
	file, match := "", false
	if dir != "" {
		file = credsFile(dir)
		if lg := loadLogin(); lg != nil {
			match = credsMatch(file, lg.User, lg.Pass)
		}
	}
	return fmt.Sprintf("running=%q creds=%q match=%v", runningSunshineExe(), file, match)
}
