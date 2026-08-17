package main

import (
	"encoding/json"
	"os"
	"strings"
	"unicode"
)

type catalogFile struct {
	Entries []catalogEntry `json:"entries"`
}

type catalogEntry struct {
	Network    string  `json:"network"`
	Env        string  `json:"env"`
	Version    string  `json:"version"`
	Tag        string  `json:"tag"`
	Source     string  `json:"source"`
	SkipReason string  `json:"skip_reason"`
	GithubRepo string  `json:"github_repo"`
	TagPrefix  string  `json:"tag_prefix"`
	Artifacts  []asset `json:"artifacts"`
	Configs    []asset `json:"configs"`
}

type asset struct {
	Kind       string `json:"kind"`
	Name       string `json:"name"`
	URL        string `json:"url"`
	URLAarch64 string `json:"url_aarch64"`
	Optional   bool   `json:"optional"`
}

func (e catalogEntry) id() string { return e.Network + "/" + e.Env }

func (e catalogEntry) pin() string {
	for _, raw := range []string{e.Version, e.Tag} {
		t := strings.TrimSpace(raw)
		if t != "" && t != "unknown" {
			return t
		}
	}
	return ""
}

func (a asset) isApt() bool {
	return a.Kind == "apt" || strings.HasPrefix(a.URL, "apt://")
}

func (e catalogEntry) githubHint() (repo, prefix string, ok bool) {
	if repo = strings.TrimSpace(e.GithubRepo); repo != "" {
		prefix = e.TagPrefix
		if prefix == "" {
			prefix = tagPrefix(e.Tag)
		}
		return repo, prefix, true
	}
	for _, a := range e.Artifacts {
		if parsedRepo, tag, parsed := parseGitHubRelease(a.URL); parsed {
			prefix = e.TagPrefix
			if prefix == "" {
				prefix = tagPrefix(firstNonEmpty(e.Tag, tag))
			}
			return parsedRepo, prefix, true
		}
	}
	if repo, ok = repoFromSource(e.Source); ok {
		prefix = e.TagPrefix
		if prefix == "" {
			prefix = tagPrefix(e.Tag)
		}
		return repo, prefix, true
	}
	return "", "", false
}

func repoFromSource(raw string) (string, bool) {
	token := strings.TrimSpace(raw)
	for _, sep := range []string{" ", "+"} {
		if i := strings.IndexAny(token, sep); i >= 0 {
			token = strings.TrimSpace(token[:i])
			break
		}
	}
	parts := strings.Split(token, "/")
	if len(parts) != 2 {
		return "", false
	}
	owner := strings.TrimSpace(parts[0])
	repo := strings.TrimSpace(parts[1])
	if owner == "" || repo == "" || strings.Contains(owner, ".") || strings.Contains(repo, ".") {
		return "", false
	}
	return owner + "/" + repo, true
}

func parseGitHubRelease(url string) (repo, tag string, ok bool) {
	parts := strings.Split(url, "/")
	for i := 0; i < len(parts); i++ {
		if parts[i] != "github.com" || i+5 >= len(parts) {
			continue
		}
		if parts[i+3] != "releases" || parts[i+4] != "download" {
			continue
		}
		return parts[i+1] + "/" + parts[i+2], parts[i+5], true
	}
	return "", "", false
}

func tagPrefix(tag string) string {
	for i, r := range tag {
		if unicode.IsDigit(r) {
			return tag[:i]
		}
	}
	return tag
}

func displayVersion(tag string) string {
	ver := strings.TrimSpace(tag)
	for _, p := range []string{"GreatVoyage-Nile-", "GreatVoyage-", "v"} {
		if strings.HasPrefix(ver, p) {
			ver = strings.TrimPrefix(ver, p)
			break
		}
	}
	if len(ver) > 1 && (ver[0] == 'v' || ver[0] == 'V') && ver[1] >= '0' && ver[1] <= '9' {
		ver = ver[1:]
	}
	if ver == "" {
		return tag
	}
	return ver
}

func normalizeVer(raw string) string {
	s := strings.ToLower(strings.TrimSpace(raw))
	if len(s) > 1 && s[0] == 'v' && s[1] >= '0' && s[1] <= '9' {
		s = s[1:]
	}
	return s
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}

func loadCatalog(path string) (catalogFile, error) {
	var out catalogFile
	data, err := os.ReadFile(path)
	if err != nil {
		return out, err
	}
	err = json.Unmarshal(data, &out)
	return out, err
}
