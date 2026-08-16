package main

import (
	"fmt"
	"net"
	"path/filepath"
	"strings"
	"time"
)

// HostIP — candidate address for TRON_PUBLIC_BASE / TRON_PANEL_BASE.
type HostIP struct {
	IP        string `json:"ip"`
	Interface string `json:"iface,omitempty"`
	Source    string `json:"source"` // route | iface | hostname
	Primary   bool   `json:"primary,omitempty"`
}

type HostNetInfo struct {
	Hostname  string   `json:"hostname"`
	PrimaryIP string   `json:"primary_ip,omitempty"`
	IPs       []HostIP `json:"ips"`
}

func detectHostNetInfo() HostNetInfo {
	info := HostNetInfo{
		Hostname: hostname(),
		IPs:      []HostIP{},
	}

	seen := map[string]bool{}
	add := func(ip, iface, source string, primary bool) {
		ip = strings.TrimSpace(ip)
		if ip == "" || seen[ip] {
			return
		}
		if !isUsableIPv4(ip) {
			return
		}
		seen[ip] = true
		info.IPs = append(info.IPs, HostIP{
			IP: ip, Interface: iface, Source: source, Primary: primary,
		})
		if primary && info.PrimaryIP == "" {
			info.PrimaryIP = ip
		}
	}

	// Prefer default-route src (LAN / egress IP).
	if ip := detectPublicIP(); ip != "" {
		add(ip, "", "route", true)
	}

	ifaces, err := net.Interfaces()
	if err == nil {
		for _, iface := range ifaces {
			if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
				continue
			}
			name := iface.Name
			if isIgnoredIface(name) {
				continue
			}
			addrs, err := iface.Addrs()
			if err != nil {
				continue
			}
			for _, a := range addrs {
				ip := ipFromAddr(a)
				if ip == "" {
					continue
				}
				add(ip, name, "iface", false)
			}
		}
	}

	if h := info.Hostname; h != "" && h != "unknown" {
		if ips, err := net.LookupIP(h); err == nil {
			for _, ip := range ips {
				v4 := ip.To4()
				if v4 == nil {
					continue
				}
				add(v4.String(), "", "hostname", false)
			}
		}
	}

	if info.PrimaryIP == "" && len(info.IPs) > 0 {
		info.IPs[0].Primary = true
		info.PrimaryIP = info.IPs[0].IP
	}

	return info
}

func isIgnoredIface(name string) bool {
	n := strings.ToLower(name)
	switch {
	case n == "docker0", n == "lo", n == "lo0":
		return true
	case strings.HasPrefix(n, "br-"),
		strings.HasPrefix(n, "veth"),
		strings.HasPrefix(n, "virbr"),
		strings.HasPrefix(n, "vmnet"),
		strings.HasPrefix(n, "cni"),
		strings.HasPrefix(n, "flannel"),
		strings.HasPrefix(n, "tun"),
		strings.HasPrefix(n, "tap"),
		strings.HasPrefix(n, "wg"),
		strings.HasPrefix(n, "utun"):
		return true
	default:
		return false
	}
}

func isUsableIPv4(ip string) bool {
	parsed := net.ParseIP(ip)
	if parsed == nil {
		return false
	}
	v4 := parsed.To4()
	if v4 == nil {
		return false
	}
	if v4.IsLoopback() || v4.IsUnspecified() || v4.IsMulticast() || v4.IsLinkLocalUnicast() {
		return false
	}
	// Drop Docker default bridge 172.17.0.0/16.
	if v4[0] == 172 && v4[1] == 17 {
		return false
	}
	return true
}

func ipFromAddr(a net.Addr) string {
	switch v := a.(type) {
	case *net.IPNet:
		if v.IP == nil {
			return ""
		}
		if v4 := v.IP.To4(); v4 != nil {
			return v4.String()
		}
	case *net.IPAddr:
		if v.IP == nil {
			return ""
		}
		if v4 := v.IP.To4(); v4 != nil {
			return v4.String()
		}
	}
	return ""
}

// hostNetForStatus — map suitable for status.host merge.
func hostNetForStatus() map[string]any {
	info := detectHostNetInfo()
	ips := make([]map[string]any, 0, len(info.IPs))
	for _, h := range info.IPs {
		m := map[string]any{
			"ip": h.IP, "source": h.Source, "primary": h.Primary,
		}
		if h.Interface != "" {
			m["iface"] = h.Interface
		}
		ips = append(ips, m)
	}
	osName, arch, _ := liveUname()
	return map[string]any{
		"hostname":    info.Hostname,
		"primary_ip":  info.PrimaryIP,
		"ips":         ips,
		"os":          osName,
		"arch":        arch,
		"detected_at": time.Now().UTC().Format(time.RFC3339),
	}
}

func publicBaseOverridePath(cfg Config) string {
	dir := filepath.Dir(cfg.StateFile)
	if dir == "" || dir == "." {
		dir = "/var/lib/rpcnode/tron-" + cfg.Env
	}
	return filepath.Join(dir, "public-base.json")
}

// effectivePublicBases — override file wins, then env, then auto-detect.
func effectivePublicBases(cfg Config) (rpcBase, panelBase string) {
	ov := readJSONFile(publicBaseOverridePath(cfg))
	if pb, _ := ov["public_base"].(string); strings.TrimSpace(pb) != "" {
		rpcBase = strings.TrimRight(strings.TrimSpace(pb), "/")
	} else {
		rpcBase = strings.TrimRight(cfg.PublicBase, "/")
	}
	if pb, _ := ov["panel_base"].(string); strings.TrimSpace(pb) != "" {
		panelBase = strings.TrimRight(strings.TrimSpace(pb), "/")
	} else {
		panelBase = strings.TrimRight(cfg.PanelBase, "/")
	}

	if rpcBase == "" {
		rpcPort := cfg.PublicRPCPort()
		if ip := detectPublicIP(); ip != "" {
			rpcBase = fmt.Sprintf("http://%s:%d", ip, rpcPort)
		} else {
			rpcBase = fmt.Sprintf("http://127.0.0.1:%d", rpcPort)
		}
	}
	if panelBase == "" {
		panelBase = swapURLPort(rpcBase, cfg.PanelPort)
	}
	if panelBase == "" {
		panelBase = fmt.Sprintf("http://127.0.0.1:%d", cfg.PanelPort)
	}
	return rpcBase, panelBase
}
