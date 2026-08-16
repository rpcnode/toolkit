package store

import "testing"

func TestParseNodeNetFromStatusJSON(t *testing.T) {
	raw := `{
	  "node_net": {
	    "node_net_rx_mbps": 12.5,
	    "node_net_tx_mbps": 3.1,
	    "node_net_rx_bytes": 1048576
	  }
	}`
	st := ParseNodeNetFromStatusJSON(raw)
	if st == nil || st.RxMbps != 12.5 || st.TxMbps != 3.1 || st.RxBytes != 1048576 {
		t.Fatalf("%+v", st)
	}
	st2 := ParseNodeNetFromStatusJSON(`{"host_metrics":{"current":{"node_net_rx_mbps":1,"node_net_tx_mbps":2}}}`)
	if st2 == nil || st2.RxMbps != 1 || st2.TxMbps != 2 {
		t.Fatalf("host_metrics: %+v", st2)
	}
	if ParseNodeNetFromStatusJSON("") != nil {
		t.Fatal("empty")
	}
}
