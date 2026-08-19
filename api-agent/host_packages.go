package main

import (
	"fmt"
	"os"
	"os/exec"
	"strings"
)

// hostPackagesForNetwork is the OS-package catalog the agent must keep installed.
// Chain clients (geth, bitcoind, …) stay in their ensure* installers; this list is
// apt packages the unit/snapshot/start path needs on a bare Ubuntu host.
//
// When a new client version needs a new package — add it here in the same PR.
// Called on every provision AND start so Update+Start picks up new deps.
func hostPackagesForNetwork(network string) []string {
	network = normalizeNetwork(network)
	common := []string{"ca-certificates", "curl", "wget", "tar", "jq"}
	var extra []string
	switch network {
	case "tron":
		// java-tron GreatVoyage is Java 8 only. ensureJava8 also verifies
		// `java -version` == 8 and falls back to Temurin 8 if the distro
		// has no openjdk-8 (Ubuntu 24.04) or PATH java is 17.
		extra = []string{"openjdk-8-jre-headless"}
	case "ton":
		extra = []string{"git", "python3-pip"}
	case "solana":
		// bzip2 = CLI tarball. Rest = Anza v3+ source build (tarball has no agave-validator).
		extra = []string{
			"bzip2", "git", "build-essential", "pkg-config", "libudev-dev",
			"llvm", "libclang-dev", "clang", "cmake", "protobuf-compiler",
			"libssl-dev", "libprotobuf-dev",
		}
	}
	return uniqStrings(append(common, extra...))
}

func dpkgInstalled(pkg string) bool {
	out, err := exec.Command("dpkg-query", "-W", "-f=${Status}", pkg).CombinedOutput()
	if err != nil {
		return false
	}
	return strings.Contains(string(out), "install ok installed")
}

func aptInstall(pkgs []string) error {
	if len(pkgs) == 0 {
		return nil
	}
	args := append([]string{"install", "-y", "-qq"}, pkgs...)
	cmd := exec.Command("apt-get", args...)
	cmd.Env = append(os.Environ(), "DEBIAN_FRONTEND=noninteractive")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("apt-get install %s: %v (%s)", strings.Join(pkgs, " "), err, strings.TrimSpace(string(out)))
	}
	return nil
}

// ensureAptPackages installs missing Debian packages. No-op on non-apt hosts (unit tests / Mac).
func ensureAptPackages(pkgs []string) error {
	pkgs = uniqStrings(pkgs)
	if len(pkgs) == 0 {
		return nil
	}
	if _, err := exec.LookPath("apt-get"); err != nil {
		return nil
	}
	var missing []string
	for _, p := range pkgs {
		if !dpkgInstalled(p) {
			missing = append(missing, p)
		}
	}
	if len(missing) == 0 {
		return nil
	}
	_ = exec.Command("apt-get", "update", "-qq").Run()
	if err := aptInstall(missing); err != nil {
		_ = exec.Command("add-apt-repository", "-y", "universe").Run()
		_ = exec.Command("apt-get", "update", "-qq").Run()
		if err2 := aptInstall(missing); err2 != nil {
			return err2
		}
	}
	return nil
}

// ensureNetworkHostDeps installs OS packages for this network profile.
// Idempotent. Safe to call on provision and on every start.
func ensureNetworkHostDeps(network string) error {
	return ensureAptPackages(hostPackagesForNetwork(network))
}
