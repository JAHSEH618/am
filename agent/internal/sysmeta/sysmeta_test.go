// 系统元信息单元测试。
//
// gz
package sysmeta

import (
	"net"
	"testing"
)

func TestLocalIP_IsValidOrEmpty(t *testing.T) {
	got := LocalIP()
	if got == "" {
		t.Skip("no usable IPv4 on this host (CI sandbox?)")
	}
	parsed := net.ParseIP(got)
	if parsed == nil {
		t.Fatalf("LocalIP returned non-IP: %q", got)
	}
	if parsed.IsLoopback() {
		t.Fatalf("LocalIP returned loopback %q, must filter out", got)
	}
	if v4 := parsed.To4(); v4 == nil {
		t.Fatalf("LocalIP returned non-IPv4 %q", got)
	}
}

func TestIsVirtualIface(t *testing.T) {
	cases := map[string]bool{
		"en0":         false,
		"eth0":        false,
		"wi-fi":       false, // Windows 真实网卡
		"以太网":         false, // Windows 中文显示，未匹配前缀
		"utun3":       true,
		"docker0":     true,
		"br-abc":      true,
		"vboxnet0":    true,
		"vmnet8":      true,
		"vethernet1":  true, // Hyper-V
		"awdl0":       true,
	}
	for name, want := range cases {
		if got := isVirtualIface(name); got != want {
			t.Errorf("isVirtualIface(%q)=%v, want %v", name, got, want)
		}
	}
}

func TestGlobalGitUser_NoCrash(t *testing.T) {
	u := GlobalGitUser()
	// 不强求一定有值（CI 可能没配 git），只要不 panic 就行
	t.Logf("git user: name=%q email=%q", u.Name, u.Email)
}
