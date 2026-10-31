package main

import (
	"archive/zip"
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"
)

// errLocked means a game folder could not be replaced, usually because the game is running.
var errLocked = errors.New("game files are in use")

// Folders that belong to the program and are replaced on every install.
// Everything else in the install folder (saves, settings.properties, screenshots, logs) is kept.
var replacedDirs = []string{"app", "runtime", "licenses"}

const zipRoot = "CastleOfMagic/"

// installFiles unpacks the game zip into dest and returns the installed size in KB.
func installFiles(payload []byte, dest string) (int, error) {
	zr, err := zip.NewReader(bytes.NewReader(payload), int64(len(payload)))
	if err != nil {
		return 0, fmt.Errorf("the installer data is damaged: %v", err)
	}
	if err := os.MkdirAll(dest, 0o755); err != nil {
		return 0, err
	}

	// Move the old program folders aside first. If any of them is in use, nothing is changed.
	stamp := fmt.Sprintf(".old-%d", time.Now().UnixNano())
	var moved []string
	restore := func() {
		for _, n := range moved {
			_ = os.RemoveAll(filepath.Join(dest, n))
			_ = os.Rename(filepath.Join(dest, n+stamp), filepath.Join(dest, n))
		}
	}
	for _, n := range replacedDirs {
		p := filepath.Join(dest, n)
		if _, err := os.Stat(p); err != nil {
			continue
		}
		if err := os.Rename(p, p+stamp); err != nil {
			restore()
			return 0, errLocked
		}
		moved = append(moved, n)
	}

	var total uint64
	for _, f := range zr.File {
		rel, ok := relPath(f.Name)
		if !ok {
			continue
		}
		target := filepath.Join(dest, filepath.FromSlash(rel))
		if !inside(dest, target) {
			restore()
			return 0, fmt.Errorf("unsafe path in installer data: %s", f.Name)
		}
		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0o755); err != nil {
				restore()
				return 0, err
			}
			continue
		}
		if err := writeEntry(f, target); err != nil {
			restore()
			return 0, fmt.Errorf("could not write %s (is the game running?): %v", rel, err)
		}
		total += f.UncompressedSize64
	}

	for _, n := range moved {
		_ = os.RemoveAll(filepath.Join(dest, n+stamp))
	}
	return int(total / 1024), nil
}

// relPath strips the zip's top folder and rejects anything that tries to leave it.
func relPath(name string) (string, bool) {
	name = strings.ReplaceAll(name, "\\", "/")
	if !strings.HasPrefix(name, zipRoot) {
		return "", false
	}
	rel := strings.TrimPrefix(name, zipRoot)
	if rel == "" {
		return "", false
	}
	for _, part := range strings.Split(rel, "/") {
		if part == ".." {
			return "", true // keep it, inside() will reject it
		}
	}
	clean := path.Clean(rel)
	if strings.HasPrefix(clean, "/") || strings.Contains(clean, ":") {
		return "", true
	}
	return clean, true
}

func inside(dir, target string) bool {
	r, err := filepath.Rel(dir, target)
	return err == nil && r != "." && r != ".." && !strings.HasPrefix(r, ".."+string(filepath.Separator)) && !filepath.IsAbs(r)
}

func writeEntry(f *zip.File, target string) error {
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return err
	}
	in, err := f.Open()
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o755)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}
