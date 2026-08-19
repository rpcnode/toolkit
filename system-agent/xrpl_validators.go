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

// xrplCanonicalValidators — UNL only. ❌ [validator_list_threshold] 0: xrpld
// treats a stray "0" as a publisher key → FTL "Invalid validator list publisher key: 0".
func xrplCanonicalValidators(env string) string {
	if normalizeEnvName(env) == "testnet" {
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
	if normalizeEnvName(env) == "testnet" {
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

func healXRPLValidatorsFile(etc, env string) (bool, error) {
	etc = strings.TrimSpace(etc)
	if etc == "" {
		return false, nil
	}

	dest := filepath.Join(etc, "validators.txt")
	prev, _ := os.ReadFile(dest)
	if xrplValidatorsOK(string(prev), env) {
		return false, nil
	}

	if err := os.MkdirAll(etc, 0o755); err != nil {
		return false, err
	}

	body := xrplCanonicalValidators(env)
	if err := os.WriteFile(dest, []byte(body), 0o644); err != nil {
		return false, err
	}

	_ = exec.Command("chown", "nodeop:nodeop", dest).Run()

	return true, nil
}
