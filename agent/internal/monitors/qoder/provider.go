// Package qoder 采集 Qoder / QoderWork CLI 写入本地的 JSONL transcript。
//
// 官方 transcript 位置以 hook 的 transcript_path 为准，常见目录为：
//
//	~/.qoder/projects/<project>/transcript/<session-id>.jsonl
//	~/.qoderwork/projects/<project>/**/*.jsonl
//
// 每行是 session_meta / user / assistant / progress 之一。
package qoder

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
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/am/aiwatch-agent/internal/gitinfo"
	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"

	_ "modernc.org/sqlite"
)

const (
	TypeCode          = "qoder"
	maxJSONLLine      = 8 << 20
	maxRecentMessages = 20000
	maxRecentTools    = 1000
)

type Provider struct {
	watchDir string
	lookback time.Duration
	cache    *common.FileCache[*parsedSession]
	gitMu    sync.Mutex
	gitCache map[string]gitinfo.Info
}

type parsedSession struct {
	SessionID         string
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

type transcriptRecord struct {
	Type       string          `json:"type"`
	SessionID  string          `json:"sessionId"`
	SessionID2 string          `json:"session_id"`
	UUID       string          `json:"uuid"`
	Timestamp  json.RawMessage `json:"timestamp"`
	CWD        string          `json:"cwd"`
	WorkingDir string          `json:"working_dir"`
	Model      string          `json:"model"`
	Message    struct {
		ID      string          `json:"id"`
		Role    string          `json:"role"`
		Model   string          `json:"model"`
		Content json.RawMessage `json:"content"`
		Usage   tokenUsage      `json:"usage"`
	} `json:"message"`
	Usage tokenUsage `json:"usage"`
	Data  struct {
		Content struct {
			CWD        string `json:"cwd"`
			WorkingDir string `json:"working_dir"`
			Model      string `json:"model"`
		} `json:"content"`
	} `json:"data"`
}

type tokenUsage struct {
	InputTokens      int64 `json:"input_tokens"`
	OutputTokens     int64 `json:"output_tokens"`
	PromptTokens     int64 `json:"prompt_tokens"`
	CompletionTokens int64 `json:"completion_tokens"`
}

func New(watchDir string) *Provider {
	return &Provider{
		watchDir: watchDir,
		lookback: monitor.DefaultLookback,
		cache:    common.NewFileCache[*parsedSession](),
		gitCache: make(map[string]gitinfo.Info),
	}
}

func (p *Provider) Type() string          { return TypeCode }
func (p *Provider) TargetVersion() string { return "qoder" }

func (p *Provider) SetLookback(d time.Duration) {
	if d <= 0 {
		d = monitor.DefaultLookback
	}
	p.lookback = d
}

func (p *Provider) WatchHints() []string { return qoderRoots() }

func (p *Provider) IsInstalled() bool {
	for _, root := range qoderRoots() {
		if st, err := os.Stat(root); err == nil && st.IsDir() {
			return true
		}
	}
	if len(qoderStateDBs()) > 0 {
		return true
	}
	return false
}

func (p *Provider) Snapshot(ctx context.Context) (monitor.Snapshot, error) {
	_ = ctx
	now := time.Now()
	jobs, hits, seen := p.collectJobs(now.Add(-p.lookback))
	results := common.RunParallelParse(jobs, p.parseOne)
	parsed := append([]*parsedSession(nil), hits...)
	parsed = append(parsed, readIDEHistory()...)
	for _, r := range results {
		if r.Value == nil {
			continue
		}
		p.cache.Put(r.Path, r.MTime, r.NewOffset, r.Value)
		parsed = append(parsed, r.Value)
	}
	p.cache.Prune(seen)

	// 同一 session 可能同时出现于 projects 与 logs/sessions；按 ID 合并后只上报一次。
	merged := make(map[string]*parsedSession)
	for _, ps := range parsed {
		if ps == nil || ps.SessionID == "" || (!ps.LastActivity.IsZero() && ps.LastActivity.Before(now.Add(-p.lookback))) {
			continue
		}
		if old := merged[ps.SessionID]; old != nil {
			if ps.LastActivity.After(old.LastActivity) {
				merged[ps.SessionID] = ps
			}
			continue
		}
		merged[ps.SessionID] = ps
	}
	sessions := make([]monitor.Session, 0, len(merged))
	for _, ps := range merged {
		sessions = append(sessions, p.toMonitor(ps, now))
	}
	return monitor.Snapshot{Type: TypeCode, TargetVersion: p.TargetVersion(), CapturedAt: monitor.Now(), Sessions: sessions}, nil
}

func (p *Provider) collectJobs(cutoff time.Time) ([]common.ParseJob[*parsedSession], []*parsedSession, map[string]struct{}) {
	var jobs []common.ParseJob[*parsedSession]
	var hits []*parsedSession
	seen := make(map[string]struct{})
	for _, root := range qoderRoots() {
		_ = filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
			if err != nil || d == nil || d.IsDir() || !strings.EqualFold(filepath.Ext(path), ".jsonl") {
				return nil
			}
			info, statErr := d.Info()
			if statErr != nil || info.ModTime().Before(cutoff) {
				return nil
			}
			seen[path] = struct{}{}
			cached, offset, mtime := p.cache.GetIncremental(path)
			if cached != nil && offset == 0 {
				hits = append(hits, cached)
			} else {
				jobs = append(jobs, common.ParseJob[*parsedSession]{Path: path, Cached: cached, Offset: offset, MTime: mtime})
			}
			return nil
		})
	}
	return jobs, hits, seen
}

func (p *Provider) parseOne(path string, offset int64, cached *parsedSession) (*parsedSession, int64, error) {
	ps := cached
	if ps == nil || offset == 0 {
		ps = &parsedSession{SessionID: sessionIDFromPath(path)}
	}
	consumed, parsed, err := common.ScanJSONL(path, offset, maxJSONLLine, func(line []byte, _ int64) bool {
		var rec transcriptRecord
		if json.Unmarshal(line, &rec) != nil {
			return true
		}
		mergeRecord(ps, rec)
		return true
	})
	if err != nil {
		return nil, consumed, err
	}
	if !parsed && cached == nil {
		return nil, consumed, nil
	}
	if st, statErr := os.Stat(path); statErr == nil {
		if ps.StartedAt.IsZero() {
			ps.StartedAt = st.ModTime()
		}
		if ps.LastActivity.IsZero() {
			ps.LastActivity = st.ModTime()
		}
	}
	return ps, consumed, nil
}

func mergeRecord(ps *parsedSession, rec transcriptRecord) {
	if id := firstNonEmpty(rec.SessionID, rec.SessionID2); id != "" {
		ps.SessionID = id
	}
	ps.CWD = firstNonEmpty(rec.CWD, rec.WorkingDir, rec.Data.Content.CWD, rec.Data.Content.WorkingDir, ps.CWD)
	ps.Model = firstNonEmpty(rec.Message.Model, rec.Model, rec.Data.Content.Model, ps.Model)
	ts := parseFlexibleTime(rec.Timestamp)
	if !ts.IsZero() {
		if ps.StartedAt.IsZero() || ts.Before(ps.StartedAt) {
			ps.StartedAt = ts
		}
		if ts.After(ps.LastActivity) {
			ps.LastActivity = ts
		}
	}
	role := strings.ToLower(firstNonEmpty(rec.Message.Role, rec.Type))
	if role != "user" && role != "assistant" {
		return
	}
	parts, tools, onlyToolResult := parseContent(rec.Message.Content)
	if len(parts) == 0 {
		return
	}
	if onlyToolResult {
		role = "tool"
	}
	msg := monitor.Message{ExternalMessageID: firstNonEmpty(rec.UUID, rec.Message.ID), Role: role, ContentParts: parts, Timestamp: monitor.LocalTime(ts)}
	monitor.FinalizeMessage(&msg)
	if msg.ExternalMessageID == "" {
		msg.ExternalMessageID = common.SyntheticMessageIDByTime(ps.SessionID, ts, role, msg.Text)
	}
	in := rec.Message.Usage.InputTokens + rec.Message.Usage.PromptTokens
	out := rec.Message.Usage.OutputTokens + rec.Message.Usage.CompletionTokens
	if in == 0 && out == 0 {
		in = rec.Usage.InputTokens + rec.Usage.PromptTokens
		out = rec.Usage.OutputTokens + rec.Usage.CompletionTokens
	}
	msg.InputTokens = int(in)
	msg.OutputTokens = int(out)
	ps.Messages = append(ps.Messages, msg)
	if role == "user" {
		ps.UserMessages++
		common.AppendActivityDelta(&ps.Deltas, ts, msg.ExternalMessageID, common.ActivitySourceUserTurn, 0, 0, 1)
	} else if role == "assistant" {
		ps.AssistantMessages++
		ps.InputTokens += in
		ps.OutputTokens += out
		common.AppendActivityDelta(&ps.Deltas, ts, msg.ExternalMessageID, common.ActivitySourceAssistantTurn, in, out, 1)
	}
	for _, tool := range tools {
		ps.Tools = append(ps.Tools, monitor.Tool{Name: tool, Timestamp: monitor.LocalTime(ts)})
	}
	if len(ps.Messages) > maxRecentMessages {
		ps.Messages = ps.Messages[len(ps.Messages)-maxRecentMessages:]
	}
	if len(ps.Tools) > maxRecentTools {
		ps.Tools = ps.Tools[len(ps.Tools)-maxRecentTools:]
	}
}

func parseContent(raw json.RawMessage) (parts []monitor.ContentPart, tools []string, onlyToolResult bool) {
	if len(raw) == 0 || string(raw) == "null" {
		return nil, nil, false
	}
	var value any
	if json.Unmarshal(raw, &value) != nil {
		return nil, nil, false
	}
	toolResultCount := 0
	otherCount := 0
	var walk func(any)
	walk = func(v any) {
		switch x := v.(type) {
		case string:
			if strings.TrimSpace(x) != "" {
				parts = append(parts, monitor.ContentPart{Type: "text", Text: x, SortOrder: len(parts)})
				otherCount++
			}
		case []any:
			for _, item := range x {
				walk(item)
			}
		case map[string]any:
			typ := strings.ToLower(asString(x["type"]))
			switch typ {
			case "tool_use", "tool_call":
				name := common.NormalizeToolName(firstNonEmpty(asString(x["name"]), asString(x["tool_name"])))
				args := marshalCompact(firstNonNil(x["input"], x["arguments"]))
				parts = append(parts, monitor.ContentPart{Type: "tool_call", ToolName: name, ArgumentsJSON: args, SortOrder: len(parts)})
				if name != "" {
					tools = append(tools, name)
				}
				otherCount++
			case "tool_result":
				text := stringify(firstNonNil(x["content"], x["text"]))
				parts = append(parts, monitor.ContentPart{Type: "tool_result", Text: text, SortOrder: len(parts)})
				toolResultCount++
			case "thinking", "reasoning":
				text := stringify(firstNonNil(x["text"], x["content"]))
				if text != "" {
					parts = append(parts, monitor.ContentPart{Type: "thinking", Text: text, SortOrder: len(parts)})
					otherCount++
				}
			default:
				if text := stringify(firstNonNil(x["text"], x["content"])); text != "" {
					parts = append(parts, monitor.ContentPart{Type: "text", Text: text, SortOrder: len(parts)})
					otherCount++
				}
			}
		}
	}
	walk(value)
	return parts, tools, toolResultCount > 0 && otherCount == 0
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
	currentTool := ""
	if len(ps.Tools) > 0 && status != common.StatusIdle && status != common.StatusWaiting {
		currentTool = ps.Tools[len(ps.Tools)-1].Name
	}
	return monitor.Session{
		SessionID: ps.SessionID, Cwd: cwd, CwdHash: gi.ProjectPathHash, GitBranch: gi.BranchName,
		RepoURL: gi.RepoURL, ProjectName: project, Model: ps.Model, Status: status, CurrentTool: currentTool,
		StartedAt: monitor.LocalTime(ps.StartedAt), LastActivity: monitor.LocalTime(ps.LastActivity),
		UserMessages: ps.UserMessages, AssistantMessages: ps.AssistantMessages,
		SnapshotMessageCount: len(ps.Messages), InputTokens: ps.InputTokens, OutputTokens: ps.OutputTokens,
		RecentMessages: ps.Messages, RecentTools: ps.Tools,
		ActivityDeltas: common.TailActivityDeltas(ps.Deltas, maxRecentMessages),
	}
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

func qoderRoots() []string {
	home, err := os.UserHomeDir()
	if err != nil {
		return nil
	}
	return []string{
		filepath.Join(home, ".qoder", "projects"),
		filepath.Join(home, ".qoderwork", "projects"),
		filepath.Join(home, ".qoder", "logs", "sessions"),
	}
}

// readIDEHistory 读取 Qoder IDE 的本地历史索引。IDE 正文在部分版本中是加密日志或云端数据，
// 因此这里只生成真实存在的会话 stub，不伪造 user/assistant 消息。
func readIDEHistory() []*parsedSession {
	var out []*parsedSession
	for _, path := range qoderStateDBs() {
		st, err := os.Stat(path)
		if err != nil {
			continue
		}
		db, err := sql.Open("sqlite", path+"?mode=ro&_pragma=busy_timeout(1000)")
		if err != nil {
			continue
		}
		rows, err := db.Query("SELECT key,value FROM ItemTable WHERE key LIKE 'lingma.chat.localHistory.%'")
		if err == nil {
			for rows.Next() {
				var key string
				var raw []byte
				if rows.Scan(&key, &raw) != nil || len(raw) == 0 {
					continue
				}
				var value any
				if json.Unmarshal(raw, &value) == nil {
					collectIDEHistory(value, key, st.ModTime(), &out)
				}
			}
			_ = rows.Close()
		}
		_ = db.Close()
	}
	return out
}

func collectIDEHistory(v any, key string, fallback time.Time, out *[]*parsedSession) {
	switch x := v.(type) {
	case []any:
		for _, item := range x {
			collectIDEHistory(item, key, fallback, out)
		}
	case map[string]any:
		id := mapString(x, "sessionId", "session_id", "conversationId", "conversation_id", "id")
		if id != "" {
			created := mapTime(x, "createdAt", "created_at", "createTime", "timestamp")
			updated := mapTime(x, "updatedAt", "updated_at", "updateTime", "lastMessageDate", "timestamp")
			if created.IsZero() {
				created = fallback
			}
			if updated.IsZero() {
				updated = fallback
			}
			*out = append(*out, &parsedSession{SessionID: id, CWD: mapString(x, "cwd", "workingDirectory", "workspacePath"), StartedAt: created, LastActivity: updated})
			return
		}
		for _, child := range x {
			collectIDEHistory(child, key, fallback, out)
		}
	}
}

func qoderStateDBs() []string {
	home, err := os.UserHomeDir()
	if err != nil {
		return nil
	}
	var roots []string
	switch runtime.GOOS {
	case "darwin":
		roots = []string{filepath.Join(home, "Library", "Application Support", "Qoder")}
	case "windows":
		base := os.Getenv("APPDATA")
		if base == "" {
			base = filepath.Join(home, "AppData", "Roaming")
		}
		roots = []string{filepath.Join(base, "Qoder")}
	default:
		base := os.Getenv("XDG_CONFIG_HOME")
		if base == "" {
			base = filepath.Join(home, ".config")
		}
		roots = []string{filepath.Join(base, "Qoder")}
	}
	var out []string
	for _, root := range roots {
		path := filepath.Join(root, "User", "globalStorage", "state.vscdb")
		if st, err := os.Stat(path); err == nil && !st.IsDir() {
			out = append(out, path)
		}
	}
	return out
}

func mapString(m map[string]any, keys ...string) string {
	for _, key := range keys {
		if s, ok := m[key].(string); ok && strings.TrimSpace(s) != "" {
			return s
		}
	}
	return ""
}

func mapTime(m map[string]any, keys ...string) time.Time {
	for _, key := range keys {
		if v, ok := m[key]; ok {
			raw, _ := json.Marshal(v)
			if t := parseFlexibleTime(raw); !t.IsZero() {
				return t
			}
		}
	}
	return time.Time{}
}

func sessionIDFromPath(path string) string {
	base := strings.TrimSuffix(filepath.Base(path), filepath.Ext(path))
	base = strings.TrimSuffix(base, "-session")
	if base != "" {
		return base
	}
	s := sha1.Sum([]byte(path))
	return "qoder-" + hex.EncodeToString(s[:8])
}

func parseFlexibleTime(raw json.RawMessage) time.Time {
	if len(raw) == 0 {
		return time.Time{}
	}
	var s string
	if json.Unmarshal(raw, &s) == nil {
		for _, layout := range []string{time.RFC3339Nano, time.RFC3339, "2006-01-02T15:04:05"} {
			if t, err := time.Parse(layout, s); err == nil {
				return t
			}
		}
		if n, err := strconv.ParseInt(s, 10, 64); err == nil {
			return epochTime(n)
		}
	}
	var n float64
	if json.Unmarshal(raw, &n) == nil {
		return epochTime(int64(n))
	}
	return time.Time{}
}

func epochTime(n int64) time.Time {
	if n <= 0 {
		return time.Time{}
	}
	if n > 1_000_000_000_000 {
		return time.UnixMilli(n)
	}
	return time.Unix(n, 0)
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}
func firstNonNil(values ...any) any {
	for _, v := range values {
		if v != nil {
			return v
		}
	}
	return nil
}
func asString(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	return ""
}
func stringify(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	if v == nil {
		return ""
	}
	b, _ := json.Marshal(v)
	return string(b)
}
func marshalCompact(v any) string {
	if v == nil {
		return ""
	}
	b, _ := json.Marshal(v)
	return string(b)
}
