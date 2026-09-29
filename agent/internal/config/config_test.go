package config

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestReportTimeout_MigratesLegacyValues(t *testing.T) {
	const legacy = 15 * 60 * 1000 // 旧默认：15 分钟，已被写进每台机器的 config.json
	cases := []struct {
		name string
		ms   int64
		want time.Duration
	}{
		{"unset uses new default", 0, 90 * time.Second},
		{"negative uses new default", -1, 90 * time.Second},
		{"legacy 15min default is migrated", legacy, 90 * time.Second},
		{"anything >= 5min is treated as stale", 5 * 60 * 1000, 90 * time.Second},
		{"just above the cap falls back to default", MaxReportTimeoutMs + 1, 90 * time.Second},
		{"cap itself is honored", MaxReportTimeoutMs, 120 * time.Second},
		{"custom short value is honored", 60 * 1000, 60 * time.Second},
		{"explicit new default is honored", DefaultReportTimeoutMs, 90 * time.Second},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			c := &Config{ReportTimeoutMs: tc.ms}
			if got := c.ReportTimeout(); got != tc.want {
				t.Errorf("ReportTimeout() = %s, want %s", got, tc.want)
			}
		})
	}
	var nilCfg *Config
	if got := nilCfg.ReportTimeout(); got != 90*time.Second {
		t.Errorf("nil config ReportTimeout() = %s, want 90s", got)
	}
}

func TestReportBodyBudget(t *testing.T) {
	cases := []struct {
		name string
		n    int64
		want int
	}{
		{"unset", 0, DefaultReportBodyBudgetBytes},
		{"negative", -5, DefaultReportBodyBudgetBytes},
		{"below min clamps up", 1024, MinReportBodyBudgetBytes},
		{"above max clamps down", 1 << 40, MaxReportBodyBudgetBytes},
		{"in range honored", 2 << 20, 2 << 20},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := (&Config{ReportBodyBudgetBytes: tc.n}).ReportBodyBudget(); got != tc.want {
				t.Errorf("ReportBodyBudget() = %d, want %d", got, tc.want)
			}
		})
	}
	var nilCfg *Config
	if got := nilCfg.ReportBodyBudget(); got != DefaultReportBodyBudgetBytes {
		t.Errorf("nil config budget = %d", got)
	}
}

// 把 config 目录指向临时目录（三个平台各自读取的环境变量都设上）。
func isolateConfigDir(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	t.Setenv("HOME", dir)
	t.Setenv("XDG_CONFIG_HOME", dir)
	t.Setenv("ProgramData", dir)
	return dir
}

// Load 遇到存量的 15 分钟超时：内存里改成新默认，但**不为它专门写盘**（不要每次启动都改文件）；
// 之后任何一次 Save 会把磁盘上的值一并更新。
func TestLoad_LegacyTimeoutMigratedInMemoryWithoutRewritingDisk(t *testing.T) {
	isolateConfigDir(t)
	path, err := DefaultPath()
	if err != nil {
		t.Fatal(err)
	}
	// 直接写文件（绕开 SaveTo 的迁移），模拟老版本落盘的 config.json。
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(`{"server_url":"http://s","user_code":"u1","agent_id":"a","agent_secret":"s","report_timeout_ms":900000}`), 0o600); err != nil {
		t.Fatal(err)
	}
	before, _ := os.ReadFile(path)
	statBefore, _ := os.Stat(path)

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.ReportTimeoutMs != DefaultReportTimeoutMs {
		t.Errorf("in-memory ReportTimeoutMs = %d, want %d", cfg.ReportTimeoutMs, DefaultReportTimeoutMs)
	}
	if got := cfg.ReportTimeout(); got != 90*time.Second {
		t.Errorf("ReportTimeout() = %s, want 90s", got)
	}
	after, _ := os.ReadFile(path)
	statAfter, _ := os.Stat(path)
	if string(before) != string(after) || !statBefore.ModTime().Equal(statAfter.ModTime()) {
		t.Errorf("Load must not rewrite config.json just to migrate the timeout; before=%s after=%s", before, after)
	}

	// 下一次本来就要发生的 Save（如注册 / 策略变更）顺带把磁盘上的值换成新默认。
	if err := Save(cfg); err != nil {
		t.Fatal(err)
	}
	reloaded, err := LoadFrom(path)
	if err != nil {
		t.Fatal(err)
	}
	if reloaded.ReportTimeoutMs != DefaultReportTimeoutMs {
		t.Errorf("after Save, on-disk ReportTimeoutMs = %d, want %d", reloaded.ReportTimeoutMs, DefaultReportTimeoutMs)
	}
}

// 即使调用方绕过 Load 直接 Save 一份带旧超时的 config（如注册流程里拿到的对象），落盘的也是新默认。
func TestSaveTo_MigratesLegacyTimeout(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	if err := SaveTo(&Config{ServerURL: "http://s", UserCode: "u", ReportTimeoutMs: 900000}, path); err != nil {
		t.Fatal(err)
	}
	got, err := LoadFrom(path)
	if err != nil {
		t.Fatal(err)
	}
	if got.ReportTimeoutMs != DefaultReportTimeoutMs {
		t.Errorf("saved ReportTimeoutMs = %d, want %d", got.ReportTimeoutMs, DefaultReportTimeoutMs)
	}
	// 运维显式设置的合理值不被动。
	if err := SaveTo(&Config{ServerURL: "http://s", UserCode: "u", ReportTimeoutMs: 45000}, path); err != nil {
		t.Fatal(err)
	}
	got, _ = LoadFrom(path)
	if got.ReportTimeoutMs != 45000 {
		t.Errorf("custom timeout must be preserved, got %d", got.ReportTimeoutMs)
	}
}

// 老配置没有 report_timeout_ms：补新默认并落盘（既有行为，默认值换成 90s）。
func TestLoad_MissingTimeoutFilledWithNewDefault(t *testing.T) {
	isolateConfigDir(t)
	path, _ := DefaultPath()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(`{"server_url":"http://s","user_code":"u1"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.ReportTimeoutMs != DefaultReportTimeoutMs {
		t.Errorf("ReportTimeoutMs = %d, want %d", cfg.ReportTimeoutMs, DefaultReportTimeoutMs)
	}
	onDisk, _ := LoadFrom(path)
	if onDisk.ReportTimeoutMs != DefaultReportTimeoutMs {
		t.Errorf("filled default should be persisted, on-disk = %d", onDisk.ReportTimeoutMs)
	}
}
