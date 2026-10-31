//go:build windows

// Castle of Magic setup and uninstaller.
//
// setup.exe (default build): installs into %LOCALAPPDATA%\Programs\Castle of Magic without admin rights,
// creates desktop and Start menu shortcuts, and registers an entry in "Installed apps".
// uninstall.exe (build tag "uninstaller"): removes it again, optionally keeping saved progress.
package main

import (
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"strings"
	"time"
	"unsafe"

	ole "github.com/go-ole/go-ole"
	"github.com/go-ole/go-ole/oleutil"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

const (
	appName    = "Castle of Magic"
	appVersion = "1.2"
	regKey     = `Software\Microsoft\Windows\CurrentVersion\Uninstall\CastleOfMagic`

	mbYesNo         = 0x4
	mbIconError     = 0x10
	mbIconQuestion  = 0x20
	mbIconWarning   = 0x30
	mbIconInfo      = 0x40
	mbSetForeground = 0x10000
	mbTopmost       = 0x40000
	idYes           = 6
)

var logPath = filepath.Join(os.TempDir(), "CastleOfMagic-setup.log")

func main() {
	if f, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644); err == nil {
		log.SetOutput(f)
		defer f.Close()
	}
	log.Printf("---- %s %s, args %v", appName, appVersion, os.Args)
	defer func() {
		if r := recover(); r != nil {
			log.Printf("CRASH: %v\n%s", r, debug.Stack())
			msg(fmt.Sprintf("Setup crashed: %v\n\nDetails were saved in:\n%s", r, logPath), mbIconError)
			os.Exit(1)
		}
	}()
	if uninstallerBuild {
		uninstall()
		return
	}
	setup()
}

func msg(text string, flags uint32) int32 {
	t, _ := windows.UTF16PtrFromString(text)
	c, _ := windows.UTF16PtrFromString(appName + " Setup")
	log.Printf("message: %q", strings.ReplaceAll(text, "\n", " "))
	r, _ := windows.MessageBox(0, t, c, flags|mbSetForeground|mbTopmost)
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
	iconNote, err := doInstall(dir)
	done()
	if err != nil {
		log.Printf("install failed: %v", err)
		msg("Setup could not finish.\n\n"+err.Error()+"\n\nLog file:\n"+logPath, mbIconError)
		os.Exit(1)
	}
	log.Printf("install finished")
	if msg(appName+" is installed.\n\n"+iconNote+"\n\nStart the game now?", mbYesNo|mbIconInfo) == idYes {
		cmd := exec.Command(filepath.Join(dir, "CastleOfMagic.exe"))
		cmd.Dir = dir
		_ = cmd.Start()
	}
}

// doInstall copies the game and creates shortcuts. A missing shortcut is reported, not fatal.
func doInstall(dir string) (string, error) {
	log.Printf("installing into %s (%d MB payload)", dir, len(payload)>>20)
	sizeKB, err := installFiles(payload, dir)
	if errors.Is(err, errLocked) {
		return "", errors.New(appName + " is running. Close the game, then run setup again.")
	}
	if err != nil {
		return "", err
	}
	log.Printf("files installed: %d MB", sizeKB>>10)
	uninst := filepath.Join(dir, "uninstall.exe")
	if err := os.WriteFile(uninst, uninstallerExe, 0o755); err != nil {
		return "", fmt.Errorf("could not write the uninstaller: %v", err)
	}
	note := "To play, double-click the \"" + appName + "\" icon on your desktop."
	links, err := makeShortcuts(dir)
	if err != nil {
		log.Printf("shortcuts failed: %v", err)
		note = "The desktop icon could not be created (" + err.Error() + ").\nThe game is in:\n" + dir
	} else {
		log.Printf("shortcuts: %v", links)
	}
	if err := register(dir, links, sizeKB); err != nil {
		log.Printf("register failed: %v", err) // only affects the "Installed apps" entry
	}
	return note, nil
}

// makeShortcuts creates desktop and Start menu shortcuts through the Windows Shell COM API
// (no PowerShell or script host process) and returns their paths.
func makeShortcuts(dir string) ([]string, error) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	if err := ole.CoInitializeEx(0, ole.COINIT_APARTMENTTHREADED); err != nil {
		if oe, ok := err.(*ole.OleError); !ok || oe.Code() != 1 { // 1 = S_FALSE, already initialised
			return nil, fmt.Errorf("COM: %v", err)
		}
	}
	defer ole.CoUninitialize()

	unk, err := oleutil.CreateObject("WScript.Shell")
	if err != nil {
		return nil, fmt.Errorf("shell: %v", err)
	}
	defer unk.Release()
	shell, err := unk.QueryInterface(ole.IID_IDispatch)
	if err != nil {
		return nil, fmt.Errorf("shell: %v", err)
	}
	defer shell.Release()

	target := filepath.Join(dir, "CastleOfMagic.exe")
	var made []string
	var lastErr error
	for _, id := range []*windows.KNOWNFOLDERID{windows.FOLDERID_Desktop, windows.FOLDERID_Programs} {
		folder, err := windows.KnownFolderPath(id, 0)
		if err != nil || folder == "" {
			lastErr = fmt.Errorf("folder: %v", err)
			continue
		}
		path := filepath.Join(folder, appName+".lnk")
		if err := saveShortcut(shell, path, target, dir); err != nil {
			lastErr = err
			log.Printf("shortcut %s: %v", path, err)
			continue
		}
		made = append(made, path)
	}
	if len(made) == 0 {
		if lastErr == nil {
			lastErr = errors.New("no shortcut was created")
		}
		return nil, lastErr
	}
	return made, nil
}

func saveShortcut(shell *ole.IDispatch, path, target, dir string) error {
	v, err := oleutil.CallMethod(shell, "CreateShortcut", path)
	if err != nil {
		return fmt.Errorf("create: %v", err)
	}
	link := v.ToIDispatch()
	defer link.Release()
	for name, value := range map[string]string{
		"TargetPath":       target,
		"WorkingDirectory": dir,
		"IconLocation":     target + ",0",
		"Description":      "Play " + appName,
	} {
		if _, err := oleutil.PutProperty(link, name, value); err != nil {
			return fmt.Errorf("%s: %v", name, err)
		}
	}
	if _, err := oleutil.CallMethod(link, "Save"); err != nil {
		return fmt.Errorf("save: %v", err)
	}
	if _, err := os.Stat(path); err != nil {
		return fmt.Errorf("not found after saving: %v", err)
	}
	return nil
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
		windows.MessageBox(0, t, c, mbIconInfo|mbSetForeground|mbTopmost)
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
