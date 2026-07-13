// Package codebuddy 采集腾讯 CodeBuddy IDE 的本地会话索引，并在可用时读取 genie-history 消息。
//
// 会话元数据：User/globalStorage/tencent-cloud.coding-copilot/codebuddy-sessions.vscdb
// 正文（部分版本才落本地）：.../genie-history/<project>/conversations/<id>/messages.json[l]
package codebuddy

import (
	"bufio"
	"context"
	"database/sql"
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

const TypeCode = "codebuddy"

type Provider struct {
	watchDir string
	lookback time.Duration
	gitMu    sync.Mutex
	gitCache map[string]gitinfo.Info
}

type parsedSession struct {
	ID, CWD, Title, RawStatus, Model string
	StartedAt, LastActivity          time.Time
	UserMessages, AssistantMessages  int
	InputTokens, OutputTokens        int64
	Messages                         []monitor.Message
	Tools                            []monitor.Tool
	Deltas                           []monitor.ActivityDelta
}

type sessionMeta struct {
	ConversationID string `json:"conversationId"`
	CWD            string `json:"cwd"`
	Title          string `json:"title"`
	Status         string `json:"status"`
	Model          string `json:"model"`
	CreatedAt      int64  `json:"createdAt"`
	UpdatedAt      int64  `json:"updatedAt"`
}

func New(watchDir string) *Provider {
	return &Provider{watchDir: watchDir, lookback: monitor.DefaultLookback, gitCache: make(map[string]gitinfo.Info)}
}
func (p *Provider) Type() string          { return TypeCode }
func (p *Provider) TargetVersion() string { return "codebuddy" }
func (p *Provider) SetLookback(d time.Duration) {
	if d <= 0 {
		d = monitor.DefaultLookback
	}
	p.lookback = d
}
func (p *Provider) WatchHints() []string { return codeBuddyRoots() }
func (p *Provider) IsInstalled() bool {
	for _, r := range codeBuddyRoots() {
		if st, e := os.Stat(r); e == nil && st.IsDir() {
			return true
		}
	}
	return false
}

func (p *Provider) Snapshot(ctx context.Context) (monitor.Snapshot, error) {
	_ = ctx
	now := time.Now()
	byID := map[string]*parsedSession{}
	for _, dbPath := range discoverSessionDBs() {
		for _, ps := range readSessionDB(dbPath) {
			if ps != nil && ps.ID != "" {
				byID[ps.ID] = ps
			}
		}
	}
	for _, ps := range readCLITranscripts() {
		if ps != nil && ps.ID != "" {
			if old := byID[ps.ID]; old == nil || ps.LastActivity.After(old.LastActivity) {
				byID[ps.ID] = ps
			}
		}
	}
	messageFiles := discoverMessageFiles()
	for id, ps := range byID {
		if path := messageFiles[id]; path != "" {
			loadMessages(path, ps)
		}
		if ps.LastActivity.IsZero() {
			ps.LastActivity = ps.StartedAt
		}
		if !ps.LastActivity.IsZero() && ps.LastActivity.Before(now.Add(-p.lookback)) {
			delete(byID, id)
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

func readSessionDB(path string) []*parsedSession {
	db, e := sql.Open("sqlite", path+"?mode=ro&_pragma=busy_timeout(1500)")
	if e != nil {
		return nil
	}
	defer func() { _ = db.Close() }()
	rows, e := db.Query("SELECT key,value FROM ItemTable WHERE key LIKE 'session:%'")
	if e != nil {
		return nil
	}
	defer func() { _ = rows.Close() }()
	var out []*parsedSession
	for rows.Next() {
		var key string
		var raw []byte
		if rows.Scan(&key, &raw) != nil {
			continue
		}
		var m sessionMeta
		if json.Unmarshal(raw, &m) != nil {
			continue
		}
		id := m.ConversationID
		if id == "" {
			id = strings.TrimPrefix(key, "session:")
		}
		if id == "" {
			continue
		}
		out = append(out, &parsedSession{ID: id, CWD: m.CWD, Title: m.Title, RawStatus: m.Status, Model: m.Model, StartedAt: epoch(m.CreatedAt), LastActivity: epoch(m.UpdatedAt)})
	}
	return out
}

func loadMessages(path string, ps *parsedSession) {
	if strings.HasSuffix(strings.ToLower(path), ".jsonl") {
		f, e := os.Open(path)
		if e != nil {
			return
		}
		defer func() { _ = f.Close() }()
		s := bufio.NewScanner(f)
		s.Buffer(make([]byte, 0, 64<<10), 8<<20)
		i := 0
		for s.Scan() {
			var m map[string]any
			if json.Unmarshal(s.Bytes(), &m) == nil {
				appendMessage(ps, m, i)
			}
			i++
		}
		return
	}
	raw, e := os.ReadFile(path)
	if e != nil {
		return
	}
	var value any
	if json.Unmarshal(raw, &value) != nil {
		return
	}
	var arr []any
	switch x := value.(type) {
	case []any:
		arr = x
	case map[string]any:
		arr = firstArray(x, "messages", "items", "history")
	}
	for i, item := range arr {
		if m, ok := item.(map[string]any); ok {
			appendMessage(ps, m, i)
		}
	}
}

func readCLITranscripts() []*parsedSession {
	home, err := os.UserHomeDir()
	if err != nil {
		return nil
	}
	root := filepath.Join(home, ".codebuddy", "projects")
	var out []*parsedSession
	_ = filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil || d == nil || d.IsDir() || !strings.EqualFold(filepath.Ext(path), ".jsonl") {
			return nil
		}
		ps := &parsedSession{ID: strings.TrimSuffix(filepath.Base(path), filepath.Ext(path))}
		f, err := os.Open(path)
		if err != nil {
			return nil
		}
		scanner := bufio.NewScanner(f)
		scanner.Buffer(make([]byte, 0, 64<<10), 8<<20)
		index := 0
		for scanner.Scan() {
			var row map[string]any
			if json.Unmarshal(scanner.Bytes(), &row) != nil {
				continue
			}
			if id := firstString(row, "sessionId", "session_id"); id != "" {
				ps.ID = id
			}
			if cwd := firstString(row, "cwd", "working_dir"); cwd != "" {
				ps.CWD = cwd
			}
			if nested, ok := row["message"].(map[string]any); ok {
				copyIfMissing(nested, row, "timestamp", "uuid", "id")
				appendMessage(ps, nested, index)
			} else {
				appendMessage(ps, row, index)
			}
			index++
		}
		_ = f.Close()
		if st, err := os.Stat(path); err == nil {
			if ps.StartedAt.IsZero() {
				ps.StartedAt = st.ModTime()
			}
			if ps.LastActivity.IsZero() {
				ps.LastActivity = st.ModTime()
			}
		}
		if ps.UserMessages > 0 || ps.AssistantMessages > 0 {
			out = append(out, ps)
		}
		return nil
	})
	return out
}

func copyIfMissing(dst, src map[string]any, keys ...string) {
	for _, key := range keys {
		if _, exists := dst[key]; !exists {
			if value, ok := src[key]; ok {
				dst[key] = value
			}
		}
	}
}

func appendMessage(ps *parsedSession, m map[string]any, index int) {
	role := strings.ToLower(firstString(m, "role", "type", "sender"))
	if role == "human" {
		role = "user"
	}
	if role == "ai" || role == "bot" {
		role = "assistant"
	}
	if role != "user" && role != "assistant" && role != "tool" {
		return
	}
	ts := firstTime(m, "timestamp", "createdAt", "created_at", "time")
	if ts.IsZero() {
		ts = ps.StartedAt.Add(time.Duration(index) * time.Microsecond)
	}
	if ps.StartedAt.IsZero() || ts.Before(ps.StartedAt) {
		ps.StartedAt = ts
	}
	if ts.After(ps.LastActivity) {
		ps.LastActivity = ts
	}
	parts, tools := messageParts(m)
	if len(parts) == 0 {
		return
	}
	id := firstString(m, "id", "messageId", "uuid")
	msg := monitor.Message{ExternalMessageID: id, Role: role, ContentParts: parts, Timestamp: monitor.LocalTime(ts)}
	monitor.FinalizeMessage(&msg)
	if id == "" {
		msg.ExternalMessageID = common.SyntheticMessageID(ps.ID, strconv.Itoa(index), role, msg.Text)
	}
	in := firstInt64(m, "inputTokens", "input_tokens", "prompt_tokens")
	out := firstInt64(m, "outputTokens", "output_tokens", "completion_tokens")
	msg.InputTokens = int(in)
	msg.OutputTokens = int(out)
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

func messageParts(m map[string]any) (parts []monitor.ContentPart, tools []string) {
	content := firstNonNil(m["content"], m["text"], m["message"])
	var walk func(any)
	walk = func(v any) {
		switch x := v.(type) {
		case string:
			if strings.TrimSpace(x) != "" {
				parts = append(parts, monitor.ContentPart{Type: "text", Text: x, SortOrder: len(parts)})
			}
		case []any:
			for _, it := range x {
				walk(it)
			}
		case map[string]any:
			typ := strings.ToLower(firstString(x, "type"))
			if strings.Contains(typ, "tool") || firstString(x, "toolName", "tool_name") != "" {
				name := common.NormalizeToolName(firstString(x, "name", "toolName", "tool_name"))
				args, _ := json.Marshal(firstNonNil(x["input"], x["arguments"]))
				parts = append(parts, monitor.ContentPart{Type: "tool_call", ToolName: name, ArgumentsJSON: string(args), SortOrder: len(parts)})
				if name != "" {
					tools = append(tools, name)
				}
			} else if text := firstString(x, "text", "content", "value"); text != "" {
				pt := "text"
				if typ == "thinking" || typ == "reasoning" {
					pt = "thinking"
				}
				parts = append(parts, monitor.ContentPart{Type: pt, Text: text, SortOrder: len(parts)})
			}
		}
	}
	walk(content)
	for _, raw := range firstArray(m, "toolCalls", "tool_calls") {
		if tm, ok := raw.(map[string]any); ok {
			name := common.NormalizeToolName(firstString(tm, "name", "toolName"))
			args, _ := json.Marshal(firstNonNil(tm["input"], tm["arguments"]))
			parts = append(parts, monitor.ContentPart{Type: "tool_call", ToolName: name, ArgumentsJSON: string(args), SortOrder: len(parts)})
			if name != "" {
				tools = append(tools, name)
			}
		}
	}
	return
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
	bubble := common.BubbleUnknown
	lower := strings.ToLower(ps.RawStatus)
	if strings.Contains(lower, "running") || strings.Contains(lower, "processing") || strings.Contains(lower, "generating") {
		bubble = common.BubbleThinking
	}
	if strings.Contains(lower, "waiting") {
		bubble = common.BubbleWaitingUser
	}
	status := common.ResolveActivity(common.SessionLike{LastActivity: ps.LastActivity, BubbleStatus: bubble, RecentTools: ps.Tools}, now)
	current := ""
	if len(ps.Tools) > 0 && status != common.StatusIdle && status != common.StatusWaiting {
		current = ps.Tools[len(ps.Tools)-1].Name
	}
	return monitor.Session{SessionID: ps.ID, Cwd: cwd, CwdHash: gi.ProjectPathHash, GitBranch: gi.BranchName, RepoURL: gi.RepoURL, ProjectName: project, Model: ps.Model, Status: status, CurrentTool: current, StartedAt: monitor.LocalTime(ps.StartedAt), LastActivity: monitor.LocalTime(ps.LastActivity), UserMessages: ps.UserMessages, AssistantMessages: ps.AssistantMessages, SnapshotMessageCount: len(ps.Messages), InputTokens: ps.InputTokens, OutputTokens: ps.OutputTokens, RecentMessages: ps.Messages, RecentTools: ps.Tools, ActivityDeltas: ps.Deltas}
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

func codeBuddyRoots() []string {
	home, e := os.UserHomeDir()
	if e != nil {
		return nil
	}
	names := []string{"CodeBuddy", "codebuddy"}
	var out []string
	switch runtime.GOOS {
	case "darwin":
		for _, n := range names {
			out = append(out, filepath.Join(home, "Library", "Application Support", n))
		}
		out = append(out, filepath.Join(home, "Library", "Application Support", "Code", "User", "globalStorage", "tencent-cloud.coding-copilot"))
	case "windows":
		base := os.Getenv("APPDATA")
		if base == "" {
			base = filepath.Join(home, "AppData", "Roaming")
		}
		for _, n := range names {
			out = append(out, filepath.Join(base, n))
		}
	default:
		base := os.Getenv("XDG_CONFIG_HOME")
		if base == "" {
			base = filepath.Join(home, ".config")
		}
		for _, n := range names {
			out = append(out, filepath.Join(base, n))
		}
	}
	return append(out, filepath.Join(home, ".codebuddy"))
}
func discoverSessionDBs() []string { return discoverNamed("codebuddy-sessions.vscdb") }
func discoverNamed(name string) []string {
	seen := map[string]bool{}
	var out []string
	for _, root := range codeBuddyRoots() {
		_ = filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
			if err != nil || d == nil {
				return nil
			}
			if d.IsDir() {
				rel, _ := filepath.Rel(root, path)
				if strings.Count(rel, string(filepath.Separator)) > 7 {
					return fs.SkipDir
				}
				return nil
			}
			if strings.EqualFold(d.Name(), name) && !seen[path] {
				seen[path] = true
				out = append(out, path)
			}
			return nil
		})
	}
	return out
}
func discoverMessageFiles() map[string]string {
	out := map[string]string{}
	for _, root := range codeBuddyRoots() {
		_ = filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
			if err != nil || d == nil {
				return nil
			}
			if d.IsDir() {
				rel, _ := filepath.Rel(root, path)
				if strings.Count(rel, string(filepath.Separator)) > 10 {
					return fs.SkipDir
				}
				return nil
			}
			name := strings.ToLower(d.Name())
			if name != "messages.jsonl" && name != "messages.json" {
				return nil
			}
			id := filepath.Base(filepath.Dir(path))
			if id != "" {
				if old := out[id]; old == "" || strings.HasSuffix(name, ".jsonl") {
					out[id] = path
				}
			}
			return nil
		})
	}
	return out
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
func firstArray(m map[string]any, ks ...string) []any {
	for _, k := range ks {
		if v, ok := m[k].([]any); ok {
			return v
		}
	}
	return nil
}
func firstString(m map[string]any, ks ...string) string {
	for _, k := range ks {
		if v, ok := m[k].(string); ok && strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}
func firstTime(m map[string]any, ks ...string) time.Time {
	for _, k := range ks {
		switch v := m[k].(type) {
		case float64:
			return epoch(int64(v))
		case string:
			if t, e := time.Parse(time.RFC3339Nano, v); e == nil {
				return t
			}
			if n, e := strconv.ParseInt(v, 10, 64); e == nil {
				return epoch(n)
			}
		}
	}
	return time.Time{}
}
func firstInt64(m map[string]any, ks ...string) int64 {
	for _, k := range ks {
		switch v := m[k].(type) {
		case float64:
			if n := int64(v); n != 0 {
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
