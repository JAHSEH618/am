package reporter

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"
)

var sentT0 = time.Date(2026, 9, 29, 12, 0, 0, 0, time.UTC)

func TestSentState_RoundTrip(t *testing.T) {
	path := filepath.Join(t.TempDir(), sentStateFileName)
	sent := map[string]sentSession{
		"claude:a": {fingerprint: 0xdeadbeefcafef00d, sentAt: sentT0.Add(-time.Minute), idle: true},
		"codex:b":  {fingerprint: 1, sentAt: sentT0.Add(-5 * time.Minute), idle: true},
	}
	if err := saveSentState(path, persistableSent(sent, sentT0)); err != nil {
		t.Fatal(err)
	}
	got := loadSentState(path, sentT0)
	if len(got) != 2 {
		t.Fatalf("loaded %d entries, want 2", len(got))
	}
	// uint64 指纹必须无损往返（十六进制字符串存储，避免 float64 丢精度）。
	if got["claude:a"].fingerprint != 0xdeadbeefcafef00d {
		t.Errorf("fingerprint lost precision: %x", got["claude:a"].fingerprint)
	}
	if !got["claude:a"].sentAt.Equal(sent["claude:a"].sentAt) || !got["claude:a"].idle {
		t.Errorf("entry mangled: %+v", got["claude:a"])
	}
}

// 只落盘 idle 且仍在强制重发周期内的条目。
func TestPersistableSent_FiltersNonIdleAndExpired(t *testing.T) {
	sent := map[string]sentSession{
		"claude:idle-fresh":   {fingerprint: 1, sentAt: sentT0.Add(-time.Minute), idle: true},
		"claude:active":       {fingerprint: 2, sentAt: sentT0.Add(-time.Minute), idle: false},
		"claude:idle-expired": {fingerprint: 3, sentAt: sentT0.Add(-unchangedResyncInterval - time.Second), idle: true},
		"claude:edge":         {fingerprint: 4, sentAt: sentT0.Add(-unchangedResyncInterval + time.Second), idle: true},
	}
	got := persistableSent(sent, sentT0)
	if len(got) != 2 {
		t.Fatalf("persistable = %v, want idle-fresh and edge only", got)
	}
	for _, k := range []string{"claude:idle-fresh", "claude:edge"} {
		if _, ok := got[k]; !ok {
			t.Errorf("%s should be persisted", k)
		}
	}
}

// 加载时丢掉过期 / 来自未来 / 非法的条目；强制重发的时间基准（sentAt）原样保留。
func TestLoadSentState_DropsStaleFutureAndInvalidEntries(t *testing.T) {
	path := filepath.Join(t.TempDir(), sentStateFileName)
	f := sentStateFile{SchemaVersion: sentStateSchema, Sessions: map[string]sentStateEntry{
		"ok":          {Fingerprint: "ff", SentAt: sentT0.Add(-time.Minute)},
		"expired":     {Fingerprint: "ff", SentAt: sentT0.Add(-time.Hour)},
		"future":      {Fingerprint: "ff", SentAt: sentT0.Add(2 * time.Hour)},
		"bad-fp":      {Fingerprint: "not-hex", SentAt: sentT0.Add(-time.Minute)},
		"zero-time":   {Fingerprint: "ff"},
		"just-inside": {Fingerprint: "1", SentAt: sentT0.Add(-unchangedResyncInterval + time.Second)},
	}}
	data, _ := json.Marshal(f)
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	got := loadSentState(path, sentT0)
	if len(got) != 2 {
		t.Fatalf("loaded %v, want only ok and just-inside", got)
	}
	if !got["ok"].sentAt.Equal(sentT0.Add(-time.Minute)) {
		t.Error("sentAt must be preserved so the 15-minute forced resync keeps its meaning across restarts")
	}
}

// 文件缺失 / 空 / 损坏 / 版本不符：一律当作没有这个文件（退化为旧行为），绝不报错或崩溃。
func TestLoadSentState_CorruptOrMismatchedIsIgnored(t *testing.T) {
	dir := t.TempDir()
	valid, _ := json.Marshal(sentStateFile{SchemaVersion: sentStateSchema, Sessions: map[string]sentStateEntry{
		"a": {Fingerprint: "1", SentAt: sentT0.Add(-time.Minute)},
	}})
	wrongVersion, _ := json.Marshal(sentStateFile{SchemaVersion: sentStateSchema + 1, Sessions: map[string]sentStateEntry{
		"a": {Fingerprint: "1", SentAt: sentT0.Add(-time.Minute)},
	}})
	cases := []struct {
		name    string
		content []byte // nil = 文件不存在
		want    int
	}{
		{"valid", valid, 1},
		{"missing", nil, 0},
		{"empty", []byte{}, 0},
		{"garbage", []byte("{not json"), 0},
		{"truncated", valid[:len(valid)/2], 0},
		{"json but wrong shape", []byte(`[1,2,3]`), 0},
		{"schema version mismatch", wrongVersion, 0},
		{"no schema version", []byte(`{"sessions":{"a":{"fp":"1","sent_at":"2026-09-29T11:59:00Z"}}}`), 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			path := filepath.Join(dir, tc.name+".json")
			if tc.content != nil {
				if err := os.WriteFile(path, tc.content, 0o600); err != nil {
					t.Fatal(err)
				}
			}
			if got := loadSentState(path, sentT0); len(got) != tc.want {
				t.Errorf("loaded %d entries, want %d", len(got), tc.want)
			}
		})
	}
	if got := loadSentState("", sentT0); len(got) != 0 {
		t.Error("empty path disables persistence")
	}
}

// 落盘文件大小有上限：只留最近发送的 sentStateMaxEntries 条。
func TestPersistableSent_CapsEntries(t *testing.T) {
	sent := make(map[string]sentSession, sentStateMaxEntries+500)
	for i := 0; i < sentStateMaxEntries+500; i++ {
		sent[fmt.Sprintf("claude:s%05d", i)] = sentSession{
			fingerprint: uint64(i),
			sentAt:      sentT0.Add(-time.Duration(i) * time.Millisecond), // i 越大越旧
			idle:        true,
		}
	}
	got := persistableSent(sent, sentT0)
	if len(got) != sentStateMaxEntries {
		t.Fatalf("kept %d, want cap %d", len(got), sentStateMaxEntries)
	}
	if _, ok := got["claude:s00000"]; !ok {
		t.Error("the newest entry must survive the cap")
	}
	if _, ok := got[fmt.Sprintf("claude:s%05d", sentStateMaxEntries+499)]; ok {
		t.Error("the oldest entry must be evicted first")
	}
}

// 写盘是原子的（不留 .tmp）、稳态下不重复写：key→指纹没变就不碰磁盘。
func TestPersistSentIfChanged_OnlyWritesOnRealChange(t *testing.T) {
	dir := t.TempDir()
	r := &Reporter{nowFn: func() time.Time { return sentT0 }}
	r.initSentState(dir, sentT0)

	r.sent["claude:a"] = sentSession{fingerprint: 7, sentAt: sentT0, idle: true}
	r.persistSentIfChanged()
	if _, err := os.Stat(r.sentPath); err != nil {
		t.Fatalf("first change must be persisted: %v", err)
	}
	if _, err := os.Stat(r.sentPath + ".tmp"); !os.IsNotExist(err) {
		t.Error("atomic write must not leave the .tmp file behind")
	}

	// 把文件时间调到很久以前，再"无变化"地提交：不应被重写。
	old := time.Unix(1_000_000, 0)
	if err := os.Chtimes(r.sentPath, old, old); err != nil {
		t.Fatal(err)
	}
	r.sent["claude:a"] = sentSession{fingerprint: 7, sentAt: sentT0.Add(time.Minute), idle: true} // 只有 sentAt 变
	r.persistSentIfChanged()
	if st, _ := os.Stat(r.sentPath); !st.ModTime().Equal(old) {
		t.Error("an unchanged key→fingerprint view (only sentAt refreshed by a forced resync) must not rewrite the file")
	}

	// 非 idle 条目不进落盘视图，因此也不触发写盘。
	r.sent["claude:busy"] = sentSession{fingerprint: 9, sentAt: sentT0, idle: false}
	r.persistSentIfChanged()
	if st, _ := os.Stat(r.sentPath); !st.ModTime().Equal(old) {
		t.Error("non-idle sessions are never persisted")
	}

	// 指纹变化 → 写盘。
	r.sent["claude:a"] = sentSession{fingerprint: 8, sentAt: sentT0, idle: true}
	r.persistSentIfChanged()
	if st, _ := os.Stat(r.sentPath); st.ModTime().Equal(old) {
		t.Error("a changed fingerprint must be persisted")
	}
	if got := loadSentState(r.sentPath, sentT0); got["claude:a"].fingerprint != 8 {
		t.Errorf("persisted fingerprint = %d, want 8", got["claude:a"].fingerprint)
	}

	// 会话离开窗口（被淘汰）→ 视图变化 → 写盘，文件里也随之清掉。
	delete(r.sent, "claude:a")
	r.persistSentIfChanged()
	if got := loadSentState(r.sentPath, sentT0); len(got) != 0 {
		t.Errorf("evicted session lingered on disk: %v", got)
	}
}

// 落盘失败（目录不可写等）只影响重启后的优化，不影响上报。
func TestPersistSentIfChanged_FailureIsHarmless(t *testing.T) {
	blocker := filepath.Join(t.TempDir(), "file")
	if err := os.WriteFile(blocker, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	r := &Reporter{nowFn: func() time.Time { return sentT0 }}
	r.initSentState(filepath.Join(blocker, "sub"), sentT0) // 父路径是个普通文件：写必然失败
	r.sent["claude:a"] = sentSession{fingerprint: 1, sentAt: sentT0, idle: true}
	r.persistSentIfChanged() // 不得 panic
	if len(r.sentSaved) != 0 {
		t.Error("a failed write must not be recorded as saved (it should be retried at the next commit)")
	}
	// 未配置路径（旧测试 / 极端环境）：整个功能关闭。
	(&Reporter{}).persistSentIfChanged()
}
