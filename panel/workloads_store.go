package main

import (
	"log"

	"github.com/ali3/tron-toolkit/panel/store"
)

// WorkloadRef — chain node (env) on a server (SQLite-backed).
type WorkloadRef = store.Node

type WorkloadRegistry struct {
	db *store.DB
}

func NewWorkloadRegistry(db *store.DB) *WorkloadRegistry {
	return &WorkloadRegistry{db: db}
}

func (r *WorkloadRegistry) List() []WorkloadRef {
	items, err := r.db.ListNodes()
	if err != nil {
		log.Printf("workloads list: %v", err)
		return nil
	}
	return items
}

func (r *WorkloadRegistry) ListViews() []store.NodeView {
	items, err := r.db.ListNodeViews()
	if err != nil {
		log.Printf("workloads views: %v", err)
		return nil
	}
	return items
}

func (r *WorkloadRegistry) Get(id string) (WorkloadRef, bool) {
	n, ok, err := r.db.GetNode(id)
	if err != nil {
		log.Printf("workloads get: %v", err)
		return WorkloadRef{}, false
	}
	return n, ok
}

func (r *WorkloadRegistry) FindByServerEnv(serverID, env string) (WorkloadRef, bool) {
	n, ok, err := r.db.FindNodeByServerEnv(serverID, env)
	if err != nil {
		log.Printf("workloads find: %v", err)
		return WorkloadRef{}, false
	}
	return n, ok
}

func (r *WorkloadRegistry) FindByServerNetworkEnv(serverID, network, env string) (WorkloadRef, bool) {
	n, ok, err := r.db.FindNodeByServerNetworkEnv(serverID, network, env)
	if err != nil {
		log.Printf("workloads find network: %v", err)
		return WorkloadRef{}, false
	}
	return n, ok
}

func (r *WorkloadRegistry) Upsert(w WorkloadRef) WorkloadRef {
	out, err := r.db.UpsertNode(w)
	if err != nil {
		log.Printf("workloads upsert: %v", err)
		return w
	}
	return out
}

func (r *WorkloadRegistry) Delete(id string) bool {
	ok, err := r.db.DeleteNode(id)
	if err != nil {
		log.Printf("workloads delete: %v", err)
		return false
	}
	return ok
}

// SetDiskLayout persists confirmed multi-disk layout for a node UUID.
func (r *WorkloadRegistry) SetDiskLayout(id string, layout map[string]any) error {
	if err := r.db.SetNodeDiskLayout(id, layout); err != nil {
		log.Printf("workloads disk_layout: %v", err)
		return err
	}
	return nil
}

func (r *WorkloadRegistry) CountByServerID(serverID string) int {
	n, err := r.db.CountNodesByServer(serverID)
	if err != nil {
		log.Printf("workloads count: %v", err)
		return 0
	}
	return n
}

func (r *WorkloadRegistry) IDsByServerID(serverID string) []string {
	ids, err := r.db.NodeIDsByServer(serverID)
	if err != nil {
		log.Printf("workloads ids: %v", err)
		return nil
	}
	return ids
}
