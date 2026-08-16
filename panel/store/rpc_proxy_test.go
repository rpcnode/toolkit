package store

import "testing"

func TestParseRPCProxyJSON_IdleZerosValid(t *testing.T) {
	raw := `{"rps_1m":0,"rps_5m":0,"in_flight":0,"total":0,"latency_p50_ms":0,"latency_p95_ms":0,"errors_4xx":0,"errors_5xx":0,"upstream_errors":0,"http_502":0,"http_503":0}`
	st := ParseRPCProxyJSON(raw)
	if st == nil {
		t.Fatal("idle zero sample must parse (Fullnode Go RPC panel)")
	}
	if st.RPS1m != 0 || st.Total != 0 {
		t.Fatalf("%+v", st)
	}
}

func TestParseRPCProxyJSON_EmptyRejected(t *testing.T) {
	if ParseRPCProxyJSON("") != nil || ParseRPCProxyJSON("{}") != nil {
		t.Fatal("empty should be nil")
	}
}
