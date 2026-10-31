//go:build windows

// Castle of Magic setup and uninstaller.
//
// setup.exe (default build): installs into %LOCALAPPDATA%\Programs\Castle of Magic without admin rights,
// creates desktop and Start menu shortcuts, and registers an entry in "Installed apps".
// uninstall.exe (build tag "uninstaller"): removes it again, optionally keeping saved progress.
package main

import (
	"bytes"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"
	"unicode/utf16"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

const (
	appName    = "Castle of Magic"
	appVersion = "1.1"
	regKey     = `Software\Microsoft\Windows\CurrentVersion\Uninstall\CastleOfMagic`

	mbYesNo         = 0x4
	mbIconError     = 0x10
	mbIconQuestion  = 0x20
	mbIconWarning   = 0x30
	mbIconInfo      = 0x40
	mbSetForeground = 0x10000
	idYes           = 6
	createNoWindow  = 0x08000000
)

func main() {
	if uninstallerBuild {
		uninstall()
		return
	}
	setup()
}

func msg(text string, flags uint32) int32 {
	t, _ := windows.UTF16PtrFromString(text)
	c, _ := windows.UTF16PtrFromString(appName + " Setup")
	r, _ := windows.MessageBox(0, t, c, flags|mbSetForeground)
	return r
}

func installDir() string {
	base := os.Getenv("LOCALAPPDATA")
	if base == "" {
		home, _ := os.UserHomeDir()
		base = filepath.Join(home, "AppData", "Local")
	}
	return filepath.Join(base, "Programs", appName)
}

// ------------------------------------------------------------ setup

func setup() {
	dir := installDir()
	_, statErr := os.Stat(filepath.Join(dir, "CastleOfMagic.exe"))
	update := statErr == nil
	question := fmt.Sprintf("Install %s on this PC?\n\nFolder:\n%s\n\nA \"%s\" icon will be added to your desktop and Start menu.", appName, dir, appName)
	if update {
		question = fmt.Sprintf("Update %s?\n\nFolder:\n%s\n\nYour saved progress and settings are kept.", appName, dir)
	}
	if msg(question, mbYesNo|mbIconQuestion) != idYes {
		return
	}

	done := showBusy("Installing " + appName + ", please wait...\n\nThis takes a few seconds.")
	err := doInstall(dir)
	done()
	if err != nil {
		msg("Setup could not finish.\n\n"+err.Error(), mbIconError)
		os.Exit(1)
	}
	if msg(appName+" is installed.\n\nTo play, double-click the \""+appName+"\" icon on your desktop.\n\nStart the game now?", mbYesNo|mbIconInfo) == idYes {
		cmd := exec.Command(filepath.Join(dir, "CastleOfMagic.exe"))
		cmd.Dir = dir
		_ = cmd.Start()
	}
}

func doInstall(dir string) error {
	sizeKB, err := installFiles(payload, dir)
	if errors.Is(err, errLocked) {
		return errors.New(appName + " is running. Close the game, then run setup again.")
	}
	if err != nil {
		return err
	}
	uninst := filepath.Join(dir, "uninstall.exe")
	if err := os.WriteFile(uninst, uninstallerExe, 0o755); err != nil {
		return fmt.Errorf("could not write the uninstaller: %v", err)
	}
	links, err := makeShortcuts(dir)
	if err != nil {
		return fmt.Errorf("the game was installed, but the desktop icon could not be created:\n%v\n\nYou can start it from:\n%s", err, dir)
	}
	if err := register(dir, links, sizeKB); err != nil {
		return fmt.Errorf("the game was installed, but it could not be added to Installed apps: %v", err)
	}
	return nil
}

const shortcutScript = `
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$shell = New-Object -ComObject WScript.Shell
$made = @()
foreach ($folder in @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))) {
  if (-not $folder) { continue }
  $path = Join-Path $folder 'Castle of Magic.lnk'
  $link = $shell.CreateShortcut($path)
  $link.TargetPath = $env:COM_TARGET
  $link.WorkingDirectory = $env:COM_DIR
  $link.IconLocation = $env:COM_TARGET + ',0'
  $link.Description = 'Play Castle of Magic'
  $link.Save()
  $made += $path
}
$made -join [char]10
`

// makeShortcuts creates the desktop and Start menu shortcuts and returns their paths.
// Paths are passed through environment variables, never pasted into the script.
func makeShortcuts(dir string) ([]string, error) {
	out, err := powershell(shortcutScript,
		"COM_TARGET="+filepath.Join(dir, "CastleOfMagic.exe"),
		"COM_DIR="+dir)
	if err != nil {
		return nil, err
	}
	var links []string
	for _, l := range strings.Split(out, "\n") {
		if l = strings.TrimSpace(l); l != "" {
			links = append(links, l)
		}
	}
	if len(links) == 0 {
		return nil, errors.New("no shortcut was created")
	}
	return links, nil
}

func powershell(script string, env ...string) (string, error) {
	u := utf16.Encode([]rune(script))
	b := make([]byte, len(u)*2)
	for i, v := range u {
		b[2*i] = byte(v)
		b[2*i+1] = byte(v >> 8)
	}
	exe := filepath.Join(os.Getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
	if _, err := os.Stat(exe); err != nil {
		exe = "powershell.exe"
	}
	cmd := exec.Command(exe, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
		"-EncodedCommand", base64.StdEncoding.EncodeToString(b))
	cmd.Env = append(os.Environ(), env...)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	if err := cmd.Run(); err != nil {
		return "", fmt.Errorf("%v %s", err, strings.TrimSpace(stderr.String()))
	}
	return stdout.String(), nil
}

func register(dir string, links []string, sizeKB int) error {
	k, _, err := registry.CreateKey(registry.CURRENT_USER, regKey, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()
	exe := filepath.Join(dir, "CastleOfMagic.exe")
	values := map[string]string{
		"DisplayName":     appName,
		"DisplayVersion":  appVersion,
		"Publisher":       "Castle of Magic PC (personal use)",
		"InstallLocation": dir,
		"DisplayIcon":     exe + ",0",
		"UninstallString": `"` + filepath.Join(dir, "uninstall.exe") + `"`,
		"Shortcuts":       strings.Join(links, "|"),
	}
	for name, v := range values {
		if err := k.SetStringValue(name, v); err != nil {
			return err
		}
	}
	_ = k.SetDWordValue("NoModify", 1)
	_ = k.SetDWordValue("NoRepair", 1)
	_ = k.SetDWordValue("EstimatedSize", uint32(sizeKB))
	return nil
}

// showBusy shows a small "please wait" box and returns a function that closes it.
func showBusy(text string) func() {
	title := appName + " Setup - working"
	go func() {
		runtime.LockOSThread()
		t, _ := windows.UTF16PtrFromString(text)
		c, _ := windows.UTF16PtrFromString(title)
		windows.MessageBox(0, t, c, mbIconInfo|mbSetForeground)
	}()
	return func() {
		user32 := windows.NewLazySystemDLL("user32.dll")
		find, post := user32.NewProc("FindWindowW"), user32.NewProc("PostMessageW")
		c, _ := windows.UTF16PtrFromString(title)
		for i := 0; i < 30; i++ {
			if h, _, _ := find.Call(0, uintptr(unsafe.Pointer(c))); h != 0 {
				post.Call(h, 0x0010 /* WM_CLOSE */, 0, 0)
				return
			}
			time.Sleep(100 * time.Millisecond)
		}
	}
}

// ------------------------------------------------------------ uninstall

func uninstall() {
	dir := ""
	for _, a := range os.Args[1:] {
		if strings.HasPrefix(a, "/dir=") {
			dir = strings.TrimPrefix(a, "/dir=")
		}
	}
	if dir == "" {
		// Running from the install folder: a program can't delete itself, so run a copy from TEMP.
		self, err := os.Executable()
		if err == nil {
			dir = filepath.Dir(self)
			tmp := filepath.Join(os.TempDir(), "CastleOfMagic-uninstall.exe")
			if copyFile(self, tmp) == nil {
				if exec.Command(tmp, "/dir="+dir).Start() == nil {
					return
				}
			}
		}
	}

	// Only touch a folder that really is a Castle of Magic install.
	if _, err := os.Stat(filepath.Join(dir, "CastleOfMagic.exe")); err != nil {
		msg(appName+" is not installed in:\n"+dir, mbIconError)
		return
	}
	if _, err := os.Stat(filepath.Join(dir, "app", "CastleOfMagicPC.jar")); err != nil {
		msg(appName+" is not installed in:\n"+dir, mbIconError)
		return
	}

	if msg("Remove "+appName+" from this PC?", mbYesNo|mbIconQuestion) != idYes {
		return
	}
	deleteSaves := msg("Also delete your saved progress and settings?\n\nChoose No to keep them, in case you install the game again.", mbYesNo|mbIconWarning) == idYes

	time.Sleep(time.Second) // let the copy that started us exit

	var links []string
	if k, err := registry.OpenKey(registry.CURRENT_USER, regKey, registry.QUERY_VALUE); err == nil {
		if v, _, err := k.GetStringValue("Shortcuts"); err == nil && v != "" {
			links = strings.Split(v, "|")
		}
		k.Close()
	}
	for _, l := range links {
		if strings.HasSuffix(strings.ToLower(l), ".lnk") {
			_ = os.Remove(l)
		}
	}

	parts := []string{"app", "runtime", "licenses", "CastleOfMagic.exe", "README.txt", "uninstall.exe"}
	var lastErr error
	for try := 0; try < 6; try++ {
		lastErr = nil
		for _, p := range parts {
			if err := os.RemoveAll(filepath.Join(dir, p)); err != nil {
				lastErr = err
			}
		}
		if lastErr == nil {
			break
		}
		time.Sleep(time.Second)
	}
	if lastErr != nil {
		msg("Some files could not be removed. Close "+appName+" and run the uninstaller again.\n\n"+lastErr.Error(), mbIconError)
		return
	}
	if deleteSaves {
		_ = os.RemoveAll(dir)
	} else {
		_ = os.Remove(dir) // only succeeds if nothing is left
	}
	_ = registry.DeleteKey(registry.CURRENT_USER, regKey)

	if deleteSaves {
		msg(appName+" was removed.", mbIconInfo)
	} else {
		msg(appName+" was removed.\n\nYour saved progress is still in:\n"+dir, mbIconInfo)
	}
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}
