// Package uninstall 实现本机「完全卸载」：停服务、注销自启、删除配置 / state / 缓存 / 日志及默认二进制路径。
//
// 与 install.sh / install.ps1 的 --clean / -Clean 使用同一套路径约定，避免重装后残留 gitlog cursor 等。
// gz
package uninstall

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"

	"github.com/am/aiwatch-agent/internal/config"
	"github.com/am/aiwatch-agent/internal/logger"
	"github.com/am/aiwatch-agent/internal/svcctl"
)

// Run 执行卸载。出于误触防护，必须显式传入 yes=true（对应 CLI --yes）。
func Run(yes bool) error {
	if !yes {
		return fmt.Errorf("为防误删，请加上 --yes：aiwatchd uninstall --yes")
	}

	if err := svcctl.Stop(); err != nil {
		return err
	}
	if err := svcctl.RemoveRegistration(); err != nil {
		return err
	}

	self, _ := os.Executable()
	self, _ = filepath.EvalSymlinks(self)

	if err := purgeDataDirs(self); err != nil {
		return err
	}

	fmt.Println("aiwatchd 已从本机卸载（服务、自启项与用户数据目录已清理）。默认二进制路径若仍存在可手动删除。")
	return nil
}

func purgeDataDirs(runningExe string) error {
	if cfgPath, err := config.DefaultPath(); err == nil {
		_ = os.RemoveAll(filepath.Dir(cfgPath))
	}
	if legacy, err := config.LegacyPath(); err == nil {
		_ = os.RemoveAll(filepath.Dir(legacy))
	}

	_ = removeCacheTree()

	logPath, err := logger.DefaultLogPath()
	if err == nil {
		_ = os.RemoveAll(filepath.Dir(logPath))
	}

	switch runtime.GOOS {
	case "windows":
		return purgeWindowsExtras(runningExe)
	default:
		return purgeUnixBinary(runningExe)
	}
}

func removeCacheTree() error {
	switch runtime.GOOS {
	case "windows":
		base := os.Getenv("LOCALAPPDATA")
		if base == "" {
			if home, err := os.UserHomeDir(); err == nil {
				base = filepath.Join(home, "AppData", "Local")
			}
		}
		if base != "" {
			return os.RemoveAll(filepath.Join(base, "aiwatchd", "gitlog"))
		}
	default:
		base, err := os.UserCacheDir()
		if err != nil {
			if home, err := os.UserHomeDir(); err == nil {
				base = filepath.Join(home, ".cache")
			} else {
				return nil
			}
		}
		return os.RemoveAll(filepath.Join(base, "aiwatchd"))
	}
	return nil
}

func purgeUnixBinary(runningExe string) error {
	home, err := os.UserHomeDir()
	if err != nil {
		return nil
	}
	p := filepath.Join(home, ".local", "bin", "aiwatchd")
	if runningExe != "" && strings.EqualFold(p, runningExe) {
		fmt.Fprintf(os.Stderr, "当前正从此路径运行，请退出后手动删除: %s\n", p)
		return nil
	}
	_ = os.Remove(p)
	return nil
}

func purgeWindowsExtras(runningExe string) error {
	base := os.Getenv("LOCALAPPDATA")
	if base == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return nil
		}
		base = filepath.Join(home, "AppData", "Local")
	}
	installRoot := filepath.Join(base, "aiwatchd")
	if installRoot == "" {
		return nil
	}

	entries, err := os.ReadDir(installRoot)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	for _, e := range entries {
		full := filepath.Join(installRoot, e.Name())
		if runningExe != "" && strings.EqualFold(full, runningExe) {
			continue
		}
		_ = os.RemoveAll(full)
	}
	if runningExe != "" && strings.HasPrefix(strings.ToLower(runningExe), strings.ToLower(installRoot+string(filepath.Separator))) {
		_ = os.Remove(installRoot)
		if _, err := os.Stat(runningExe); err == nil {
			fmt.Fprintf(os.Stderr, "安装目录中的 aiwatchd.exe 仍被占用（通常为本进程）。关闭终端后如仍存在，请手动删除: %s\n", runningExe)
		}
	} else {
		_ = os.Remove(installRoot)
	}
	return nil
}
