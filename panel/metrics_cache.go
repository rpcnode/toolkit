package main

import (
	"log"

	"github.com/ali3/tron-toolkit/panel/store"
)

type ServerMetrics = store.ServerMetrics

type MetricsCache struct {
	db *store.DB
}

func NewMetricsCache(db *store.DB) *MetricsCache {
	return &MetricsCache{db: db}
}

func (c *MetricsCache) Upsert(m ServerMetrics) ServerMetrics {
	out, err := c.db.UpsertServerMetrics(m)
	if err != nil {
		log.Printf("metrics upsert: %v", err)
		return m
	}
	return out
}

func (c *MetricsCache) Get(serverID, agentURL string) (ServerMetrics, bool) {
	m, ok, err := c.db.GetServerMetrics(serverID, agentURL)
	if err != nil {
		log.Printf("metrics get: %v", err)
		return ServerMetrics{}, false
	}
	return m, ok
}

func metricsStatus(m ServerMetrics) string {
	return store.MetricsStatus(m)
}
