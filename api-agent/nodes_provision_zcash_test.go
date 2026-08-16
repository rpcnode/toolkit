package main

import (
	"strings"
	"testing"
)

func TestRenderZebradTomlMainnet(t *testing.T) {
	body := renderZebradToml("mainnet", "/data/zcash/mainnet", 8232, 8233)
	for _, want := range []string{
		`network = "Mainnet"`,
		`listen_addr = "0.0.0.0:8233"`,
		`cache_dir = "/data/zcash/mainnet"`,
		`listen_addr = "127.0.0.1:8232"`,
		`enable_cookie_auth = false`,
	} {
		if !strings.Contains(body, want) {
			t.Fatalf("missing %q in:\n%s", want, body)
		}
	}
	if strings.Contains(body, "testnet=1") || strings.Contains(body, "rpcuser=") {
		t.Fatal("must not emit zcashd-style conf knobs")
	}
}

func TestRenderZebradTomlTestnet(t *testing.T) {
	body := renderZebradToml("testnet", "/data/zcash/testnet", 18232, 18233)
	if !strings.Contains(body, `network = "Testnet"`) {
		t.Fatalf("want Testnet:\n%s", body)
	}
	if !strings.Contains(body, `127.0.0.1:18232`) || !strings.Contains(body, `0.0.0.0:18233`) {
		t.Fatalf("want testnet ports:\n%s", body)
	}
}

func TestRenderZebradUnit(t *testing.T) {
	u := renderZebradUnit(networkPortProfile{
		Env: "mainnet", OptPath: "/opt/zcash/mainnet",
	}, "/etc/zcash/mainnet/zebrad.toml")
	if !strings.Contains(u, "zebrad --config /etc/zcash/mainnet/zebrad.toml start") {
		t.Fatalf("unit:\n%s", u)
	}
	if strings.Contains(u, "zcashd") || strings.Contains(u, "zcash-fetch-params") {
		t.Fatal("unit must not reference zcashd / fetch-params")
	}
	if !strings.Contains(u, "LimitNOFILE=1048576") {
		t.Fatal("missing LimitNOFILE")
	}
	if !strings.Contains(u, "IPAccounting=yes") {
		t.Fatal("missing IPAccounting")
	}
}
