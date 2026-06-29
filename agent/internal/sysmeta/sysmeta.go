// Package sysmeta 采集与本机环境相关的元信息（局域网 IP + Git 全局账号）。
//
// 跨平台保证：
//
//	IP 采集走 net.InterfaceAddrs()，纯 Go 实现，macOS / Windows / Linux 一致。
//	Git 账号走 `git config --global` —— Git 的 CLI 在三平台都把全局配置存在用户主目录，
//	  并通过 PATH 的 git 可执行文件统一访问；不直接读 ~/.gitconfig 是为了让 Windows 上的
//	  GitHub Desktop / Git for Windows 用户也能正确返回。
// gz
package sysmeta

import (
	"context"
	"net"
	"os/exec"
	"strings"
	"sync"
	"time"

	"github.com/am/aiwatch-agent/internal/procutil"
)

var (
	localIPMu     sync.RWMutex
	localIPCached string
	localIPAt     time.Time
	localIPTTL    = 10 * time.Minute
)

// LocalIP 返回首个非 loopback 的 IPv4 地址。没有可用网卡时返回空串。
//
// macOS / Windows / Linux 都用 net.InterfaceAddrs()，避免每个平台单独 syscall。
//
// 优先顺序（直观且稳定）：
//  1. IPv4
//  2. 非 loopback 且非 link-local（169.254.0.0/16）
//  3. 非内核虚拟网卡（utun / vboxnet / docker / vEthernet 等）—— 通过子串过滤
//
// 如果筛后多张网卡都满足，按系统返回顺序取第一个。
func LocalIP() string {
	localIPMu.RLock()
	if !localIPAt.IsZero() && time.Since(localIPAt) < localIPTTL {
		ip := localIPCached
		localIPMu.RUnlock()
		return ip
	}
	localIPMu.RUnlock()

	localIPMu.Lock()
	defer localIPMu.Unlock()
	if !localIPAt.IsZero() && time.Since(localIPAt) < localIPTTL {
		return localIPCached
	}
	localIPCached = localIPUncached()
	localIPAt = time.Now()
	return localIPCached
}

func localIPUncached() string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return ""
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		name := strings.ToLower(iface.Name)
		if isVirtualIface(name) {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			var ip net.IP
			switch v := addr.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			if ip == nil || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
				continue
			}
			ip4 := ip.To4()
			if ip4 == nil {
				continue
			}
			return ip4.String()
		}
	}
	return ""
}

// isVirtualIface 通过名字粗略过滤出虚拟网卡：utun(macOS VPN) / vboxnet / docker / br- / veth /
// vEthernet (Hyper-V) / Loopback Pseudo-Interface (Windows)。
//
// 不在白名单内的所有真实网卡（en0/eth0/Wi-Fi/以太网/无线网络）都会被保留。
func isVirtualIface(lowerName string) bool {
	for _, prefix := range []string{
		"utun", "awdl", "llw", "anpi", "ap", "bridge", "vmnet", "vboxnet", "vmware",
		"docker", "br-", "veth", "tun", "tap",
		"vethernet", // Windows Hyper-V
	} {
		if strings.HasPrefix(lowerName, prefix) {
			return true
		}
	}
	return false
}

// GitUser 全局 Git 账号（user.name + user.email）。读不到的字段以空串返回。
type GitUser struct {
	Name  string
	Email string
}

var (
	gitUserMu     sync.RWMutex
	gitUserCached GitUser
	gitUserAt     time.Time
	gitUserTTL    = 10 * time.Minute
)

// GlobalGitUser 通过 `git config --global --get user.{name,email}` 读取。
//
// 跨平台说明：Windows 下 Git for Windows 安装后会把 git.exe 加到 PATH，所以同一份代码即可工作。
// 单条命令 1.5s 超时，避免某些 SSH 探测的 git 命令阻塞 Reporter。
// 进程内缓存 10 分钟，避免 reporter 每 tick 重复 fork git。
func GlobalGitUser() GitUser {
	gitUserMu.RLock()
	if !gitUserAt.IsZero() && time.Since(gitUserAt) < gitUserTTL {
		u := gitUserCached
		gitUserMu.RUnlock()
		return u
	}
	gitUserMu.RUnlock()

	gitUserMu.Lock()
	defer gitUserMu.Unlock()
	if !gitUserAt.IsZero() && time.Since(gitUserAt) < gitUserTTL {
		return gitUserCached
	}
	gitUserCached = GitUser{
		Name:  runGit("config", "--global", "--get", "user.name"),
		Email: runGit("config", "--global", "--get", "user.email"),
	}
	gitUserAt = time.Now()
	return gitUserCached
}

func runGit(args ...string) string {
	ctx, cancel := context.WithTimeout(context.Background(), 1500*time.Millisecond)
	defer cancel()
	out, err := procutil.Hidden(exec.CommandContext(ctx, "git", args...)).Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}
