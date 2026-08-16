package store

import (
	"database/sql"
	"strings"
	"time"
)

const (
	MetaForceTick   = "force_tick"
	MetaLastTickAt  = "last_tick_at"
	MetaLastStats   = "last_stats"
	// CollectorStaleAfter — UI + docker watchdog treat the panel collector as dead.
	CollectorStaleAfter = 2 * time.Minute
)

// CollectorPulse is the last SQLite heartbeat from panel-collector.
type CollectorPulse struct {
	LastTickAt string `json:"last_tick_at"`
	AgeSec     int    `json:"age_sec"`
	Stale      bool   `json:"stale"`
	OK         bool   `json:"ok"`
}

func (db *DB) CollectorPulse() CollectorPulse {
	at, found, err := db.GetMeta(MetaLastTickAt)
	at = strings.TrimSpace(at)
	if err != nil || !found || at == "" {
		return CollectorPulse{Stale: true, AgeSec: -1}
	}
	t, err := time.Parse(time.RFC3339, at)
	if err != nil {
		t, err = time.Parse(time.RFC3339Nano, at)
	}
	if err != nil {
		return CollectorPulse{LastTickAt: at, Stale: true, AgeSec: -1}
	}
	age := time.Since(t)
	if age < 0 {
		age = 0
	}
	return CollectorPulse{
		LastTickAt: at,
		AgeSec:     int(age.Seconds()),
		Stale:      age > CollectorStaleAfter,
		OK:         true,
	}
}

// Hint is copy for the panel banner / API when the collector looks dead.
func (p CollectorPulse) Hint() string {
	if !p.Stale {
		return ""
	}
	return "Collector has not updated for more than 2 minutes. Docker panel-watchdog should restart rpcnode-panel-collector. Manual: docker restart rpcnode-panel-collector"
}

func (db *DB) SetMeta(key, value string) error {
	key = strings.TrimSpace(key)
	if key == "" {
		return nil
	}
	_, err := db.sql.Exec(`
INSERT INTO collector_meta(key, value) VALUES(?,?)
ON CONFLICT(key) DO UPDATE SET value=excluded.value`, key, value)
	return err
}

func (db *DB) GetMeta(key string) (string, bool, error) {
	key = strings.TrimSpace(key)
	if key == "" {
		return "", false, nil
	}
	var v string
	err := db.sql.QueryRow(`SELECT value FROM collector_meta WHERE key=?`, key).Scan(&v)
	if err == sql.ErrNoRows {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	return v, true, nil
}

// RequestForceTick asks the collector process to enqueue a full poll ASAP.
func (db *DB) RequestForceTick() error {
	return db.SetMeta(MetaForceTick, time.Now().UTC().Format(time.RFC3339Nano))
}

// ConsumeForceTick clears a pending force tick; returns true when one was set.
func (db *DB) ConsumeForceTick() (bool, error) {
	v, ok, err := db.GetMeta(MetaForceTick)
	if err != nil || !ok || strings.TrimSpace(v) == "" {
		return false, err
	}
	_, err = db.sql.Exec(`UPDATE collector_meta SET value='' WHERE key=?`, MetaForceTick)
	return true, err
}
