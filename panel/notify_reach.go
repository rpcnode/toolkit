package main

import (
	"sync"
	"time"
)

// Reachability hysteresis — avoid Telegram flap on a single collector timeout.
// node.down / node.up fire only after the bad/good sample holds continuously.
var notifyReachHold sync.Map // edgeKey → reachSample

type reachSample struct {
	BadSince  time.Time
	GoodSince time.Time
}

type reachDecision struct {
	BadSince  time.Time
	GoodSince time.Time
	Emit      string // "down", "up", or ""
}

func evaluateReachHold(prev reachSample, bad bool, now time.Time, downHold, upHold time.Duration) reachDecision {
	if downHold <= 0 {
		downHold = defaultNodeDownHold
	}
	if upHold <= 0 {
		upHold = defaultNodeUpHold
	}
	out := reachDecision{}
	if bad {
		out.GoodSince = time.Time{}
		if prev.BadSince.IsZero() {
			out.BadSince = now
		} else {
			out.BadSince = prev.BadSince
		}
		if !out.BadSince.IsZero() && !now.Before(out.BadSince.Add(downHold)) {
			out.Emit = "down"
		}
		return out
	}
	out.BadSince = time.Time{}
	if prev.GoodSince.IsZero() {
		out.GoodSince = now
	} else {
		out.GoodSince = prev.GoodSince
	}
	if !out.GoodSince.IsZero() && !now.Before(out.GoodSince.Add(upHold)) {
		out.Emit = "up"
	}
	return out
}

func observeReachHold(edgeKey string, bad bool, downHold, upHold time.Duration) string {
	prev := reachSample{}
	if v, ok := notifyReachHold.Load(edgeKey); ok {
		if s, ok := v.(reachSample); ok {
			prev = s
		}
	}
	dec := evaluateReachHold(prev, bad, time.Now(), downHold, upHold)
	notifyReachHold.Store(edgeKey, reachSample{BadSince: dec.BadSince, GoodSince: dec.GoodSince})
	return dec.Emit
}
