// cursors.go schema 兼容性单测。
//
// <p>核心场景三种：(1) 文件不存在；(2) v0 老格式（裸 map）→ 重置；(3) v1 新格式正常加载。
// 任一回归都意味着员工升级链路上"老 cursor 锁死历史"会复现，必须挡在测试这关。
//
// gz
package reporter

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/am/aiwatch-agent/internal/monitor"
)

// withTempState 为单测准备独立的 stateDir，避免污染开发机的真实 ~/.../aiwatchd/state/。
//
// <p>实现：用 TempDir() 拿到隔离目录，把 stateDir() 依赖的 config.DefaultPath 用 env var
// 强制覆盖；这里取巧：直接构造 MsgCursorStore.path 走 path 字段绕开 stateDir()，因为
// LoadCursorStore 是直接用 stateDir() 拼路径的，要测它必须改环境。
//
// <p>所以单测里我们绕过 LoadCursorStore，直接构造一个指向 tmp path 的 store + 手写 / 读文件，
// 复用 cursorsFile / MsgCursor 类型，覆盖 schema 兼容性核心分支即可。
func withTempCursorPath(t *testing.T) string {
	t.Helper()
	return filepath.Join(t.TempDir(), cursorsFileName)
}

func TestCursorsFile_LegacyV0Map_ResetsToEmpty(t *testing.T) {
	// 模拟 v2.7.0 写下来的老 cursors.json：直接是 map[string]MsgCursor，无 schema_version 字段
	path := withTempCursorPath(t)
	legacy := map[string]MsgCursor{
		"cursor:abc-123": {
			LastMsgID:   "abc-123:msg-old",
			LastMsgTime: time.Date(2026, 5, 8, 10, 0, 0, 0, time.UTC),
		},
	}
	data, err := json.Marshal(legacy)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}

	// 直接用 file-level helper 验证：load 出 envelope 失败 → 回退老 map → schema 不匹配 → 清空
	got, gotErr := loadFromFile(path)
	if gotErr != nil {
		t.Fatalf("load legacy file should not error, got: %v", gotErr)
	}
	if got == nil {
		t.Fatal("loadFromFile returned nil store")
	}
	if len(got.cursors) != 0 {
		t.Errorf("expected empty cursors after legacy reset, got %d entries", len(got.cursors))
	}
	if !got.dirty {
		t.Error("expected dirty=true after legacy reset (so next Save persists v1 envelope)")
	}
}

func TestCursorsFile_V1Envelope_LoadsNormally(t *testing.T) {
	path := withTempCursorPath(t)
	env := cursorsFile{
		SchemaVersion: CursorSchemaVersion,
		Cursors: map[string]MsgCursor{
			"claude:sess-1": {
				LastMsgID:   "sess-1:msg-x",
				LastMsgTime: time.Date(2026, 5, 9, 10, 0, 0, 0, time.UTC),
			},
		},
	}
	data, err := json.MarshalIndent(env, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}

	got, gotErr := loadFromFile(path)
	if gotErr != nil {
		t.Fatalf("load v1 envelope should not error, got: %v", gotErr)
	}
	if len(got.cursors) != 1 {
		t.Fatalf("expected 1 cursor, got %d", len(got.cursors))
	}
	if got.cursors["claude:sess-1"].LastMsgID != "sess-1:msg-x" {
		t.Errorf("loaded cursor body mismatch: %+v", got.cursors["claude:sess-1"])
	}
	if got.dirty {
		t.Error("expected dirty=false on clean v1 load")
	}
}

func TestCursorsFile_FutureSchemaVersion_ResetsToEmpty(t *testing.T) {
	path := withTempCursorPath(t)
	// 模拟未来升 v2 后回退到 v1 二进制：v2 文件应被当作不兼容清空
	env := cursorsFile{
		SchemaVersion: CursorSchemaVersion + 1,
		Cursors: map[string]MsgCursor{
			"cursor:future": {LastMsgID: "future-id"},
		},
	}
	data, _ := json.Marshal(env)
	_ = os.WriteFile(path, data, 0o600)

	got, gotErr := loadFromFile(path)
	if gotErr != nil {
		t.Fatalf("load future envelope should not error, got: %v", gotErr)
	}
	if len(got.cursors) != 0 {
		t.Errorf("expected empty cursors after future-version reset, got %d", len(got.cursors))
	}
	if !got.dirty {
		t.Error("expected dirty=true after future-version reset")
	}
}

func TestCursorsFile_FileNotExist_ReturnsEmpty(t *testing.T) {
	path := filepath.Join(t.TempDir(), "nonexistent.json")
	got, gotErr := loadFromFile(path)
	if gotErr != nil {
		t.Fatalf("missing file should not error, got: %v", gotErr)
	}
	if len(got.cursors) != 0 {
		t.Errorf("expected empty cursors on first install, got %d", len(got.cursors))
	}
	if got.dirty {
		t.Error("expected dirty=false on first install (no need to overwrite anything)")
	}
}

func TestCursorsFile_SaveLoadRoundtrip_PreservesCursorsAndEnvelope(t *testing.T) {
	path := withTempCursorPath(t)
	s := &MsgCursorStore{path: path, cursors: make(map[string]MsgCursor)}
	s.Set("cursor", "sess-A", MsgCursor{LastMsgID: "A:1", LastMsgTime: time.Now().UTC()})
	if err := s.Save(); err != nil {
		t.Fatalf("save: %v", err)
	}

	// 再读一次：应能拿到刚 Save 的内容 + dirty=false
	got, err := loadFromFile(path)
	if err != nil {
		t.Fatalf("reload: %v", err)
	}
	if got.cursors["cursor:sess-A"].LastMsgID != "A:1" {
		t.Errorf("roundtrip lost data: %+v", got.cursors)
	}
	if got.dirty {
		t.Error("expected dirty=false after fresh load of v1 file we just wrote")
	}

	// 验证文件确实是 envelope 格式（含 schema_version）
	raw, _ := os.ReadFile(path)
	if !containsKey(raw, "schema_version") {
		t.Errorf("saved file missing schema_version envelope key, raw=%s", string(raw))
	}
}

// loadFromFile 直接调用真正的加载逻辑（loadCursorStoreFrom），只是跳过 stateDir() 的环境依赖、直接给定 path。
// 以前这里复制了一份加载代码，单测测的是副本而不是生产逻辑。
func loadFromFile(path string) (*MsgCursorStore, error) {
	return loadCursorStoreFrom(path, time.Now())
}

// Save 剔除超过 cursorRetention 未推进的游标；保留期必须覆盖 bootstrap 扫描窗口。
func TestCursorsSave_PrunesStaleCursors(t *testing.T) {
	if cursorRetention <= monitor.BootstrapLookback {
		t.Fatalf("cursorRetention %s must exceed BootstrapLookback %s", cursorRetention, monitor.BootstrapLookback)
	}
	now := time.Now()
	for _, tc := range []struct {
		name      string
		updatedAt time.Time
		wantKept  bool
	}{
		{"just updated", now, true},
		{"older than bootstrap window, within retention", now.Add(-monitor.BootstrapLookback - 24*time.Hour), true},
		{"just inside retention", now.Add(-cursorRetention + time.Hour), true},
		{"past retention", now.Add(-cursorRetention - time.Hour), false},
		{"very old", now.Add(-365 * 24 * time.Hour), false},
		{"zero UpdatedAt cannot be aged", time.Time{}, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			path := withTempCursorPath(t)
			s := &MsgCursorStore{path: path, cursors: map[string]MsgCursor{
				"claude:old": {LastMsgID: "old:1", UpdatedAt: tc.updatedAt},
			}}
			s.Set("claude", "fresh", MsgCursor{LastMsgID: "fresh:1"})
			if err := s.Save(); err != nil {
				t.Fatalf("save: %v", err)
			}
			got, err := loadFromFile(path)
			if err != nil {
				t.Fatalf("reload: %v", err)
			}
			if _, ok := got.cursors["claude:fresh"]; !ok {
				t.Fatal("fresh cursor must survive")
			}
			if _, ok := got.cursors["claude:old"]; ok != tc.wantKept {
				t.Fatalf("old cursor kept on disk=%v want %v", ok, tc.wantKept)
			}
			if _, ok := s.Get("claude", "old"); ok != tc.wantKept {
				t.Fatalf("old cursor kept in memory=%v want %v", ok, tc.wantKept)
			}
		})
	}
}

func containsKey(raw []byte, key string) bool {
	var m map[string]any
	if err := json.Unmarshal(raw, &m); err != nil {
		return false
	}
	_, ok := m[key]
	return ok
}

// ---- 游标文件损坏（cursors.json 一旦损坏，过去每次重启都全量 bootstrap 且永远存不了盘）----

func writeCorruptCursors(t *testing.T, path string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(`{"schema_version": 3, "cursors": {"claude:a": {"last_msg_id": "x"`), 0o600); err != nil {
		t.Fatal(err)
	}
}

func corruptBackups(t *testing.T, path string) []string {
	t.Helper()
	m, err := filepath.Glob(path + corruptSuffix + "*")
	if err != nil {
		t.Fatal(err)
	}
	return m
}

func TestLoadCursorStore_CorruptFile_BacksUpAndRecovers(t *testing.T) {
	path := withTempCursorPath(t)
	writeCorruptCursors(t, path)
	now := time.Date(2026, 9, 29, 10, 15, 0, 0, time.UTC)

	s, err := loadCursorStoreFrom(path, now)
	if err != nil {
		t.Fatalf("a corrupt file must be recovered from, not surfaced as an error (caller would degrade to a path-less store): %v", err)
	}
	if s.path != path {
		t.Fatalf("recovered store has path %q, want %q — a path-less store can never persist", s.path, path)
	}
	if !s.IsEmpty() {
		t.Error("recovered store starts empty (one-time full backfill)")
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Error("the corrupt file must be moved away")
	}
	backups := corruptBackups(t, path)
	if len(backups) != 1 {
		t.Fatalf("want exactly 1 backup, got %v", backups)
	}
	if b, _ := os.ReadFile(backups[0]); len(b) == 0 {
		t.Error("the backup must keep the original bytes for post-mortem")
	}

	// 新游标能正常写盘，并且下次启动读得回来——不再无限循环回填。
	s.Set("claude", "sess", MsgCursor{LastMsgID: "m9", LastMsgTime: now})
	if err := s.Save(); err != nil {
		t.Fatalf("save after recovery: %v", err)
	}
	again, err := loadCursorStoreFrom(path, now.Add(time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	if again.IsEmpty() || again.cursors["claude:sess"].LastMsgID != "m9" {
		t.Errorf("after one backfill the cursors must persist across restarts, got %+v", again.cursors)
	}
	if len(corruptBackups(t, path)) != 1 {
		t.Error("a healthy reload must not create more backups")
	}
}

// 各种"既不是 envelope 也不是老格式"的损坏形态都走恢复路径。
func TestLoadCursorStore_CorruptShapes(t *testing.T) {
	for name, content := range map[string]string{
		"truncated json":             `{"schema_version":3,"cursors":{"a":`,
		"binary garbage":             "\x00\x01\x02\xff\xfe",
		"wrong top level":            `[1,2,3]`,
		"envelope, bad cursors type": `{"schema_version":3,"cursors":"nope"}`,
		"plain text":                 `hello`,
	} {
		t.Run(name, func(t *testing.T) {
			path := withTempCursorPath(t)
			if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
				t.Fatal(err)
			}
			s, err := loadCursorStoreFrom(path, time.Now())
			if err != nil || s == nil || s.path != path || !s.IsEmpty() {
				t.Fatalf("store=%+v err=%v", s, err)
			}
		})
	}
}

// 反复损坏只保留最近 2 份备份。
func TestQuarantineCorruptCursors_KeepsNewestTwo(t *testing.T) {
	path := withTempCursorPath(t)
	base := time.Date(2026, 9, 29, 10, 0, 0, 0, time.UTC)
	for i := 0; i < 5; i++ {
		writeCorruptCursors(t, path)
		if _, err := loadCursorStoreFrom(path, base.Add(time.Duration(i)*time.Minute)); err != nil {
			t.Fatal(err)
		}
	}
	backups := corruptBackups(t, path)
	if len(backups) != corruptBackupKeep {
		t.Fatalf("kept %d backups, want %d: %v", len(backups), corruptBackupKeep, backups)
	}
	// 保留下来的应是最新的两份（文件名里的 UTC 时间戳可按字典序比较）。
	want := []string{
		path + corruptSuffix + base.Add(3*time.Minute).Format("20060102T150405.000000000"),
		path + corruptSuffix + base.Add(4*time.Minute).Format("20060102T150405.000000000"),
	}
	for i, w := range want {
		if backups[i] != w {
			t.Errorf("backup %d = %s, want %s", i, filepath.Base(backups[i]), filepath.Base(w))
		}
	}
}

// 无落盘路径的 store（state 目录都解析不出来）纯内存运行：Save 报错但绝不在 cwd 留下 ".tmp" 垃圾文件。
func TestMsgCursorStore_SaveWithoutPathDoesNotTouchDisk(t *testing.T) {
	cwd := t.TempDir()
	old, _ := os.Getwd()
	if err := os.Chdir(cwd); err != nil {
		t.Skipf("cannot chdir: %v", err)
	}
	defer func() { _ = os.Chdir(old) }()

	s := &MsgCursorStore{cursors: make(map[string]MsgCursor)}
	s.Set("claude", "a", MsgCursor{LastMsgID: "1"})
	if err := s.Save(); err == nil {
		t.Error("saving a path-less store must report an error rather than pretend success")
	}
	entries, _ := os.ReadDir(cwd)
	if len(entries) != 0 {
		t.Errorf("Save left files in cwd: %v", entries)
	}
}

// 正常 / 老格式 / 未来版本不受影响：仍是"重置但不备份"。
func TestLoadCursorStore_NonCorruptResetsDoNotCreateBackups(t *testing.T) {
	path := withTempCursorPath(t)
	env := cursorsFile{SchemaVersion: CursorSchemaVersion + 1, Cursors: map[string]MsgCursor{"a:b": {LastMsgID: "x"}}}
	data, _ := json.Marshal(env)
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := loadCursorStoreFrom(path, time.Now()); err != nil {
		t.Fatal(err)
	}
	if n := len(corruptBackups(t, path)); n != 0 {
		t.Errorf("schema reset created %d corrupt backups", n)
	}
}

// 升级 schema 会让全员同时进入全量回填：这个常量不能被顺手改掉。改它之前必须先满足常量旁注释里的前置条件。
func TestCursorSchemaVersionIsPinned(t *testing.T) {
	if CursorSchemaVersion != 3 {
		t.Fatalf("CursorSchemaVersion=%d: bumping it forces EVERY agent into a 30-day full backfill at once. "+
			"Read the warning above the constant (byte budget + gray release + server rate limiting + batched rollout) "+
			"and update this test deliberately.", CursorSchemaVersion)
	}
}
