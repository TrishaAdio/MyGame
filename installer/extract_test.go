package main

import (
	"archive/zip"
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func makeZip(t *testing.T, files map[string]string) []byte {
	t.Helper()
	var buf bytes.Buffer
	w := zip.NewWriter(&buf)
	for name, body := range files {
		f, err := w.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		f.Write([]byte(body))
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func read(t *testing.T, p string) string {
	t.Helper()
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatalf("read %s: %v", p, err)
	}
	return string(b)
}

func TestInstallAndUpdateKeepsSaves(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "Castle of Magic")
	v1 := makeZip(t, map[string]string{
		"CastleOfMagic/CastleOfMagic.exe":       "exe-v1",
		"CastleOfMagic/app/CastleOfMagicPC.jar": "jar-v1",
		"CastleOfMagic/runtime/bin/old.dll":     "old",
		"CastleOfMagic/README.txt":              "readme",
	})
	if _, err := installFiles(v1, dir); err != nil {
		t.Fatal(err)
	}
	// the player creates saves and settings
	os.MkdirAll(filepath.Join(dir, "saves", "adventure", "rms"), 0o755)
	os.WriteFile(filepath.Join(dir, "saves", "adventure", "rms", "save.rms"), []byte("progress"), 0o644)
	os.WriteFile(filepath.Join(dir, "settings.properties"), []byte("playerName=Anirban"), 0o644)

	v2 := makeZip(t, map[string]string{
		"CastleOfMagic/CastleOfMagic.exe":       "exe-v2",
		"CastleOfMagic/app/CastleOfMagicPC.jar": "jar-v2",
		"CastleOfMagic/runtime/bin/new.dll":     "new",
	})
	size, err := installFiles(v2, dir)
	if err != nil {
		t.Fatal(err)
	}
	if size < 0 {
		t.Fatal("bad size")
	}
	if got := read(t, filepath.Join(dir, "CastleOfMagic.exe")); got != "exe-v2" {
		t.Errorf("exe not updated: %q", got)
	}
	if got := read(t, filepath.Join(dir, "app", "CastleOfMagicPC.jar")); got != "jar-v2" {
		t.Errorf("jar not updated: %q", got)
	}
	if _, err := os.Stat(filepath.Join(dir, "runtime", "bin", "old.dll")); !os.IsNotExist(err) {
		t.Error("old runtime file should be gone")
	}
	if got := read(t, filepath.Join(dir, "saves", "adventure", "rms", "save.rms")); got != "progress" {
		t.Error("save was lost")
	}
	if got := read(t, filepath.Join(dir, "settings.properties")); got != "playerName=Anirban" {
		t.Error("settings were lost")
	}
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		if filepath.Ext(e.Name()) != "" && len(e.Name()) > 5 && e.Name()[len(e.Name())-5:] == ".old-" {
			t.Errorf("leftover %s", e.Name())
		}
	}
	for _, n := range []string{"app", "runtime"} {
		matches, _ := filepath.Glob(filepath.Join(dir, n+".old-*"))
		if len(matches) > 0 {
			t.Errorf("leftover backup folders: %v", matches)
		}
	}
}

func TestRejectsPathsOutsideInstallFolder(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "game")
	bad := makeZip(t, map[string]string{
		"CastleOfMagic/../../evil.txt": "x",
	})
	if _, err := installFiles(bad, dir); err == nil {
		t.Fatal("expected an error for a path leaving the install folder")
	}
	if _, err := os.Stat(filepath.Join(root, "evil.txt")); !os.IsNotExist(err) {
		t.Fatal("file was written outside the install folder")
	}
}

func TestIgnoresFilesOutsideZipRoot(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "game")
	z := makeZip(t, map[string]string{
		"CastleOfMagic/README.txt": "ok",
		"other/file.txt":           "ignored",
	})
	if _, err := installFiles(z, dir); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, "file.txt")); !os.IsNotExist(err) {
		t.Fatal("file outside the zip root was installed")
	}
}

// Uses the real game package when build.sh has copied it here.
func TestRealPayload(t *testing.T) {
	data, err := os.ReadFile("payload.zip")
	if err != nil {
		t.Skip("payload.zip not present")
	}
	dir := filepath.Join(t.TempDir(), "Castle of Magic")
	for round := 0; round < 2; round++ {
		size, err := installFiles(data, dir)
		if err != nil {
			t.Fatal(err)
		}
		if round == 0 {
			os.MkdirAll(filepath.Join(dir, "saves"), 0o755)
			os.WriteFile(filepath.Join(dir, "saves", "keep.txt"), []byte("x"), 0o644)
		}
		t.Logf("round %d: %d MB installed", round, size/1024)
	}
	for _, f := range []string{"CastleOfMagic.exe", "README.txt", "app/CastleOfMagicPC.jar", "app/games/adventure.jar", "runtime/bin/javaw.exe", "saves/keep.txt"} {
		if _, err := os.Stat(filepath.Join(dir, filepath.FromSlash(f))); err != nil {
			t.Errorf("missing %s", f)
		}
	}
}
