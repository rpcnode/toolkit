package main

import (
	"fmt"
	"strings"

	"github.com/ali3/tron-toolkit/panel/store"
)

// notifyTarget — identity lines for Telegram alerts.
type notifyTarget struct {
	Server   string // display name (fallback id)
	ServerID string
	NodeID   string // empty for host-only alerts
	NodeName string
	Network  string
	Env      string
}

func (c *collector) targetForNode(node store.Node) notifyTarget {
	t := notifyTarget{
		ServerID: strings.TrimSpace(node.ServerID),
		NodeID:   strings.TrimSpace(node.ID),
		NodeName: strings.TrimSpace(node.Name),
		Network:  strings.TrimSpace(node.Network),
		Env:      strings.TrimSpace(node.Env),
	}
	if t.NodeName == "" {
		t.NodeName = t.NodeID
	}
	t.Server = t.ServerID
	if t.ServerID != "" {
		if srv, ok, _ := c.db.GetServer(t.ServerID); ok {
			if n := strings.TrimSpace(srv.Name); n != "" {
				t.Server = n
			} else if id := strings.TrimSpace(srv.ID); id != "" {
				t.Server = id
			}
		}
	}
	if t.Server == "" {
		t.Server = "—"
	}
	return t
}

func (c *collector) targetForServer(srv store.Server) notifyTarget {
	name := strings.TrimSpace(srv.Name)
	if name == "" {
		name = strings.TrimSpace(srv.ID)
	}
	if name == "" {
		name = "—"
	}
	return notifyTarget{
		Server:   name,
		ServerID: strings.TrimSpace(srv.ID),
	}
}

// formatNotifyAlert — consistent Telegram body for all panel alerts (English only).
//
//	RpcNode · <event>
//
//	Server: …
//	Node: <uuid>
//	Name: …
//	Network / env: network / env
//
//	<title>
//	<details…>
func formatNotifyAlert(eventType string, t notifyTarget, title string, details ...string) string {
	eventType = strings.TrimSpace(eventType)
	title = strings.TrimSpace(title)
	var b strings.Builder
	b.WriteString("RpcNode")
	if eventType != "" {
		b.WriteString(" · ")
		b.WriteString(eventType)
	}
	b.WriteString("\n\n")

	server := strings.TrimSpace(t.Server)
	if server == "" {
		server = "—"
	}
	b.WriteString("Server: ")
	b.WriteString(server)
	if sid := strings.TrimSpace(t.ServerID); sid != "" && sid != server {
		b.WriteString(" (")
		b.WriteString(sid)
		b.WriteString(")")
	}
	b.WriteByte('\n')

	if nid := strings.TrimSpace(t.NodeID); nid != "" {
		b.WriteString("Node: ")
		b.WriteString(nid)
		if nn := strings.TrimSpace(t.NodeName); nn != "" && nn != nid {
			b.WriteString("\nName: ")
			b.WriteString(nn)
		}
		b.WriteByte('\n')
	} else {
		b.WriteString("Node: —\n")
	}

	net := strings.TrimSpace(t.Network)
	env := strings.TrimSpace(t.Env)
	switch {
	case net != "" && env != "":
		b.WriteString("Network / env: ")
		b.WriteString(net)
		b.WriteString(" / ")
		b.WriteString(env)
		b.WriteByte('\n')
	case net != "":
		b.WriteString("Network / env: ")
		b.WriteString(net)
		b.WriteByte('\n')
	case env != "":
		b.WriteString("Network / env: ")
		b.WriteString(env)
		b.WriteByte('\n')
	default:
		b.WriteString("Network / env: —\n")
	}

	b.WriteByte('\n')
	if title != "" {
		b.WriteString(title)
		b.WriteByte('\n')
	}
	for _, d := range details {
		d = strings.TrimSpace(d)
		if d == "" {
			continue
		}
		b.WriteString(d)
		b.WriteByte('\n')
	}
	return strings.TrimRight(b.String(), "\n")
}

func formatNotifyAlertf(eventType string, t notifyTarget, title, detailFmt string, args ...any) string {
	detail := ""
	if strings.TrimSpace(detailFmt) != "" {
		detail = fmt.Sprintf(detailFmt, args...)
	}
	return formatNotifyAlert(eventType, t, title, detail)
}
