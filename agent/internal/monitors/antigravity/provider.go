// Package antigravity 采集 Google Antigravity IDE 的本地会话。
//
// 会话事实源：~/.gemini/antigravity/conversations/<uuid>.pb。
// protobuf 正文没有公开稳定 schema，本 Provider 不猜测正文结构；它读取文件时间和
// state.vscdb 中的公开侧边栏索引，提供可靠的会话级活跃/项目观测。
package antigravity

import (
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/am/aiwatch-agent/internal/gitinfo"
	"github.com/am/aiwatch-agent/internal/monitor"
	"github.com/am/aiwatch-agent/internal/monitors/common"

	_ "modernc.org/sqlite"
)

const TypeCode = "antigravity"

type Provider struct {
	lookback time.Duration
	gitMu    sync.Mutex
	gitCache map[string]gitinfo.Info
}

type summary struct {
	ID, Title, CWD       string
	CreatedAt, UpdatedAt time.Time
}

func New(watchDir string) *Provider {
	_ = watchDir // 索引没有 cwd 时宁可留空，避免把 daemon cwd 误报成项目。
	return &Provider{lookback: monitor.DefaultLookback, gitCache: make(map[string]gitinfo.Info)}
}
func (p *Provider) Type() string          { return TypeCode }
func (p *Provider) TargetVersion() string { return "antigravity" }
func (p *Provider) SetLookback(d time.Duration) {
	if d <= 0 {
		d = monitor.DefaultLookback
	}
	p.lookback = d
}
func (p *Provider) WatchHints() []string {
	root := conversationsDir()
	if root == "" {
		return nil
	}
	return []string{root}
}
func (p *Provider) IsInstalled() bool {
	root := conversationsDir()
	if st, e := os.Stat(root); e == nil && st.IsDir() {
		return true
	}
	for _, r := range appRoots() {
		if st, e := os.Stat(r); e == nil && st.IsDir() {
			return true
		}
	}
	return false
}

func (p *Provider) Snapshot(ctx context.Context) (monitor.Snapshot, error) {
	_ = ctx
	now := time.Now()
	index := loadSummaries()
	root := conversationsDir()
	entries, _ := os.ReadDir(root)
	sessions := make([]monitor.Session, 0, len(entries))
	for _, entry := range entries {
		if entry.IsDir() || !strings.EqualFold(filepath.Ext(entry.Name()), ".pb") {
			continue
		}
		st, e := entry.Info()
		if e != nil {
			continue
		}
		id := strings.TrimSuffix(entry.Name(), filepath.Ext(entry.Name()))
		meta := index[id]
		started := st.ModTime()
		last := st.ModTime()
		cwd := ""
		if meta != nil {
			if !meta.CreatedAt.IsZero() {
				started = meta.CreatedAt
			}
			if !meta.UpdatedAt.IsZero() {
				last = meta.UpdatedAt
			}
			cwd = meta.CWD
		}
		if last.Before(now.Add(-p.lookback)) {
			continue
		}
		gi := p.lookupGit(cwd)
		project := gi.ProjectName
		if project == "" && cwd != "" {
			project = filepath.Base(cwd)
		}
		status := common.ResolveActivity(common.SessionLike{LastActivity: last, BubbleStatus: common.BubbleThinking}, now)
		sessions = append(sessions, monitor.Session{SessionID: id, Cwd: cwd, CwdHash: gi.ProjectPathHash, GitBranch: gi.BranchName, RepoURL: gi.RepoURL, ProjectName: project, Status: status, StartedAt: monitor.LocalTime(started), LastActivity: monitor.LocalTime(last)})
	}
	sort.Slice(sessions, func(i, j int) bool { return sessions[i].LastActivity.Time().Before(sessions[j].LastActivity.Time()) })
	return monitor.Snapshot{Type: TypeCode, TargetVersion: p.TargetVersion(), CapturedAt: monitor.Now(), Sessions: sessions}, nil
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

func loadSummaries() map[string]*summary {
	out := map[string]*summary{}
	for _, dbPath := range stateDBPaths() {
		db, e := sql.Open("sqlite", dbPath+"?mode=ro&_pragma=busy_timeout(1000)")
		if e != nil {
			continue
		}
		rows, e := db.Query("SELECT key,value FROM ItemTable WHERE key IN ('antigravityUnifiedStateSync.trajectorySummaries','chat.ChatSessionStore.index')")
		if e == nil {
			for rows.Next() {
				var key string
				var raw []byte
				if rows.Scan(&key, &raw) != nil {
					continue
				}
				if key == "chat.ChatSessionStore.index" {
					mergeJSONIndex(out, raw)
				} else {
					mergeTrajectoryIndex(out, raw)
				}
			}
			_ = rows.Close()
		}
		_ = db.Close()
	}
	return out
}

func mergeJSONIndex(out map[string]*summary, raw []byte) {
	var generic map[string]any
	if json.Unmarshal(raw, &generic) != nil {
		return
	}
	entries, ok := generic["entries"].(map[string]any)
	if !ok {
		return
	}
	for key, v := range entries {
		m, ok := v.(map[string]any)
		if !ok {
			continue
		}
		id := str(m["sessionId"])
		if id == "" {
			id = key
		}
		s := out[id]
		if s == nil {
			s = &summary{ID: id}
			out[id] = s
		}
		s.Title = str(m["title"])
		s.CWD = fileURIPath(str(m["workingDirectory"]))
		s.UpdatedAt = epoch(num(m["lastMessageDate"]))
		if timing, ok := m["timing"].(map[string]any); ok {
			s.CreatedAt = epoch(num(timing["created"]))
		}
	}
}

func mergeTrajectoryIndex(out map[string]*summary, raw []byte) {
	data := raw
	if decoded, e := base64.StdEncoding.DecodeString(strings.TrimSpace(string(raw))); e == nil && len(decoded) > 0 {
		data = decoded
	}
	for _, field := range fields(data) {
		if field.num != 1 || len(field.bytes) == 0 {
			continue
		}
		entry := fields(field.bytes)
		id := ""
		var wrapper []byte
		for _, f := range entry {
			if f.num == 1 {
				id = string(f.bytes)
			}
			if f.num == 2 {
				wrapper = f.bytes
			}
		}
		if id == "" || len(wrapper) == 0 {
			continue
		}
		innerB64 := ""
		for _, f := range fields(wrapper) {
			if f.num == 1 {
				innerB64 = string(f.bytes)
				break
			}
		}
		payload, e := base64.StdEncoding.DecodeString(innerB64)
		if e != nil {
			continue
		}
		s := out[id]
		if s == nil {
			s = &summary{ID: id}
			out[id] = s
		}
		for _, f := range fields(payload) {
			switch f.num {
			case 1:
				s.Title = string(f.bytes)
			case 3:
				s.UpdatedAt = parseProtoTimestamp(f.bytes)
			case 4:
				if sid := string(f.bytes); sid != "" && sid != id {
					s.ID = sid
				}
			case 7:
				s.CreatedAt = parseProtoTimestamp(f.bytes)
			case 9:
				for _, wf := range fields(f.bytes) {
					if wf.num == 1 {
						s.CWD = fileURIPath(string(wf.bytes))
						break
					}
				}
			case 10:
				if t := parseProtoTimestamp(f.bytes); !t.IsZero() {
					s.UpdatedAt = t
				}
			}
		}
	}
}

type wireField struct {
	num   int
	wire  int
	value uint64
	bytes []byte
}

func fields(data []byte) []wireField {
	var out []wireField
	for pos := 0; pos < len(data); {
		tag, n := readVarint(data[pos:])
		if n == 0 {
			break
		}
		pos += n
		f := wireField{num: int(tag >> 3), wire: int(tag & 7)}
		switch f.wire {
		case 0:
			v, n := readVarint(data[pos:])
			if n == 0 {
				return out
			}
			f.value = v
			pos += n
		case 1:
			if pos+8 > len(data) {
				return out
			}
			pos += 8
		case 2:
			l, n := readVarint(data[pos:])
			if n == 0 {
				return out
			}
			pos += n
			if l > uint64(len(data)-pos) {
				return out
			}
			f.bytes = data[pos : pos+int(l)]
			pos += int(l)
		case 5:
			if pos+4 > len(data) {
				return out
			}
			pos += 4
		default:
			return out
		}
		out = append(out, f)
	}
	return out
}
func readVarint(data []byte) (uint64, int) {
	var v uint64
	for i, b := range data {
		if i >= 10 {
			return 0, 0
		}
		v |= uint64(b&0x7f) << uint(7*i)
		if b < 0x80 {
			return v, i + 1
		}
	}
	return 0, 0
}
func parseProtoTimestamp(data []byte) time.Time {
	var sec, nanos int64
	for _, f := range fields(data) {
		if f.num == 1 {
			sec = int64(f.value)
		}
		if f.num == 2 {
			nanos = int64(f.value)
		}
	}
	if sec <= 0 {
		return time.Time{}
	}
	return time.Unix(sec, nanos)
}

func conversationsDir() string {
	home, e := os.UserHomeDir()
	if e != nil {
		return ""
	}
	return filepath.Join(home, ".gemini", "antigravity", "conversations")
}
func appRoots() []string {
	home, e := os.UserHomeDir()
	if e != nil {
		return nil
	}
	names := []string{"Antigravity", "Antigravity IDE"}
	var out []string
	switch runtime.GOOS {
	case "darwin":
		for _, n := range names {
			out = append(out, filepath.Join(home, "Library", "Application Support", n))
		}
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
	return out
}
func stateDBPaths() []string {
	var out []string
	for _, root := range appRoots() {
		for _, rel := range []string{filepath.Join("User", "globalStorage", "state.vscdb")} {
			p := filepath.Join(root, rel)
			if st, e := os.Stat(p); e == nil && !st.IsDir() {
				out = append(out, p)
			}
		}
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
func str(v any) string { s, _ := v.(string); return s }
func num(v any) int64 {
	switch x := v.(type) {
	case float64:
		return int64(x)
	case json.Number:
		n, _ := x.Int64()
		return n
	}
	return 0
}
func fileURIPath(raw string) string {
	if raw == "" {
		return ""
	}
	u, e := url.Parse(raw)
	if e == nil && u.Scheme == "file" {
		p, e := url.PathUnescape(u.Path)
		if e == nil {
			return filepath.FromSlash(p)
		}
	}
	return raw
}
