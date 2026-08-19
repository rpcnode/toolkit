package main

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

func guessArtifactKind(kind, url string) string {
	k := strings.ToLower(strings.TrimSpace(kind))
	u := strings.ToLower(strings.TrimSpace(url))
	switch k {
	case "jar", "bin", "binary", "tarball", "archive", "zip", "apt", "docker_extract":
		if k == "binary" {
			return "bin"
		}
		if k == "archive" {
			return "tarball"
		}
		return k
	}
	switch {
	case strings.HasPrefix(u, "docker:") || strings.HasPrefix(u, "apt:"):
		if strings.HasPrefix(u, "apt:") {
			return "apt"
		}
		return "docker_extract"
	case strings.HasSuffix(u, ".jar"):
		return "jar"
	case strings.HasSuffix(u, ".zip"):
		return "zip"
	case strings.Contains(u, ".tar.") || strings.HasSuffix(u, ".tgz"):
		return "tarball"
	default:
		return "bin"
	}
}

func (c *ClientUpdateController) optDir() string {
	opt := strings.TrimSpace(c.cfg.OptDir)
	if opt == "" {
		opt = LookupNetworkProfile(c.cfg.Network, c.cfg.Env).OptPath
	}
	return opt
}

func (c *ClientUpdateController) clientJarPath() string {
	return c.tronJarPath()
}

func (c *ClientUpdateController) unitExecPath() string {
	for _, u := range cfgNodeUnits(c.cfg) {
		out, err := exec.Command("systemctl", "show", u+".service", "-p", "ExecStart", "--value").Output()
		if err != nil {
			continue
		}
		s := string(out)
		if i := strings.Index(s, "path="); i >= 0 {
			rest := s[i+5:]
			if j := strings.IndexAny(rest, " ;"); j >= 0 {
				rest = rest[:j]
			}
			p := strings.TrimSpace(rest)
			if p != "" && fileExists(p) {
				return p
			}
		}
	}
	return ""
}

func (c *ClientUpdateController) clientBinPath() string {
	if p := c.unitExecPath(); p != "" {
		return p
	}
	opt := c.optDir()
	hint := strings.TrimSpace(LookupNetworkProfile(c.cfg.Network, c.cfg.Env).NodeBinaryHint)
	if hint != "" && hint != "java-tron" {
		for _, cand := range []string{
			filepath.Join(opt, "bin", hint),
			"/usr/local/bin/" + hint,
		} {
			if fileExists(cand) {
				if dest, err := filepath.EvalSymlinks(cand); err == nil && dest != "" {
					return dest
				}
				return cand
			}
		}
	}
	entries, _ := os.ReadDir(filepath.Join(opt, "bin"))
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		p := filepath.Join(opt, "bin", e.Name())
		if st, err := os.Stat(p); err == nil && st.Mode()&0111 != 0 {
			return p
		}
	}
	if hint != "" && hint != "java-tron" {
		return filepath.Join(opt, "bin", hint)
	}
	return filepath.Join(opt, "bin", strings.ToLower(strings.TrimSpace(c.cfg.Network)))
}

func (c *ClientUpdateController) downloadVerified(url, dest string, mode os.FileMode, wantSHA string) error {
	if url == "" {
		return fmt.Errorf("empty artifact_url")
	}
	_ = ensureDir(filepath.Dir(dest))
	tmp := dest + ".tmp"
	client := &http.Client{Timeout: 30 * time.Minute}
	resp, err := client.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return fmt.Errorf("download HTTP %d", resp.StatusCode)
	}
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, mode)
	if err != nil {
		return err
	}
	h := sha256.New()
	if _, err := io.Copy(io.MultiWriter(f, h), resp.Body); err != nil {
		_ = f.Close()
		_ = os.Remove(tmp)
		return err
	}
	_ = f.Close()
	sum := hex.EncodeToString(h.Sum(nil))
	if want := strings.ToLower(strings.TrimSpace(wantSHA)); want != "" && want != sum {
		_ = os.Remove(tmp)
		return fmt.Errorf("sha256 mismatch: got %s want %s", sum, want)
	}
	bak := dest + ".bak"
	_ = os.Rename(dest, bak)
	if err := os.Rename(tmp, dest); err != nil {
		_ = os.Rename(bak, dest)
		return err
	}
	_ = os.Chmod(dest, mode)
	return nil
}

func (c *ClientUpdateController) refreshClientLinks(dest string) {
	dest = strings.TrimSpace(dest)
	if dest == "" || !fileExists(dest) {
		return
	}
	net := strings.ToLower(strings.TrimSpace(c.cfg.Network))
	links := []string{"/usr/local/bin/" + net + "-" + filepath.Base(dest)}
	if net == "bsc" && filepath.Base(dest) == "geth" {
		links = append(links, "/usr/local/bin/bsc-geth")
	}
	for _, link := range links {
		if !fileExists(link) && !isSymlink(link) {
			if net != "bsc" {
				continue
			}
		}
		_ = ensureDir(filepath.Dir(link))
		_ = os.Remove(link)
		_ = os.Symlink(dest, link)
	}
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	_ = ensureDir(filepath.Dir(dst))
	tmp := dst + ".tmp"
	out, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0755)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		_ = out.Close()
		_ = os.Remove(tmp)
		return err
	}
	if err := out.Close(); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	_ = os.Rename(dst, dst+".bak")
	if err := os.Rename(tmp, dst); err != nil {
		_ = os.Rename(dst+".bak", dst)
		return err
	}
	return nil
}

func isSymlink(path string) bool {
	st, err := os.Lstat(path)
	return err == nil && st.Mode()&os.ModeSymlink != 0
}

func (c *ClientUpdateController) installFromArchive(man clientManifest) error {
	url := man.urlForHost()
	if url == "" {
		return fmt.Errorf("empty artifact_url")
	}
	tmp, err := os.MkdirTemp("", "rpcnode-client-*")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmp)

	archive := filepath.Join(tmp, "artifact")
	if err := c.downloadVerified(url, archive, 0644, man.shaForHost()); err != nil {
		return err
	}
	unpack := filepath.Join(tmp, "unpack")
	_ = os.MkdirAll(unpack, 0o755)
	kind := guessArtifactKind(man.ArtifactKind, url)
	var cmd *exec.Cmd
	if kind == "zip" {
		cmd = exec.Command("unzip", "-q", "-o", archive, "-d", unpack)
	} else {
		cmd = exec.Command("tar", "-xaf", archive, "-C", unpack)
	}
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("extract: %v (%s)", err, strings.TrimSpace(string(out)))
	}

	optBin := filepath.Join(c.optDir(), "bin")
	copied := 0
	_ = filepath.WalkDir(unpack, func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		name := d.Name()
		low := strings.ToLower(name)
		if strings.HasPrefix(name, ".") || strings.HasSuffix(low, ".so") || strings.Contains(low, ".so.") {
			return nil
		}
		if strings.HasPrefix(low, "readme") || strings.HasPrefix(low, "license") || strings.HasPrefix(low, "changelog") {
			return nil
		}
		rel, _ := filepath.Rel(unpack, path)
		inBin := strings.Contains(filepath.ToSlash(rel), "/bin/") || strings.HasPrefix(filepath.ToSlash(rel), "bin/")
		st, statErr := os.Stat(path)
		if statErr != nil {
			return nil
		}
		execish := st.Mode()&0111 != 0 || inBin
		if !execish {
			return nil
		}
		dest := filepath.Join(optBin, name)
		if !fileExists(optBin) {
			if p := c.looksLikeInstalled(name); p != "" {
				dest = p
			}
		}
		_ = ensureDir(filepath.Dir(dest))
		if err := copyFile(path, dest); err != nil {
			return err
		}
		_ = os.Chmod(dest, 0755)
		c.refreshClientLinks(dest)
		copied++
		return nil
	})
	if copied == 0 {
		return fmt.Errorf("archive had no installable binaries")
	}
	return nil
}

func (c *ClientUpdateController) looksLikeInstalled(name string) string {
	for _, p := range []string{
		filepath.Join(c.optDir(), "bin", name),
		"/usr/local/bin/" + name,
		"/usr/bin/" + name,
	} {
		if fileExists(p) {
			return p
		}
	}
	if p, err := exec.LookPath(name); err == nil {
		return p
	}
	return ""
}
