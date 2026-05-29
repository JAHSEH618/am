package monitorpolicy

import (
	"testing"

	"github.com/am/aiwatch-agent/internal/config"
)

func TestPolicyAllowsTypeNilOpen(t *testing.T) {
	t.Parallel()
	if !PolicyAllowsType(nil, "cursor") {
		t.Fatal("nil policy means allow all")
	}
}

func TestPolicyAllowsTypeExplicitSubset(t *testing.T) {
	t.Parallel()
	p := &config.MonitorPolicy{EnabledMonitorTypes: []string{"cursor"}, Version: 1}
	if PolicyAllowsType(p, "codex") {
		t.Fatal("codex blocked")
	}
	if !PolicyAllowsType(p, "cursor") {
		t.Fatal("cursor allowed")
	}
}

func TestPolicyAllowsTypeExplicitEmptyMeansNone(t *testing.T) {
	t.Parallel()
	p := &config.MonitorPolicy{EnabledMonitorTypes: []string{}, Version: 2}
	if PolicyAllowsType(p, "cursor") {
		t.Fatal("nothing enabled")
	}
}

func TestMergeFromServerDedupNormalize(t *testing.T) {
	t.Parallel()
	cfg := &config.Config{}
	if !MergeFromServer(cfg, &config.MonitorPolicy{
		EnabledMonitorTypes: []string{" Codex ", "cursor", "cursor"},
		Version:             9,
		TtlMs:               300000,
	}) {
		t.Fatal("first merge should change")
	}
	if cfg.MonitorPolicy.Version != 9 || len(cfg.MonitorPolicy.EnabledMonitorTypes) != 2 {
		t.Fatalf("got %+v", cfg.MonitorPolicy)
	}
	if cfg.MonitorPolicy.EnabledMonitorTypes[0] != "codex" || cfg.MonitorPolicy.EnabledMonitorTypes[1] != "cursor" {
		t.Fatalf("sort/order: %+v", cfg.MonitorPolicy.EnabledMonitorTypes)
	}
	if MergeFromServer(cfg, &config.MonitorPolicy{
		EnabledMonitorTypes: []string{"codex", "cursor"},
		Version:             9,
		TtlMs:               300000,
	}) {
		t.Fatal("identical canonical should noop")
	}
}
