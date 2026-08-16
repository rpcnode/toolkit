package store

import (
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// ImportLegacyJSON loads panel-nodes.json / panel-workloads.json / metrics when DB is empty.
func (db *DB) ImportLegacyJSON(dir string) error {
	n, err := db.ServerCount()
	if err != nil {
		return err
	}
	if n > 0 {
		return nil
	}
	dir = strings.TrimSpace(dir)
	if dir == "" {
		dir = filepath.Dir(db.path)
	}

	serversPath := filepath.Join(dir, "panel-nodes.json")
	workloadsPath := filepath.Join(dir, "panel-workloads.json")
	metricsPath := filepath.Join(dir, "panel-server-metrics.json")

	imported := 0
	if b, err := os.ReadFile(serversPath); err == nil {
		var doc struct {
			Nodes map[string]Server `json:"nodes"`
		}
		if json.Unmarshal(b, &doc) == nil {
			for id, s := range doc.Nodes {
				if s.ID == "" {
					s.ID = id
				}
				if _, err := db.UpsertServer(s); err != nil {
					log.Printf("store import server %s: %v", id, err)
					continue
				}
				imported++
			}
		}
	}

	if b, err := os.ReadFile(workloadsPath); err == nil {
		var doc struct {
			Items map[string]Node `json:"items"`
		}
		if json.Unmarshal(b, &doc) == nil {
			for id, w := range doc.Items {
				if w.ID == "" {
					w.ID = id
				}
				// Ensure server exists (FK).
				if _, ok, _ := db.GetServer(w.ServerID); !ok && w.ServerID != "" {
					_, _ = db.UpsertServer(Server{
						ID: w.ServerID, Name: w.ServerID, Network: w.Network, Env: w.Env,
						CreatedAt: time.Now().UTC(), UpdatedAt: time.Now().UTC(),
					})
				}
				if _, err := db.UpsertNode(w); err != nil {
					log.Printf("store import node %s: %v", id, err)
				}
			}
		}
	}

	if b, err := os.ReadFile(metricsPath); err == nil {
		var doc struct {
			Items map[string]ServerMetrics `json:"items"`
		}
		if json.Unmarshal(b, &doc) == nil {
			for key, m := range doc.Items {
				if m.ServerID == "" {
					m.ServerID = key
				}
				if _, err := db.UpsertServerMetrics(m); err != nil {
					log.Printf("store import metrics %s: %v", key, err)
				}
			}
		}
	}

	if imported > 0 {
		log.Printf("store: imported %d server(s) from legacy JSON in %s", imported, dir)
	}
	return nil
}
