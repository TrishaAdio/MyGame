//go:build windows && !uninstaller

package main

import _ "embed"

const uninstallerBuild = false

// The game zip (dist/CastleOfMagic-PC.zip), copied here by build.sh.
//
//go:embed payload.zip
var payload []byte

// uninstall.exe, built by build.sh with -tags uninstaller.
//
//go:embed uninstall.exe
var uninstallerExe []byte
