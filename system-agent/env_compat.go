package main

import "os"

// envFirst returns the first non-empty environment value among keys, else def.
// Canonical RPCNODE_* with legacy TRON_* fallback (one release).
func envFirst(def string, keys ...string) string {
	for _, k := range keys {
		if v := os.Getenv(k); v != "" {
			return v
		}
	}
	return def
}
