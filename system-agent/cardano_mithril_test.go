package main

import "testing"

func TestParseCardanoMithrilProgress_FilesAndSteps(t *testing.T) {
	log := `
1/7 - Checking local disk info…
2/7 - Fetching the certificate and verifying the certificate chain…
3/7 - Downloading and unpacking the cardano db snapshot
   [00:19:21] [#####] Files: 12,174/24,348 (0.0s)
   [00:00:01] 82.94 MiB/165.88 MiB (0.0s)
`
	p, ok := parseCardanoMithrilProgress(log)
	if !ok {
		t.Fatal("want pct")
	}
	if p < 40 || p > 60 {
		t.Fatalf("mid-download pct=%v want ~50", p)
	}
}

func TestParseCardanoMithrilProgress_Unpacked(t *testing.T) {
	log := `Cardano database snapshot 'abc' archives have been successfully unpacked.`
	p, ok := parseCardanoMithrilProgress(log)
	if !ok || p < 99 {
		t.Fatalf("unpacked pct=%v ok=%v", p, ok)
	}
}
