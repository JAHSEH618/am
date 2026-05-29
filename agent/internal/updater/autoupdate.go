// 自动升级调度：长期运行的 daemon 周期性比对 manifest，必要时分离启动 `aiwatchd update`。
//
// gz
package updater

import (
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"

	"github.com/am/aiwatch-agent/internal/config"
	"github.com/am/aiwatch-agent/internal/logger"
)

var autoUpdateSpawnMu sync.Mutex

// MaybeScheduleDetachedUpgrade 比对服务端 manifest 与本机版本；若有差异则分离启动 `aiwatchd update`
// 子进程完成停服 → 换包 → 启服（与员工手动执行等价）。
//
// 不可在 `update` 子命令进程内调用（无意义）；应由长期运行的 `start` daemon 周期触发。
func MaybeScheduleDetachedUpgrade(currentVersion string) error {
	cv := strings.TrimSpace(currentVersion)
	if cv == "" || cv == "dev" {
		return nil
	}
	cfg, err := config.Load()
	if err != nil {
		logger.Warnf("auto-update: skip (config): %v", err)
		return nil
	}
	if strings.TrimSpace(cfg.ServerURL) == "" {
		return nil
	}

	manifest, err := fetchManifest(cfg.ServerURL)
	if err != nil {
		logger.Warnf("auto-update: manifest fetch failed: %v", err)
		return nil
	}
	sv := strings.TrimSpace(manifest.Version)
	if sv == "" {
		logger.Warnf("auto-update: manifest missing version, skip")
		return nil
	}
	if sv == cv {
		return nil
	}

	autoUpdateSpawnMu.Lock()
	defer autoUpdateSpawnMu.Unlock()

	logger.Infof("auto-update: server=%s local=%s, spawning detached `aiwatchd update`", sv, cv)
	return launchDetachedUpdateCLI()
}

func launchDetachedUpdateCLI() error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	if resolved, err := filepath.EvalSymlinks(exe); err == nil {
		exe = resolved
	}
	cmd := exec.Command(exe, "update")
	cmd.Stdin = nil
	cmd.Stdout = io.Discard
	cmd.Stderr = io.Discard
	if attr := sysProcAttrDetached(); attr != nil {
		cmd.SysProcAttr = attr
	}
	return cmd.Start()
}
