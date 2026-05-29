// Package svcctl 封装 aiwatchd 在三个平台下的服务启停操作。
//
// 平台与 install 脚本严格保持一致（命名 + 路径）：
//
//	macOS    launchd plist  ~/Library/LaunchAgents/com.aiwatch.aiwatchd.plist
//	Linux    systemd user   ~/.config/systemd/user/aiwatchd.service
//	Windows  ScheduledTask  名为 aiwatchd
//
// 主要 caller 是 `aiwatchd update` 子命令：在线升级时需要先停 service、覆盖二进制、再启。
// 每个 Stop / Start 操作幂等且容错——如果 service 没注册（比如开发机直接跑），
// 不会硬失败，只打日志返回 nil 让 update 流程继续（updater 会按需提示用户手动重启）。
//
// gz
package svcctl

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"time"
)

const (
	// LaunchdLabel 是 install.sh 写入 plist 的 Label 字段。
	LaunchdLabel = "com.aiwatch.aiwatchd"
	// SystemdUnit 是 install.sh 写入的 user unit 名（含 .service）。
	SystemdUnit = "aiwatchd.service"
	// WindowsTaskName 是 install.ps1 注册的 ScheduledTask 名字。
	WindowsTaskName = "aiwatchd"
)

// Stop 关闭当前用户的 aiwatchd service。
// 即使服务并未注册或并未运行，返回 nil（视为"已经处于停止状态"）。
func Stop() error {
	switch runtime.GOOS {
	case "darwin":
		return stopLaunchd()
	case "linux":
		return stopSystemd()
	case "windows":
		return stopWindowsTask()
	default:
		return fmt.Errorf("unsupported platform: %s", runtime.GOOS)
	}
}

// Start 启动当前用户的 aiwatchd service。失败时返回错误。
func Start() error {
	switch runtime.GOOS {
	case "darwin":
		return startLaunchd()
	case "linux":
		return startSystemd()
	case "windows":
		return startWindowsTask()
	default:
		return fmt.Errorf("unsupported platform: %s", runtime.GOOS)
	}
}

// ---------- macOS ----------

func plistPath() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, "Library", "LaunchAgents", LaunchdLabel+".plist")
}

func stopLaunchd() error {
	pp := plistPath()
	if _, err := os.Stat(pp); os.IsNotExist(err) {
		return nil
	}
	// unload 即使 service 当前没起也不会真正出错；忽略 stderr。
	_ = exec.Command("launchctl", "unload", pp).Run()
	return nil
}

func startLaunchd() error {
	pp := plistPath()
	if _, err := os.Stat(pp); os.IsNotExist(err) {
		return fmt.Errorf("launchd plist not found at %s; run install.sh once first", pp)
	}
	out, err := exec.Command("launchctl", "load", pp).CombinedOutput()
	if err != nil {
		return fmt.Errorf("launchctl load failed: %v: %s", err, string(out))
	}
	return nil
}

// ---------- Linux ----------

func stopSystemd() error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return nil
	}
	// stop 不会因为 unit 不存在而 panic；它会非 0 退出但不要紧。
	_ = exec.Command("systemctl", "--user", "stop", SystemdUnit).Run()
	return nil
}

func startSystemd() error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return fmt.Errorf("systemctl not found; please run install.sh once or manually run `aiwatchd start`")
	}
	out, err := exec.Command("systemctl", "--user", "start", SystemdUnit).CombinedOutput()
	if err != nil {
		return fmt.Errorf("systemctl start failed: %v: %s", err, string(out))
	}
	return nil
}

// ---------- Windows ----------

func stopWindowsTask() error {
	cmd := exec.Command("powershell", "-NoProfile", "-Command",
		fmt.Sprintf("Stop-ScheduledTask -TaskName '%s' -ErrorAction SilentlyContinue", WindowsTaskName))
	_ = cmd.Run()
	// Windows 上 .exe 在被 stop 后还可能短暂被锁；给它 1 秒缓冲让进程退干净。
	time.Sleep(1 * time.Second)
	return nil
}

func startWindowsTask() error {
	out, err := exec.Command("powershell", "-NoProfile", "-Command",
		fmt.Sprintf("Start-ScheduledTask -TaskName '%s'", WindowsTaskName)).CombinedOutput()
	if err != nil {
		return fmt.Errorf("Start-ScheduledTask failed: %v: %s", err, string(out))
	}
	return nil
}

// RemoveRegistration 注销安装脚本注册的开机自启（plist / systemd unit / Windows 计划任务 + HKCU Run）。
// 与 Stop 独立调用：先 Stop 再 Remove，避免进程丢文件锁。
func RemoveRegistration() error {
	switch runtime.GOOS {
	case "darwin":
		return removeLaunchdPlist()
	case "linux":
		return removeSystemdUnit()
	case "windows":
		return removeWindowsAutostart()
	default:
		return fmt.Errorf("unsupported platform: %s", runtime.GOOS)
	}
}

func removeLaunchdPlist() error {
	pp := plistPath()
	if _, err := os.Stat(pp); os.IsNotExist(err) {
		return nil
	}
	_ = os.Remove(pp)
	return nil
}

func removeSystemdUnit() error {
	home, err := os.UserHomeDir()
	if err != nil {
		return err
	}
	unit := filepath.Join(home, ".config", "systemd", "user", SystemdUnit)
	if _, err := exec.LookPath("systemctl"); err == nil {
		_ = exec.Command("systemctl", "--user", "disable", "--now", SystemdUnit).Run()
	}
	if _, err := os.Stat(unit); err == nil {
		_ = os.Remove(unit)
	}
	if _, err := exec.LookPath("systemctl"); err == nil {
		_ = exec.Command("systemctl", "--user", "daemon-reload").Run()
	}
	return nil
}

func removeWindowsAutostart() error {
	// 计划任务 / Run 键可能本就不存在；统一忽略错误，由后续删目录兜底。
	ps := fmt.Sprintf(
		"$ErrorActionPreference='SilentlyContinue'; "+
			"Unregister-ScheduledTask -TaskName '%s' -Confirm:$false; "+
			"Remove-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run' -Name 'aiwatchd'",
		WindowsTaskName)
	_ = exec.Command("powershell", "-NoProfile", "-Command", ps).Run()
	return nil
}
