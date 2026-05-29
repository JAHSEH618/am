// Package device 负责设备信息采集：hostname、osType、hostHash、局域网 IP、Git 全局账号。
//
// 设计文档 §4 / §5 字段：
//
//	hostname        主机名
//	osType          macos / windows / linux
//	hostHash        sha256(hostname + "|" + userCode) — 区分员工 × 机器
//	localIP         首个非 loopback 的 IPv4（局域网 IP），跨平台
//	gitUserName     git config --global user.name
//	gitUserEmail    git config --global user.email
// gz
package device

import (
	"crypto/sha256"
	"encoding/hex"
	"os"
	"runtime"

	"github.com/am/aiwatch-agent/internal/sysmeta"
)

// Info 描述当前设备的基础信息。
type Info struct {
	Hostname     string
	OSType       string
	HostHash     string
	LocalIP      string
	GitUserName  string
	GitUserEmail string
}

// Collect 采集本机信息。userCode 用来参与 hostHash 计算，让同机不同员工得到不同的指纹。
func Collect(userCode string) (Info, error) {
	hostname, err := os.Hostname()
	if err != nil {
		hostname = "unknown"
	}
	gu := sysmeta.GlobalGitUser()
	return Info{
		Hostname:     hostname,
		OSType:       normalizeOSType(runtime.GOOS),
		HostHash:     hashHost(hostname, userCode),
		LocalIP:      sysmeta.LocalIP(),
		GitUserName:  gu.Name,
		GitUserEmail: gu.Email,
	}, nil
}

func normalizeOSType(goos string) string {
	switch goos {
	case "darwin":
		return "macos"
	case "windows":
		return "windows"
	case "linux":
		return "linux"
	default:
		return goos
	}
}

func hashHost(hostname, userCode string) string {
	sum := sha256.Sum256([]byte(hostname + "|" + userCode))
	return "sha256-" + hex.EncodeToString(sum[:])
}
