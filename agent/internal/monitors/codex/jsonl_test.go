// Codex JSONL 模块单元测试。
//
// gz
package codex

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/am/aiwatch-agent/internal/monitors/common"
)

const fixtureJSONL = `{"timestamp":"2026-05-07T10:00:00Z","type":"session_meta","payload":{"id":"sess-1","cwd":"/tmp/proj","cli_version":"0.5.0"}}
{"timestamp":"2026-05-07T10:00:01Z","type":"turn_context","payload":{"cwd":"/tmp/proj","model":"gpt-5","git":{"branch":"main"}}}
{"timestamp":"2026-05-07T10:00:02Z","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]}}
{"timestamp":"2026-05-07T10:00:03Z","type":"response_item","payload":{"type":"function_call","name":"apply_patch","arguments":"{\"file\":\"x.go\"}"}}
{"timestamp":"2026-05-07T10:00:04Z","type":"response_item","payload":{"type":"function_call_output"}}
{"timestamp":"2026-05-07T10:00:05Z","type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"done"}]}}
{"timestamp":"2026-05-07T10:00:06Z","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":120,"cached_input_tokens":50,"output_tokens":40,"reasoning_output_tokens":10}}}}
`

func writeFixture(t *testing.T, body string) string {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "rollout-1.jsonl")
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		t.Fatalf("write fixture: %v", err)
	}
	return path
}

func TestParseFile_Codex(t *testing.T) {
	path := writeFixture(t, fixtureJSONL)
	ps, _, err := parseFile(path)
	if err != nil {
		t.Fatalf("parseFile: %v", err)
	}
	if ps.SessionID != "sess-1" {
		t.Errorf("SessionID = %q", ps.SessionID)
	}
	if ps.CWD != "/tmp/proj" {
		t.Errorf("CWD = %q", ps.CWD)
	}
	if ps.GitBranch != "main" {
		t.Errorf("GitBranch = %q", ps.GitBranch)
	}
	if ps.Model != "gpt-5" {
		t.Errorf("Model = %q", ps.Model)
	}
	if ps.UserMessages != 1 || ps.AssistantMessages != 1 {
		t.Errorf("messages = %d/%d", ps.UserMessages, ps.AssistantMessages)
	}
	if ps.InputTokens != 120 || ps.OutputTokens != 50 || ps.CacheRead != 50 {
		t.Errorf("tokens = in:%d out:%d cache:%d", ps.InputTokens, ps.OutputTokens, ps.CacheRead)
	}
	if ps.BubbleStatus != common.BubbleWaitingUser {
		t.Errorf("BubbleStatus = %q want waiting_for_user", ps.BubbleStatus)
	}
	if len(ps.RecentTools) != 1 || ps.RecentTools[0].Name != "Edit" {
		t.Errorf("RecentTools = %+v (apply_patch should normalize to Edit)", ps.RecentTools)
	}

	// v2.0.1 防重复消息：每条 RecentMessage 必须带稳定的 ExternalMessageID，且彼此不同。
	// 服务端 (ai_session_id, external_message_id) 唯一索引依赖此字段做去重，留空会
	// 退化成"每个 reporter tick 都把最近 N 条重新 INSERT"的脏数据 —— 与 v1.6 hermes /
	// openharness 同款问题。
	if len(ps.RecentMessages) == 0 {
		t.Fatalf("RecentMessages should not be empty (fixture has user / assistant / tool)")
	}
	seen := make(map[string]int, len(ps.RecentMessages))
	for i, m := range ps.RecentMessages {
		if m.ExternalMessageID == "" {
			t.Errorf("RecentMessages[%d].ExternalMessageID empty (role=%s)", i, m.Role)
			continue
		}
		seen[m.ExternalMessageID]++
	}
	for id, n := range seen {
		if n > 1 {
			t.Errorf("ExternalMessageID %q appeared %d times in RecentMessages", id, n)
		}
	}
}

// Codex 在上下文压缩 / 重置时，total_token_usage 累计计数会回退变小。
// 旧逻辑 dIn = newIn - prevIn 会算出大负增量并上报，服务端求和后「今日 Token」变负、
// 前端越界。此处验证：计数回退时不得产生负向 ActivityDelta，但基线必须推进到新值，
// 这样压缩后继续累积的增量仍按新基线正确计算。
func TestCodex_TokenCountReset_NoNegativeDelta(t *testing.T) {
	body := `{"timestamp":"2026-05-07T10:00:00Z","type":"session_meta","payload":{"id":"sess-reset","cwd":"/tmp/proj","cli_version":"0.5.0"}}
{"timestamp":"2026-05-07T10:00:06Z","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":200000,"cached_input_tokens":0,"output_tokens":5000,"reasoning_output_tokens":0}}}}
{"timestamp":"2026-05-07T10:00:07Z","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":20000,"cached_input_tokens":0,"output_tokens":300,"reasoning_output_tokens":0}}}}
{"timestamp":"2026-05-07T10:00:08Z","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":25000,"cached_input_tokens":0,"output_tokens":700,"reasoning_output_tokens":0}}}}
`
	path := writeFixture(t, body)
	ps, _, err := parseFile(path)
	if err != nil {
		t.Fatalf("parseFile: %v", err)
	}

	var sumIn, sumOut int64
	for _, d := range ps.ActivityDeltas {
		if d.Source != common.ActivitySourceTokenCount {
			continue
		}
		if d.InputTokensDelta < 0 || d.OutputTokensDelta < 0 {
			t.Errorf("negative token delta emitted: in=%d out=%d", d.InputTokensDelta, d.OutputTokensDelta)
		}
		sumIn += d.InputTokensDelta
		sumOut += d.OutputTokensDelta
	}

	// 基线必须推进到最后一次上报值（25000 / 700），否则后续 tick 又会算出负增量。
	if ps.InputTokens != 25000 || ps.OutputTokens != 700 {
		t.Errorf("baseline = in:%d out:%d, want in:25000 out:700", ps.InputTokens, ps.OutputTokens)
	}
	// 第一段 +200000/+5000，回退段裁 0，压缩后回升段 +5000/+400。
	if sumIn != 205000 || sumOut != 5400 {
		t.Errorf("summed deltas = in:%d out:%d, want in:205000 out:5400", sumIn, sumOut)
	}
}

func TestCodex_ParseUserMessage_nonInputTextPreservesDollarSkill(t *testing.T) {
	body := `{"timestamp":"2026-05-07T10:00:00Z","type":"session_meta","payload":{"id":"sess-skill","cwd":"/tmp/proj","cli_version":"0.99.0"}}
{"timestamp":"2026-05-07T10:00:02Z","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"text","text":"$skill-creator 这是什么"}]}}
`
	path := writeFixture(t, body)
	ps, _, err := parseFile(path)
	if err != nil {
		t.Fatalf("parseFile: %v", err)
	}
	if ps.UserMessages != 1 {
		t.Fatalf("want 1 user message, got %d", ps.UserMessages)
	}
	var got string
	for i := len(ps.RecentMessages) - 1; i >= 0; i-- {
		m := ps.RecentMessages[i]
		if strings.EqualFold(m.Role, "user") {
			got = m.Text
			break
		}
	}
	if !strings.Contains(got, "$skill-creator") {
		t.Fatalf("user RecentMessage text missing $skill-creator, got=%q", got)
	}
}

func TestProvider_Codex_Snapshot(t *testing.T) {
	root := t.TempDir()
	t.Setenv("AM_CODEX_DIR", root)

	subDir := filepath.Join(root, "sessions", "2026", "05", "07")
	if err := os.MkdirAll(subDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(subDir, "rollout-x.jsonl"), []byte(fixtureJSONL), 0o644); err != nil {
		t.Fatal(err)
	}

	// 写一个 session_index.jsonl 以便测试 thread_name 注入。
	if err := os.WriteFile(filepath.Join(root, "session_index.jsonl"),
		[]byte(`{"id":"sess-1","thread_name":"重构 main.go"}`+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	p := New("/tmp/proj")
	// fixture 时间在 2026-05-07；宿主真实日期走远后默认 48h lookback 会筛掉整张会话，
	// 使本测与日历耦合。拉大窗口只影响测试替身目录。
	p.SetLookback(10 * 365 * 24 * time.Hour)
	snap, err := p.Snapshot(t.Context())
	if err != nil {
		t.Fatalf("Snapshot: %v", err)
	}
	if snap.Type != TypeCode {
		t.Fatalf("type = %q", snap.Type)
	}
	if len(snap.Sessions) != 1 {
		t.Fatalf("want 1 session, got %d", len(snap.Sessions))
	}
	got := snap.Sessions[0]
	if got.SessionID != "sess-1" {
		t.Errorf("SessionID = %q", got.SessionID)
	}
	if got.Cwd != "/tmp/proj" {
		t.Errorf("Cwd = %q", got.Cwd)
	}
	if got.Model != "gpt-5" {
		t.Errorf("Model = %q", got.Model)
	}
}

func TestParseCodexParentThreadID(t *testing.T) {
	source := []byte(`{"subagent":{"thread_spawn":{"parent_thread_id":"parent-1","depth":1}}}`)
	if got := parseCodexParentThreadID(source); got != "parent-1" {
		t.Fatalf("parent=%q want parent-1", got)
	}
}

func TestProvider_Codex_SubagentMerge(t *testing.T) {
	root := t.TempDir()
	t.Setenv("AM_CODEX_DIR", root)
	subDir := filepath.Join(root, "sessions", "2026", "05", "07")
	if err := os.MkdirAll(subDir, 0o755); err != nil {
		t.Fatal(err)
	}

	parentBody := `{"timestamp":"2026-05-07T10:00:00Z","type":"session_meta","payload":{"id":"parent-1","cwd":"/tmp/proj","cli_version":"0.5.0"}}
{"timestamp":"2026-05-07T10:00:02Z","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"review all"}]}}
`
	childBody := `{"timestamp":"2026-05-07T10:00:01Z","type":"session_meta","payload":{"id":"child-1","cwd":"/tmp/proj","cli_version":"0.5.0","source":{"subagent":{"thread_spawn":{"parent_thread_id":"parent-1"}}}}}
{"timestamp":"2026-05-07T10:00:03Z","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"explore backend"}]}}
`
	if err := os.WriteFile(filepath.Join(subDir, "rollout-parent.jsonl"), []byte(parentBody), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(subDir, "rollout-child.jsonl"), []byte(childBody), 0o644); err != nil {
		t.Fatal(err)
	}

	p := New("/tmp/proj")
	p.SetLookback(10 * 365 * 24 * time.Hour)
	snap, err := p.Snapshot(t.Context())
	if err != nil {
		t.Fatalf("Snapshot: %v", err)
	}
	if len(snap.Sessions) != 1 {
		t.Fatalf("want 1 merged session, got %d", len(snap.Sessions))
	}
	got := snap.Sessions[0]
	if got.SessionID != "parent-1" {
		t.Fatalf("SessionID=%q want parent-1", got.SessionID)
	}
	if got.UserMessages != 2 {
		t.Fatalf("UserMessages=%d want 2", got.UserMessages)
	}
}
