package main

import "testing"

func TestParseTonClientVersionOutput(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"bash: line 1: validator-engine: command not found", ""},
		{"validator-engine: command not found", ""},
		{"ton-validator-engine version 1.2.3", "1.2.3"},
		{"validator-engine-1.0.50", "1.0.50"},
		{"1.0.50\n", "1.0.50"},
		{"", ""},
	}
	for _, tc := range cases {
		if got := parseTonClientVersionOutput(tc.in); got != tc.want {
			t.Fatalf("parseTonClientVersionOutput(%q)=%q want %q", tc.in, got, tc.want)
		}
	}
}

func TestFormatClientVersionRejectsShellNoise(t *testing.T) {
	if got := formatClientVersion("bash: line 1: validator-engine: command not found"); got != "" {
		t.Fatalf("got %q", got)
	}
}
