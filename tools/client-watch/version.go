package main

// API contract the macOS app checks via GET /healthz.
// Bump apiVersion when /api/v1/clients or /files/ change in a breaking way.
const (
	watchService = "client-watch"
	watchVersion = "0.2.1"
	watchAPI     = 2
)
