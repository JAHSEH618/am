package openharness

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func writeOHSession(t *testing.T, sessRoot, userHash, name, model string) string {
	t.Helper()
	dir := filepath.Join(sessRoot, userHash)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, name)
	body := `{"session_id":"s-oh-1","model":"` + model + `","cwd":"/Users/x/proj","messages":[]}`
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestSnapshot_MtimeCacheReuseAndInvalidation(t *testing.T) {
	root := t.TempDir()
	t.Setenv("AM_OPENHARNESS_DIR", root)
	if err := os.WriteFile(filepath.Join(root, "settings.json"), []byte(`{}`), 0o600); err != nil {
		t.Fatal(err)
	}
	sessRoot := filepath.Join(root, "data", "sessions")
	path := writeOHSession(t, sessRoot, "uhash", "session-aaa.json", "model-v1")

	fixed := time.Now().Add(-time.Minute)
	if err := os.Chtimes(path, fixed, fixed); err != nil {
		t.Fatal(err)
	}

	p := New("/tmp/fallback")
	p.SetLookback(10 * 365 * 24 * time.Hour) // 大窗口,避免 fixture mtime 过期

	snap1, err := p.Snapshot(t.Context())
	if err != nil || len(snap1.Sessions) != 1 {
		t.Fatalf("snap1 sessions=%d err=%v (检查 fixture json tag 是否匹配 rawSession)", len(snap1.Sessions), err)
	}
	if snap1.Sessions[0].Model != "model-v1" {
		t.Fatalf("snap1 model=%q want model-v1", snap1.Sessions[0].Model)
	}

	// 改内容但还原 mtime → 缓存命中,复用旧解析(model-v1)
	if err := os.WriteFile(path, []byte(`{"session_id":"s-oh-1","model":"model-v2","cwd":"/Users/x/proj","messages":[]}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(path, fixed, fixed); err != nil {
		t.Fatal(err)
	}
	snap2, _ := p.Snapshot(t.Context())
	if len(snap2.Sessions) != 1 || snap2.Sessions[0].Model != "model-v1" {
		t.Fatalf("snap2 model=%+v want model-v1 (mtime 未变应命中缓存)", snap2.Sessions)
	}

	// 推进 mtime → 失效,读到新内容(model-v2)
	later := time.Now()
	if err := os.Chtimes(path, later, later); err != nil {
		t.Fatal(err)
	}
	snap3, _ := p.Snapshot(t.Context())
	if len(snap3.Sessions) != 1 || snap3.Sessions[0].Model != "model-v2" {
		t.Fatalf("snap3 model=%+v want model-v2 (mtime 推进应失效)", snap3.Sessions)
	}
}
