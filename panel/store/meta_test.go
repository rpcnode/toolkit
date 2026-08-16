package store

import (
	"testing"
	"time"
)

func TestCollectorPulse_EmptyIsStale(t *testing.T) {
	db := openTestDB(t)
	p := db.CollectorPulse()
	if !p.Stale {
		t.Fatal("empty last_tick_at must be stale")
	}
	if p.OK {
		t.Fatal("empty pulse must not be ok")
	}
	if p.Hint() == "" {
		t.Fatal("stale pulse needs a hint")
	}
}

func TestCollectorPulse_FreshAndStale(t *testing.T) {
	db := openTestDB(t)
	if err := db.SetMeta(MetaLastTickAt, time.Now().UTC().Format(time.RFC3339)); err != nil {
		t.Fatal(err)
	}
	fresh := db.CollectorPulse()
	if fresh.Stale || !fresh.OK {
		t.Fatalf("fresh pulse stale=%v ok=%v age=%d", fresh.Stale, fresh.OK, fresh.AgeSec)
	}
	if fresh.Hint() != "" {
		t.Fatalf("fresh hint=%q", fresh.Hint())
	}

	old := time.Now().UTC().Add(-3 * time.Minute).Format(time.RFC3339)
	if err := db.SetMeta(MetaLastTickAt, old); err != nil {
		t.Fatal(err)
	}
	stale := db.CollectorPulse()
	if !stale.Stale || !stale.OK {
		t.Fatalf("old pulse stale=%v ok=%v age=%d", stale.Stale, stale.OK, stale.AgeSec)
	}
	if stale.AgeSec < 120 {
		t.Fatalf("age_sec=%d want >=120", stale.AgeSec)
	}
}
