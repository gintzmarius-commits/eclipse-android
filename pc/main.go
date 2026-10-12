package main

import (
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
)

func main() {
	setupLog()

	if len(os.Args) > 1 && os.Args[1] == "--uninstall" {
		uninstall()
		return
	}
	// Automatic update (Windows): the new version installs itself over the
	// old one without any window, then starts.
	if len(os.Args) > 1 && os.Args[1] == "--update" {
		startBackground = hasArg("--background")
		if err := silentUpdate(); err != nil {
			log.Printf("update: %v", err)
			fatal("La mise à jour d'Eclipse a échoué : " + err.Error())
		}
		return
	}

	background := hasArg("--background")
	core := newAppState()
	if !installMode() {
		// One Eclipse at a time: the one running shows its window.
		if handOff(background) {
			return
		}
		core.updatedFrom = migrateData()
	}
	token := randHex(16)

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		fatal("Impossible de démarrer Eclipse : " + err.Error())
	}
	port := ln.Addr().(*net.TCPAddr).Port
	go func() { _ = http.Serve(ln, core.routes(token)) }()

	if !installMode() {
		writeInstance(port, token)
		if os.Getenv("ECLIPSE_NO_SETUP") != "1" { // tests: only file sharing
			go core.runSetup()
		}
		go core.files.start()
		go ensureAutostart()
	}

	url := fmt.Sprintf("http://127.0.0.1:%d/?t=%s", port, token)
	if os.Getenv("ECLIPSE_NOWINDOW") == "1" {
		fmt.Println(url)
		select {}
	}
	if installMode() {
		runWindow(url, core)
		return
	}
	for {
		if background {
			log.Printf("running in the background")
			if !keepRunning() {
				clearInstance()
				return
			}
			core.waitShow()
		}
		windowOpen.Store(true)
		runWindow(url, core)
		windowOpen.Store(false)
		background = true
		select {
		case <-core.show: // asked again while the window was closing
			background = false
		default:
		}
	}
}

func hasArg(a string) bool {
	for _, x := range os.Args[1:] {
		if x == a {
			return true
		}
	}
	return false
}

func setupLog() {
	dir := logDir()
	_ = os.MkdirAll(dir, 0o755)
	f, err := os.OpenFile(filepath.Join(dir, "eclipse.log"), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err == nil {
		log.SetOutput(f)
	}
	log.Printf("Eclipse %s (%s)", version, platformName)
}
