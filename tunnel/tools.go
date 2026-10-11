//go:build tools

package eclipsenet

// Keeps gomobile's runtime in go.mod (gomobile bind needs it).
import _ "golang.org/x/mobile/bind"
