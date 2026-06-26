// 自适应上报 cadence 单测：resolveActiveInterval 的夹取边界 + nextInterval 的 active 路由。
//
// <p>「实时活跃不够实时」的修复把"活跃时段快报"的间隔解析与选择，从依赖网络 / provider /
// 设备采集、难以单测的 tickOnce 里拆成两个纯函数。任一回归（误把全员压到亚秒级、或 fast 比
// 基线还慢、或 active 信号不再切换 cadence）都必须挡在这关。
//
// gz
package reporter

import (
	"testing"
	"time"

	"github.com/am/aiwatch-agent/internal/config"
)

func TestResolveActiveInterval(t *testing.T) {
	const slow = 120 * time.Second
	def := time.Duration(config.DefaultActiveReportIntervalMs) * time.Millisecond

	cases := []struct {
		name  string
		cfgMs int64
		slow  time.Duration
		want  time.Duration
	}{
		{"zero uses default", 0, slow, def},
		{"negative uses default", -1, slow, def},
		{"custom honored", 20_000, slow, 20 * time.Second},
		{"below floor clamps up", 1_000, slow, minActiveReportInterval},
		{"above slow clamps to slow", 600_000, slow, slow},
		{"default still bounded by huge slow", 0, 10 * time.Minute, def},
		{"slow shorter than fast degrades to slow", 0, 8 * time.Second, 8 * time.Second},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := resolveActiveInterval(tc.cfgMs, tc.slow)
			if got != tc.want {
				t.Errorf("resolveActiveInterval(%d, %s) = %s, want %s", tc.cfgMs, tc.slow, got, tc.want)
			}
		})
	}
}

func TestNextIntervalFollowsActive(t *testing.T) {
	const fast = 15 * time.Second
	const slow = 120 * time.Second
	r := &Reporter{}

	if got := r.nextInterval(fast, slow); got != slow {
		t.Errorf("idle (zero value) nextInterval = %s, want %s", got, slow)
	}
	r.lastActive.Store(true)
	if got := r.nextInterval(fast, slow); got != fast {
		t.Errorf("active nextInterval = %s, want %s", got, fast)
	}
	r.lastActive.Store(false)
	if got := r.nextInterval(fast, slow); got != slow {
		t.Errorf("back-to-idle nextInterval = %s, want %s", got, slow)
	}
}
