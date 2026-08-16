package main

import (
	"testing"
)

func TestCatalogPortRolesNileIncludesInternal(t *testing.T) {
	prof := lookupPortProfile("tron", "nile")
	roles := catalogPortRoles(prof)
	want := map[int]string{
		39091: "public_port",
		39191: "agent_port",
		18091: "node_http_port",
		18889: "p2p_port",
		18290: "sol_http_port",
		50151: "grpc_port",
		9528:  "metrics_port",
	}
	got := map[int]string{}
	for _, r := range roles {
		if r.Port > 0 {
			got[r.Port] = r.Role
		}
	}
	for port, role := range want {
		if got[port] != role {
			t.Fatalf("port %d: got %q want %q (got=%v)", port, got[port], role, got)
		}
	}
	ext := catalogExternalPorts(prof)
	if len(ext) != 3 || ext[0] != 39091 || ext[2] != 18889 {
		t.Fatalf("external=%v", ext)
	}
}
