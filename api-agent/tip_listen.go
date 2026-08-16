package main

import (
	"strings"
	"sync"
)

var (
	rpcListenErrMu sync.Mutex
	rpcListenErr   string
)

func setRPCListenError(msg string) {
	rpcListenErrMu.Lock()
	defer rpcListenErrMu.Unlock()
	rpcListenErr = strings.TrimSpace(msg)
}

func rpcListenError() string {
	rpcListenErrMu.Lock()
	defer rpcListenErrMu.Unlock()
	return rpcListenErr
}
