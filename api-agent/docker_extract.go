package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// ensureDockerInstalled — apt-get docker.io when missing (nitro/op binaries are image-only).
func ensureDockerInstalled() error {
	if _, err := exec.LookPath("docker"); err == nil {
		return nil
	}
	if _, err := exec.LookPath("apt-get"); err != nil {
		return fmt.Errorf("docker not installed and apt-get unavailable")
	}
	_ = exec.Command("apt-get", "update", "-qq").Run()
	cmd := exec.Command("apt-get", "install", "-y", "-qq", "docker.io")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("apt-get install docker.io: %v (%s)", err, strings.TrimSpace(string(out)))
	}
	_ = exec.Command("systemctl", "enable", "--now", "docker").Run()
	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		if _, err := exec.LookPath("docker"); err == nil {
			if exec.Command("docker", "info").Run() == nil {
				return nil
			}
		}
		time.Sleep(500 * time.Millisecond)
	}
	if _, err := exec.LookPath("docker"); err != nil {
		return fmt.Errorf("docker not on PATH after install")
	}

	return nil
}

// extractBinaryFromDockerImage pulls image and copies containerPath → dest.
func extractBinaryFromDockerImage(image, containerPath, dest string) error {
	if err := ensureDockerInstalled(); err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return err
	}
	pull := exec.Command("docker", "pull", image)
	if out, err := pull.CombinedOutput(); err != nil {
		return fmt.Errorf("docker pull %s: %v (%s)", image, err, strings.TrimSpace(string(out)))
	}
	cidOut, err := exec.Command("docker", "create", image).CombinedOutput()
	if err != nil {
		return fmt.Errorf("docker create %s: %v (%s)", image, err, strings.TrimSpace(string(cidOut)))
	}
	cid := strings.TrimSpace(string(cidOut))
	defer func() { _ = exec.Command("docker", "rm", "-f", cid).Run() }()

	tmp := dest + ".docker-extract"
	_ = os.Remove(tmp)
	cp := exec.Command("docker", "cp", cid+":"+containerPath, tmp)
	if out, err := cp.CombinedOutput(); err != nil {
		return fmt.Errorf("docker cp %s:%s: %v (%s)", image, containerPath, err, strings.TrimSpace(string(out)))
	}
	if err := os.Rename(tmp, dest); err != nil {
		_ = copyFile(tmp, dest)
		_ = os.Remove(tmp)
	}
	_ = os.Chmod(dest, 0o755)
	if !fileExists(dest) {
		return fmt.Errorf("extract %s from %s failed — dest missing", containerPath, image)
	}

	return nil
}

// ensureBinaryFromDocker — reuse dest if present; else extract from image.
func ensureBinaryFromDocker(image, containerPath, dest string) (string, error) {
	if fileExists(dest) {
		return dest, nil
	}
	if err := extractBinaryFromDockerImage(image, containerPath, dest); err != nil {
		return "", err
	}

	return dest, nil
}
