package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
)

const (
	xrplVLKeyRipple = "ED2677ABFFD1B33AC6FBC3062B71F1E8397C1505E1C42C64D11AD1B28FF73F4734"
	xrplVLKeyXRPLF  = "ED42AEC58B701EEBB77356FFFEC26F83C1F0407263530F068C7C73D392C7E06FD1"
	xrplVLKeyAltnet = "ED264807102805220DA0F312E71FC2C69E1552C9C5790F6C25E3729DEB573D5860"
)

var xrplBareZeroLineRe = regexp.MustCompile(`(?m)^0\s*$`)

func xrplCanonicalValidators(env string) string {
	if normalizeEnv(env) == "testnet" {
		return `[validator_list_sites]
https://vl.altnet.rippletest.net

[validator_list_keys]
` + xrplVLKeyAltnet + `
`
	}

	return `[validator_list_sites]
https://vl.ripple.com
https://unl.xrplf.org

[validator_list_keys]
` + xrplVLKeyRipple + `
` + xrplVLKeyXRPLF + `
`
}

func xrplRequiredVLKeys(env string) []string {
	if normalizeEnv(env) == "testnet" {
		return []string{xrplVLKeyAltnet}
	}

	return []string{xrplVLKeyRipple, xrplVLKeyXRPLF}
}

func xrplValidatorsOK(raw, env string) bool {
	if strings.Contains(raw, "[validator_list_threshold]") || xrplBareZeroLineRe.MatchString(raw) {
		return false
	}

	for _, k := range xrplRequiredVLKeys(env) {
		if !strings.Contains(raw, k) {
			return false
		}
	}

	return true
}

func writeXRPLValidatorsFile(etc, env string) error {
	etc = strings.TrimSpace(etc)
	if etc == "" {
		return nil
	}

	dest := filepath.Join(etc, "validators.txt")
	prev, _ := os.ReadFile(dest)
	if xrplValidatorsOK(string(prev), env) {
		return nil
	}

	if err := os.MkdirAll(etc, 0o755); err != nil {
		return err
	}

	if err := os.WriteFile(dest, []byte(xrplCanonicalValidators(env)), 0o644); err != nil {
		return err
	}

	_ = exec.Command("chown", "nodeop:nodeop", dest).Run()

	return nil
}
