package main

import (
	"os"
	"path/filepath"
)

// toolkitCtlPath prefers rpcnodectl; tronctl remains a compatibility wrapper.
func toolkitCtlPath(dir string) string {
	if dir == "" {
		return ""
	}
	for _, name := range []string{"rpcnodectl", "tronctl"} {
		p := filepath.Join(dir, name)
		if st, err := os.Stat(p); err == nil && !st.IsDir() {
			return p
		}
	}
	return filepath.Join(dir, "rpcnodectl")
}
