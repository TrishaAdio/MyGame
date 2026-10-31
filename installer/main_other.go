//go:build !windows

package main

import "fmt"

// The installer only runs on Windows. This file lets the install logic be tested elsewhere.
func main() { fmt.Println("Castle of Magic setup runs on Windows only.") }
