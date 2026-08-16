package main

import (
	"testing"
	"time"
)

func TestEvaluateReachHoldTransientTimeoutNoAlert(t *testing.T) {
	now := time.Date(2026, 8, 12, 0, 0, 0, 0, time.UTC)
	downHold := 45 * time.Second
	upHold := 20 * time.Second

	// First bad sample — start clock, no emit.
	d1 := evaluateReachHold(reachSample{}, true, now, downHold, upHold)
	if d1.Emit != "" || d1.BadSince.IsZero() {
		t.Fatalf("first bad: %+v", d1)
	}
	// Recover after 5s — no down fired, up emit only after upHold (notifyEdge suppresses if never down).
	d2 := evaluateReachHold(reachSample{BadSince: d1.BadSince}, false, now.Add(5*time.Second), downHold, upHold)
	if d2.Emit != "" || !d2.BadSince.IsZero() || d2.GoodSince.IsZero() {
		t.Fatalf("brief recover: %+v", d2)
	}
	// Still good at 5s — no up yet.
	d3 := evaluateReachHold(reachSample{GoodSince: d2.GoodSince}, false, now.Add(10*time.Second), downHold, upHold)
	if d3.Emit != "" {
		t.Fatalf("early good emit: %+v", d3)
	}
}

func TestEvaluateReachHoldConfirmedDownThenUp(t *testing.T) {
	now := time.Date(2026, 8, 12, 0, 0, 0, 0, time.UTC)
	downHold := 45 * time.Second
	upHold := 20 * time.Second

	start := evaluateReachHold(reachSample{}, true, now, downHold, upHold)
	mid := evaluateReachHold(reachSample{BadSince: start.BadSince}, true, now.Add(30*time.Second), downHold, upHold)
	if mid.Emit != "" {
		t.Fatalf("mid bad should wait: %+v", mid)
	}
	down := evaluateReachHold(reachSample{BadSince: start.BadSince}, true, now.Add(45*time.Second), downHold, upHold)
	if down.Emit != "down" {
		t.Fatalf("want down at hold: %+v", down)
	}

	good0 := evaluateReachHold(reachSample{BadSince: down.BadSince}, false, now.Add(50*time.Second), downHold, upHold)
	if good0.Emit != "" || good0.GoodSince.IsZero() {
		t.Fatalf("first good after down: %+v", good0)
	}
	up := evaluateReachHold(reachSample{GoodSince: good0.GoodSince}, false, now.Add(70*time.Second), downHold, upHold)
	if up.Emit != "up" {
		t.Fatalf("want up after hold: %+v", up)
	}
}

func TestEvaluateReachHoldBadResetsGoodClock(t *testing.T) {
	now := time.Date(2026, 8, 12, 0, 0, 0, 0, time.UTC)
	downHold := 45 * time.Second
	upHold := 20 * time.Second

	g0 := evaluateReachHold(reachSample{}, false, now, downHold, upHold)
	// Flap bad before upHold completes.
	b := evaluateReachHold(reachSample{GoodSince: g0.GoodSince}, true, now.Add(10*time.Second), downHold, upHold)
	if b.Emit != "" || b.BadSince.IsZero() || !b.GoodSince.IsZero() {
		t.Fatalf("bad after short good: %+v", b)
	}
	// New good streak must restart from now (not old GoodSince).
	g1 := evaluateReachHold(reachSample{BadSince: b.BadSince}, false, now.Add(12*time.Second), downHold, upHold)
	if g1.GoodSince != now.Add(12*time.Second) {
		t.Fatalf("good clock should restart: %+v", g1)
	}
}
