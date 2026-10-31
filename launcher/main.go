// CastleOfMagic.exe: starts the bundled Java runtime with the right settings.
// Build: GOOS=windows GOARCH=amd64 go build -ldflags "-H windowsgui -s -w" -o CastleOfMagic.exe
package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

const abovenormalPriorityClass = 0x00008000

func main() {
	exe, err := os.Executable()
	if err != nil {
		fail("Could not find the game folder: " + err.Error())
	}
	dir := filepath.Dir(exe)
	javaw := filepath.Join(dir, "runtime", "bin", "javaw.exe")
	jar := filepath.Join(dir, "app", "CastleOfMagicPC.jar")
	for _, f := range []string{javaw, jar} {
		if _, err := os.Stat(f); err != nil {
			fail("A game file is missing:\n" + f + "\n\nPlease extract the whole zip again, keeping all folders together.")
		}
	}

	args := []string{
		"-Dfile.encoding=ISO_8859_1",
		"-Dsun.java2d.uiScale=1",
		"-Dsun.java2d.d3d=true",
		"-Dsun.awt.noerasebackground=true",
		"--enable-native-access=ALL-UNNAMED",
		"--add-exports=java.desktop/sun.java2d.pipe.hw=ALL-UNNAMED",
		"-XX:+UseZGC",
		"-Xms256m",
		"-Xmx768m",
		"-cp", jar,
		"org.recompile.castle.CastleOfMagicPC",
	}
	args = append(args, os.Args[1:]...)

	_ = os.MkdirAll(filepath.Join(dir, "logs"), 0o755)
	logPath := filepath.Join(dir, "logs", "last-run.log")
	logFile, _ := os.Create(logPath)

	cmd := exec.Command(javaw, args...)
	cmd.Dir = dir
	if logFile != nil {
		cmd.Stdout = logFile
		cmd.Stderr = logFile
	}
	cmd.SysProcAttr = &syscall.SysProcAttr{CreationFlags: abovenormalPriorityClass}
	if err := cmd.Start(); err != nil {
		fail("Could not start the game: " + err.Error())
	}

	// If the game dies right away, show why instead of silently doing nothing.
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		if err != nil {
			if logFile != nil {
				logFile.Close()
			}
			data, _ := os.ReadFile(logPath)
			text := string(data)
			if len(text) > 1500 {
				text = "..." + text[len(text)-1500:]
			}
			fail(fmt.Sprintf("The game stopped unexpectedly (%v).\n\n%s\n\nFull log: %s", err, strings.TrimSpace(text), logPath))
		}
	case <-time.After(8 * time.Second):
		// running fine; the launcher can exit and leave the game running
	}
}

func fail(msg string) {
	user32 := syscall.NewLazyDLL("user32.dll")
	box := user32.NewProc("MessageBoxW")
	t, _ := syscall.UTF16PtrFromString(msg)
	c, _ := syscall.UTF16PtrFromString("Castle of Magic")
	box.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), 0x10)
	os.Exit(1)
}
