//go:build tools

// Keeps golang.org/x/mobile in go.mod: `gomobile bind` needs its bind package.
package xraybridge

import _ "golang.org/x/mobile/bind"
