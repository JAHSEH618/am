// Package monitorpolicy 实现与服务端 monitor_target.enabled 对齐的采集白名单。
//
// nil 策略 = 尚未从服务端收到 monitor_policy（老服务端 / 历史 config），按「全开」处理。
// 显式策略下 enabled_monitor_types 为空表示服务端全部禁用会话类 Provider。
// gz
package monitorpolicy

import (
	"slices"
	"strings"

	"github.com/am/aiwatch-agent/internal/config"
)

// CollectorEnabled 判断某 Provider type_code 是否允许采集并上报。
func CollectorEnabled(cfg *config.Config, typeCode string) bool {
	if cfg == nil {
		return true
	}
	return PolicyAllowsType(cfg.MonitorPolicy, typeCode)
}

// PolicyAllowsType 纯函数：便于单测。
func PolicyAllowsType(p *config.MonitorPolicy, typeCode string) bool {
	if p == nil {
		return true
	}
	for _, x := range p.EnabledMonitorTypes {
		if strings.EqualFold(strings.TrimSpace(x), typeCode) {
			return true
		}
	}
	return false
}

// MergeFromServer 把服务端返回的策略写入 cfg；有变化时返回 true（调用方可选择落盘）。
func MergeFromServer(cfg *config.Config, in *config.MonitorPolicy) (changed bool) {
	if cfg == nil || in == nil {
		return false
	}
	next := NormalizeCopy(in)
	prevNorm := NormalizeCopy(cfg.MonitorPolicy)
	if policiesEqual(prevNorm, next) {
		return false
	}
	cfg.MonitorPolicy = next
	return true
}

// NormalizeCopy 深拷贝并把 type 规范为小写 Trim。
func NormalizeCopy(in *config.MonitorPolicy) *config.MonitorPolicy {
	if in == nil {
		return nil
	}
	types := make([]string, 0, len(in.EnabledMonitorTypes))
	for _, x := range in.EnabledMonitorTypes {
		t := strings.ToLower(strings.TrimSpace(x))
		if t != "" && !slices.Contains(types, t) {
			types = append(types, t)
		}
	}
	slices.Sort(types)
	out := &config.MonitorPolicy{
		Version:             in.Version,
		TtlMs:               in.TtlMs,
		EnabledMonitorTypes: types,
	}
	return out
}

func policiesEqual(a, b *config.MonitorPolicy) bool {
	if a == nil && b == nil {
		return true
	}
	if a == nil || b == nil {
		return false
	}
	if a.Version != b.Version || a.TtlMs != b.TtlMs {
		return false
	}
	if len(a.EnabledMonitorTypes) != len(b.EnabledMonitorTypes) {
		return false
	}
	for i := range a.EnabledMonitorTypes {
		if a.EnabledMonitorTypes[i] != b.EnabledMonitorTypes[i] {
			return false
		}
	}
	return true
}
