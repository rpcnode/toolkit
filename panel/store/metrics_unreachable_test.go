package store

import "testing"

func TestMarkNodeUnreachablePreservesRawJSON(t *testing.T) {
	db := openTestDB(t)

	srv, err := db.UpsertServer(Server{
		ID: "srv-1", Name: "s", Network: "bitcoin", Env: "mainnet",
		AgentURL: "http://127.0.0.1:39390", AgentKey: "k",
	})
	if err != nil {
		t.Fatal(err)
	}
	node, err := db.UpsertNode(Node{
		ID: "9295c148-98ce-452a-bf19-140b447af57c", ServerID: srv.ID,
		Network: "bitcoin", Env: "mainnet", AgentPort: 39390, Status: "syncing",
	})
	if err != nil {
		t.Fatal(err)
	}

	raw := `{"ok":true,"lifecycle":{"phase":"syncing","label":"IBD","detail":"blocks 100"},"rpc":{"node_height":100}}`
	h := int64(100)
	if err := db.UpsertNodeStatus(NodeStatus{
		NodeID: node.ID, Phase: "syncing", Label: "IBD", Detail: "blocks 100",
		Height: &h, Health: "ok", RawJSON: raw,
	}); err != nil {
		t.Fatal(err)
	}

	if err := db.MarkNodeUnreachable(node.ID, "connection refused"); err != nil {
		t.Fatal(err)
	}

	st, ok, err := db.GetNodeStatus(node.ID)
	if err != nil || !ok {
		t.Fatalf("get: ok=%v err=%v", ok, err)
	}
	if st.Phase != "syncing" || st.Label != "IBD" {
		t.Fatalf("wiped summary: phase=%q label=%q", st.Phase, st.Label)
	}
	if st.RawJSON != raw {
		t.Fatalf("raw_json wiped: %q", st.RawJSON)
	}
	if st.Height == nil || *st.Height != 100 {
		t.Fatalf("height wiped: %v", st.Height)
	}
	if st.Error != "connection refused" {
		t.Fatalf("error=%q", st.Error)
	}
}

func TestMarkNodeUnreachableInsertsStubWhenEmpty(t *testing.T) {
	db := openTestDB(t)

	srv, err := db.UpsertServer(Server{
		ID: "srv-1", Name: "s", Network: "tron", Env: "mainnet",
		AgentURL: "http://127.0.0.1:39190", AgentKey: "k",
	})
	if err != nil {
		t.Fatal(err)
	}
	node, err := db.UpsertNode(Node{
		ID: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", ServerID: srv.ID,
		Network: "tron", Env: "mainnet", Status: "online",
	})
	if err != nil {
		t.Fatal(err)
	}

	if err := db.MarkNodeUnreachable(node.ID, "dial tcp timeout"); err != nil {
		t.Fatal(err)
	}
	st, ok, err := db.GetNodeStatus(node.ID)
	if err != nil || !ok {
		t.Fatalf("get: ok=%v err=%v", ok, err)
	}
	if st.Phase != "error" || st.Error != "dial tcp timeout" {
		t.Fatalf("stub=%+v", st)
	}
}
