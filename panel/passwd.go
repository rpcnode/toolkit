package main

import (
	"bufio"
	"fmt"
	"os"
	"strings"
)

// runPasswdMain — `rpcnode-panel passwd <login> [--password SECRET]`
// Updates bcrypt panel.htpasswd for forgotten / rotated human passwords.
func runPasswdMain(args []string) {
	user := ""
	pass := ""
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--password", "-p":
			if i+1 >= len(args) {
				fatalPasswd("missing value for --password")
			}
			pass = args[i+1]
			i++
		case "--help", "-h":
			fmt.Print(`rpcnode-panel passwd — set panel login password (htpasswd)

Usage:
  rpcnode-panel passwd <login> --password 'new-secret'
  PANEL_PASS='new-secret' rpcnode-panel passwd <login>

Docker (standalone panel, container_name=rpcnode-panel):
  docker exec rpcnode-panel rpcnode-panel passwd admin --password 'new-secret'
  docker restart rpcnode-panel   # drop in-memory sessions

Env: PANEL_HTPASSWD (default /etc/rpcnode/panel.htpasswd), PANEL_SESSIONS, PANEL_PASS
`)
			return
		default:
			if strings.HasPrefix(args[i], "-") {
				fatalPasswd("unknown flag: " + args[i])
			}
			if user == "" {
				user = args[i]
			} else {
				fatalPasswd("unexpected arg: " + args[i])
			}
		}
	}
	user = strings.TrimSpace(user)
	if user == "" {
		fatalPasswd("usage: rpcnode-panel passwd <login> --password SECRET")
	}
	if pass == "" {
		pass = strings.TrimSpace(os.Getenv("PANEL_PASS"))
	}
	if pass == "" {
		fmt.Fprint(os.Stderr, "New password (min 8 chars): ")
		line, err := bufio.NewReader(os.Stdin).ReadString('\n')
		if err != nil {
			fatalPasswd(err.Error())
		}
		pass = strings.TrimSpace(line)
	}
	cfg := loadConfig()
	auth := NewPanelAuth(cfg.HtpasswdPath)
	if err := auth.SetPassword(user, pass); err != nil {
		fatalPasswd(err.Error())
	}
	revoked := 0
	if cfg.SessionPath != "" {
		sessions := NewSessionStore(cfg.SessionPath)
		revoked = sessions.RevokeUser(user)
	}
	fmt.Printf("ok: password set for user=%s htpasswd=%s sessions_revoked_on_disk=%d\n",
		user, auth.htpasswdPath(), revoked)
	fmt.Println("note: running panel reloads htpasswd ~every 5s; restart container to drop in-memory sessions:")
	fmt.Println("  docker restart rpcnode-panel")
}

func fatalPasswd(msg string) {
	fmt.Fprintln(os.Stderr, "ERROR:", msg)
	os.Exit(1)
}
