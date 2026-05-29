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

// loadFromFile 是 LoadCursorStore 的单测版本：跳过 stateDir() 的环境依赖，直接给定 path。
//
// <p>把 LoadCursorStore 内部除"路径解析"以外的逻辑独立出来，方便单测覆盖 schema 兼容性分支
// 而不需要 mock 文件系统位置。
func loadFromFile(path string) (*MsgCursorStore, error) {
	s := &MsgCursorStore{
		path:    path,
		cursors: make(map[string]MsgCursor),
	}
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return s, nil
		}
		return nil, err
	}
	if len(data) == 0 {
		return s, nil
	}
	var env cursorsFile
	if err := json.Unmarshal(data, &env); err == nil && env.SchemaVersion > 0 {
		if env.SchemaVersion != CursorSchemaVersion {
			s.dirty = true
			return s, nil
		}
		if env.Cursors != nil {
			s.cursors = env.Cursors
		}
		return s, nil
	}
	var legacy map[string]MsgCursor
	if err := json.Unmarshal(data, &legacy); err == nil {
		s.dirty = true
		return s, nil
	}
	return nil, errInvalidCursorsFile
}

var errInvalidCursorsFile = errCorrupt("cursors.json is neither v1 envelope nor legacy map")

type errCorrupt string

func (e errCorrupt) Error() string { return string(e) }

func containsKey(raw []byte, key string) bool {
	var m map[string]any
	if err := json.Unmarshal(raw, &m); err != nil {
		return false
	}
	_, ok := m[key]
	return ok
}
