// Package trae 采集 TRAE / TRAE SOLO 的 VS Code 风格 workspace state.vscdb。
//
// 已知会话键：ChatStore、inputHistory、memento/icube-ai-ng-chat-storage*。
// TRAE 不同地区版/版本的 JSON 字段会变化，因此本解析器只投影稳定字段并忽略未知字段。
package trae

import (
	"context"
	"crypto/sha1"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/am/aiwatch-agent/internal/gitinfo"
	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"

	_ "modernc.org/sqlite"
)

const TypeCode = "trae"

type Provider struct {
	watchDir string
	lookback time.Duration
	gitMu    sync.Mutex
	gitCache map[string]gitinfo.Info
}

type parsedSession struct {
	ID                string
	CWD               string
	Model             string
	StartedAt         time.Time
	LastActivity      time.Time
	UserMessages      int
	AssistantMessages int
	InputTokens       int64
	OutputTokens      int64
	Messages          []monitor.Message
	Tools             []monitor.Tool
	Deltas            []monitor.ActivityDelta
}

func New(watchDir string) *Provider {
	return &Provider{watchDir: watchDir, lookback: monitor.DefaultLookback, gitCache: make(map[string]gitinfo.Info)}
}

func (p *Provider) Type() string          { return TypeCode }
func (p *Provider) TargetVersion() string { return "trae" }
func (p *Provider) SetLookback(d time.Duration) {
	if d <= 0 {
		d = monitor.DefaultLookback
	}
	p.lookback = d
}
func (p *Provider) WatchHints() []string { return traeRoots() }

func (p *Provider) IsInstalled() bool {
	for _, root := range traeRoots() {
		if st, err := os.Stat(root); err == nil && st.IsDir() {
			return true
		}
	}
	return false
}

func (p *Provider) Snapshot(ctx context.Context) (monitor.Snapshot, error) {
	_ = ctx
	now := time.Now()
	byID := make(map[string]*parsedSession)
	for _, dbPath := range discoverStateDBs() {
		st, err := os.Stat(dbPath)
		if err != nil {
			continue
		}
		for _, ps := range readStateDB(dbPath, st.ModTime()) {
			if ps == nil || ps.ID == "" || (!ps.LastActivity.IsZero() && ps.LastActivity.Before(now.Add(-p.lookback))) {
				continue
			}
			if old := byID[ps.ID]; old == nil || ps.LastActivity.After(old.LastActivity) {
				byID[ps.ID] = ps
			}
		}
	}
	ids := make([]string, 0, len(byID))
	for id := range byID {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	sessions := make([]monitor.Session, 0, len(ids))
	for _, id := range ids {
		sessions = append(sessions, p.toMonitor(byID[id], now))
	}
	return monitor.Snapshot{Type: TypeCode, TargetVersion: p.TargetVersion(), CapturedAt: monitor.Now(), Sessions: sessions}, nil
}

func readStateDB(path string, fallback time.Time) []*parsedSession {
	db, err := sql.Open("sqlite", path+"?mode=ro&_pragma=busy_timeout(1500)")
	if err != nil {
		return nil
	}
	defer func() { _ = db.Close() }()
	rows, err := db.Query(`
		SELECT key, value FROM ItemTable
		WHERE key='ChatStore' OR key='inputHistory'
		   OR key LIKE 'memento/icube-ai-ng-chat-storage%'
		   OR lower(key) LIKE '%trae%chat%'
	`)
	if err != nil {
		return nil
	}
	defer func() { _ = rows.Close() }()
	var out []*parsedSession
	for rows.Next() {
		var key string
		var raw []byte
		if rows.Scan(&key, &raw) != nil || len(raw) == 0 {
			continue
		}
		out = append(out, sessionsFromValue(raw, path+":"+key, fallback)...)
	}
	return out
}

func sessionsFromValue(raw []byte, source string, fallback time.Time) []*parsedSession {
	value, ok := decodeNestedJSON(raw)
	if !ok {
		return nil
	}
	var candidates []map[string]any
	collectSessionCandidates(value, &candidates)
	out := make([]*parsedSession, 0, len(candidates))
	for i, candidate := range candidates {
		ps := parseSessionMap(candidate, source, i, fallback)
		if ps != nil {
			out = append(out, ps)
		}
	}
	return out
}

func decodeNestedJSON(raw []byte) (any, bool) {
	var v any
	if json.Unmarshal(raw, &v) != nil {
		return nil, false
	}
	for i := 0; i < 3; i++ {
		s, ok := v.(string)
		if !ok {
			break
		}
		var next any
		if json.Unmarshal([]byte(s), &next) != nil {
			break
		}
		v = next
	}
	return v, true
}

func collectSessionCandidates(v any, out *[]map[string]any) {
	switch x := v.(type) {
	case []any:
		for _, item := range x {
			collectSessionCandidates(item, out)
		}
	case map[string]any:
		if hasMessageArray(x) {
			*out = append(*out, x)
			return
		}
		for _, key := range []string{"list", "sessions", "conversations", "chats", "items", "history"} {
			if child, ok := x[key]; ok {
				collectSessionCandidates(child, out)
			}
		}
	}
}

func hasMessageArray(m map[string]any) bool {
	for _, key := range []string{"messages", "turns", "history"} {
		if _, ok := m[key].([]any); ok {
			return true
		}
	}
	return false
}

func parseSessionMap(m map[string]any, source string, index int, fallback time.Time) *parsedSession {
	msgs := firstArray(m, "messages", "turns", "history")
	if len(msgs) == 0 {
		return nil
	}
	id := firstString(m, "id", "sessionId", "session_id", "conversationId", "conversation_id", "chatId", "taskId")
	if id == "" {
		id = stableID(source + ":" + strconv.Itoa(index))
	}
	ps := &parsedSession{
		ID:           id,
		CWD:          firstString(m, "cwd", "workingDirectory", "working_dir", "workspace", "workspacePath", "projectPath"),
		Model:        firstString(m, "model", "modelId", "model_id"),
		StartedAt:    firstTime(m, "createdAt", "created_at", "createTime", "startTime", "timestamp"),
		LastActivity: firstTime(m, "updatedAt", "updated_at", "updateTime", "lastMessageAt", "timestamp"),
	}
	for i, rawMsg := range msgs {
		mm, ok := rawMsg.(map[string]any)
		if !ok {
			continue
		}
		parseMessage(ps, mm, i, fallback)
	}
	if ps.StartedAt.IsZero() {
		ps.StartedAt = fallback
	}
	if ps.LastActivity.IsZero() {
		ps.LastActivity = fallback
	}
	if ps.Model == "" {
		for _, rawMsg := range msgs {
			if mm, ok := rawMsg.(map[string]any); ok {
				if v := firstString(mm, "model", "modelId", "model_id"); v != "" {
					ps.Model = v
					break
				}
			}
		}
	}
	return ps
}

func parseMessage(ps *parsedSession, m map[string]any, index int, fallback time.Time) {
	role := strings.ToLower(firstString(m, "role", "type", "sender"))
	switch role {
	case "human":
		role = "user"
	case "ai", "bot":
		role = "assistant"
	}
	if role != "user" && role != "assistant" && role != "tool" {
		return
	}
	ts := firstTime(m, "timestamp", "createdAt", "created_at", "time", "updateTime")
	if ts.IsZero() {
		ts = fallback.Add(time.Duration(index) * time.Microsecond)
	}
	if ps.StartedAt.IsZero() || ts.Before(ps.StartedAt) {
		ps.StartedAt = ts
	}
	if ts.After(ps.LastActivity) {
		ps.LastActivity = ts
	}
	parts, tools := traeParts(m)
	if len(parts) == 0 {
		return
	}
	msgID := firstString(m, "id", "messageId", "message_id", "uuid")
	msg := monitor.Message{ExternalMessageID: msgID, Role: role, ContentParts: parts, Timestamp: monitor.LocalTime(ts)}
	monitor.FinalizeMessage(&msg)
	if msg.ExternalMessageID == "" {
		msg.ExternalMessageID = common.SyntheticMessageID(ps.ID, strconv.Itoa(index), role, msg.Text)
	}
	in := firstInt64(m, "inputTokens", "input_tokens", "promptTokens", "prompt_tokens")
	out := firstInt64(m, "outputTokens", "output_tokens", "completionTokens", "completion_tokens")
	msg.InputTokens, msg.OutputTokens = int(in), int(out)
	ps.Messages = append(ps.Messages, msg)
	if role == "user" {
		ps.UserMessages++
		common.AppendActivityDelta(&ps.Deltas, ts, msg.ExternalMessageID, common.ActivitySourceUserTurn, 0, 0, 1)
	}
	if role == "assistant" {
		ps.AssistantMessages++
		ps.InputTokens += in
		ps.OutputTokens += out
		common.AppendActivityDelta(&ps.Deltas, ts, msg.ExternalMessageID, common.ActivitySourceAssistantTurn, in, out, 1)
	}
	for _, tool := range tools {
		ps.Tools = append(ps.Tools, monitor.Tool{Name: tool, Timestamp: monitor.LocalTime(ts)})
	}
}

func traeParts(m map[string]any) (parts []monitor.ContentPart, tools []string) {
	if parsed := firstArray(m, "parsedQuery"); len(parsed) > 0 {
		for _, item := range parsed {
			if s, ok := item.(string); ok && strings.TrimSpace(s) != "" {
				parts = append(parts, monitor.ContentPart{Type: "text", Text: s, SortOrder: len(parts)})
			}
		}
	}
	content := firstNonNil(m["content"], m["text"], m["answer"], m["message"])
	switch x := content.(type) {
	case string:
		if strings.TrimSpace(x) != "" {
			parts = append(parts, monitor.ContentPart{Type: "text", Text: x, SortOrder: len(parts)})
		}
	case []any:
		for _, item := range x {
			if s, ok := item.(string); ok {
				parts = append(parts, monitor.ContentPart{Type: "text", Text: s, SortOrder: len(parts)})
				continue
			}
			if pm, ok := item.(map[string]any); ok {
				appendPartMap(&parts, &tools, pm)
			}
		}
	case map[string]any:
		appendPartMap(&parts, &tools, x)
	}
	for _, raw := range firstArray(m, "toolCalls", "tool_calls") {
		if tm, ok := raw.(map[string]any); ok {
			appendPartMap(&parts, &tools, map[string]any{"type": "tool_call", "name": firstString(tm, "name", "toolName"), "arguments": firstNonNil(tm["arguments"], tm["input"])})
		}
	}
	return parts, tools
}

func appendPartMap(parts *[]monitor.ContentPart, tools *[]string, m map[string]any) {
	typ := strings.ToLower(firstString(m, "type"))
	if strings.Contains(typ, "tool") || firstString(m, "toolName", "tool_name") != "" {
		name := common.NormalizeToolName(firstString(m, "name", "toolName", "tool_name"))
		args, _ := json.Marshal(firstNonNil(m["arguments"], m["input"]))
		*parts = append(*parts, monitor.ContentPart{Type: "tool_call", ToolName: name, ArgumentsJSON: string(args), SortOrder: len(*parts)})
		if name != "" {
			*tools = append(*tools, name)
		}
		return
	}
	text := firstString(m, "text", "content", "value")
	if text == "" {
		return
	}
	partType := "text"
	if typ == "thinking" || typ == "reasoning" {
		partType = "thinking"
	}
	*parts = append(*parts, monitor.ContentPart{Type: partType, Text: text, SortOrder: len(*parts)})
}

func (p *Provider) toMonitor(ps *parsedSession, now time.Time) monitor.Session {
	cwd := ps.CWD
	if cwd == "" && len(ps.Messages) > 0 {
		cwd = p.watchDir
	}
	gi := p.lookupGit(cwd)
	project := gi.ProjectName
	if project == "" && cwd != "" {
		project = filepath.Base(cwd)
	}
	status := common.ResolveActivity(common.SessionLike{LastActivity: ps.LastActivity, RecentTools: ps.Tools}, now)
	current := ""
	if len(ps.Tools) > 0 && status != common.StatusIdle && status != common.StatusWaiting {
		current = ps.Tools[len(ps.Tools)-1].Name
	}
	return monitor.Session{SessionID: ps.ID, Cwd: cwd, CwdHash: gi.ProjectPathHash, GitBranch: gi.BranchName, RepoURL: gi.RepoURL,
		ProjectName: project, Model: ps.Model, Status: status, CurrentTool: current, StartedAt: monitor.LocalTime(ps.StartedAt), LastActivity: monitor.LocalTime(ps.LastActivity),
		UserMessages: ps.UserMessages, AssistantMessages: ps.AssistantMessages, SnapshotMessageCount: len(ps.Messages), InputTokens: ps.InputTokens, OutputTokens: ps.OutputTokens,
		RecentMessages: ps.Messages, RecentTools: ps.Tools, ActivityDeltas: ps.Deltas}
}

func (p *Provider) lookupGit(cwd string) gitinfo.Info {
	if cwd == "" {
		return gitinfo.Info{}
	}
	p.gitMu.Lock()
	defer p.gitMu.Unlock()
	if v, ok := p.gitCache[cwd]; ok {
		return v
	}
	v := gitinfo.Detect(cwd)
	p.gitCache[cwd] = v
	return v
}

func traeRoots() []string {
	home, err := os.UserHomeDir()
	if err != nil {
		return nil
	}
	names := []string{"Trae", "TRAE", "TRAE CN", "TRAE SOLO"}
	var roots []string
	switch runtime.GOOS {
	case "darwin":
		for _, n := range names {
			roots = append(roots, filepath.Join(home, "Library", "Application Support", n))
		}
	case "windows":
		base := os.Getenv("APPDATA")
		if base == "" {
			base = filepath.Join(home, "AppData", "Roaming")
		}
		for _, n := range names {
			roots = append(roots, filepath.Join(base, n))
		}
	default:
		base := os.Getenv("XDG_CONFIG_HOME")
		if base == "" {
			base = filepath.Join(home, ".config")
		}
		for _, n := range names {
			roots = append(roots, filepath.Join(base, n))
		}
	}
	return append(roots, filepath.Join(home, ".trae"), filepath.Join(home, ".trae-aicc"))
}

func discoverStateDBs() []string {
	seen := map[string]bool{}
	var out []string
	for _, root := range traeRoots() {
		_ = filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
			if err != nil || d == nil {
				return nil
			}
			if d.IsDir() {
				rel, _ := filepath.Rel(root, path)
				if strings.Count(rel, string(filepath.Separator)) > 5 {
					return fs.SkipDir
				}
				return nil
			}
			if d.Name() == "state.vscdb" && !seen[path] {
				seen[path] = true
				out = append(out, path)
			}
			return nil
		})
	}
	return out
}

func firstArray(m map[string]any, keys ...string) []any {
	for _, k := range keys {
		if v, ok := m[k].([]any); ok {
			return v
		}
	}
	return nil
}
func firstString(m map[string]any, keys ...string) string {
	for _, k := range keys {
		if s, ok := m[k].(string); ok && strings.TrimSpace(s) != "" {
			return s
		}
	}
	return ""
}
func firstTime(m map[string]any, keys ...string) time.Time {
	for _, k := range keys {
		if t := flexTime(m[k]); !t.IsZero() {
			return t
		}
	}
	return time.Time{}
}
func flexTime(v any) time.Time {
	switch x := v.(type) {
	case float64:
		return epoch(int64(x))
	case json.Number:
		n, _ := x.Int64()
		return epoch(n)
	case string:
		for _, l := range []string{time.RFC3339Nano, time.RFC3339, "2006-01-02 15:04:05"} {
			if t, e := time.Parse(l, x); e == nil {
				return t
			}
		}
		if n, e := strconv.ParseInt(x, 10, 64); e == nil {
			return epoch(n)
		}
	}
	return time.Time{}
}
func epoch(n int64) time.Time {
	if n <= 0 {
		return time.Time{}
	}
	if n > 1_000_000_000_000 {
		return time.UnixMilli(n)
	}
	return time.Unix(n, 0)
}
func firstInt64(m map[string]any, keys ...string) int64 {
	for _, k := range keys {
		switch v := m[k].(type) {
		case float64:
			if n := int64(v); n != 0 {
				return n
			}
		case json.Number:
			n, _ := v.Int64()
			if n != 0 {
				return n
			}
		case string:
			n, _ := strconv.ParseInt(v, 10, 64)
			if n != 0 {
				return n
			}
		}
	}
	return 0
}
func firstNonNil(vs ...any) any {
	for _, v := range vs {
		if v != nil {
			return v
		}
	}
	return nil
}
func firstNonEmpty(vs ...string) string {
	for _, v := range vs {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}
func stableID(s string) string {
	sum := sha1.Sum([]byte(s))
	return "trae-" + hex.EncodeToString(sum[:8])
}
