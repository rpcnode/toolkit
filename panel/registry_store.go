package main

import (
	"log"

	"github.com/ali3/tron-toolkit/panel/store"
)

// NodeRef — host agent registered in the panel (SQLite-backed).
type NodeRef = store.Server

type NodeRegistry struct {
	db *store.DB
}

func NewNodeRegistry(db *store.DB) *NodeRegistry {
	return &NodeRegistry{db: db}
}

func (r *NodeRegistry) List() []NodeRef {
	items, err := r.db.ListServers(true)
	if err != nil {
		log.Printf("registry list: %v", err)
		return nil
	}
	return items
}

func (r *NodeRegistry) HasAgentKey(token string) bool {
	ok, err := r.db.HasAgentKey(token)
	if err != nil {
		log.Printf("registry has key: %v", err)
		return false
	}
	return ok
}

func (r *NodeRegistry) FindIDByAgentURLOrToken(agentURL, token string) string {
	id, err := r.db.FindServerIDByAgentURLOrToken(agentURL, token)
	if err != nil {
		log.Printf("registry find: %v", err)
		return ""
	}
	return id
}

func (r *NodeRegistry) Get(id string) (NodeRef, bool) {
	s, ok, err := r.db.GetServer(id)
	if err != nil {
		log.Printf("registry get: %v", err)
		return NodeRef{}, false
	}
	return s, ok
}

func (r *NodeRegistry) Upsert(n NodeRef) NodeRef {
	out, err := r.db.UpsertServer(n)
	if err != nil {
		log.Printf("registry upsert: %v", err)
		n.AgentKey = ""
		return n
	}
	return out
}

func (r *NodeRegistry) SetAgentVersion(id, version string) {
	if r == nil || r.db == nil {
		return
	}
	if err := r.db.SetServerAgentVersion(id, version); err != nil {
		log.Printf("registry set agent_version %s: %v", id, err)
	}
}

func (r *NodeRegistry) Delete(id string) bool {
	ok, err := r.db.DeleteServer(id)
	if err != nil {
		log.Printf("registry delete: %v", err)
		return false
	}
	return ok
}
