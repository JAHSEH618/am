// 多工作区 composer 聚合。
//
// gz
package cursor

import (
	"database/sql"
	"encoding/json"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

// workspaceMappingTTL 限制扫描频率：workspace.json + state.vscdb 写入并不频繁。
const workspaceMappingTTL = 30 * time.Second

// WorkspaceContext 单个 Cursor 工作区窗口的解析上下文。
type WorkspaceContext struct {
	// Folder 工作区根目录（workspace.json → folder URI）。
	Folder string
	// ImagesDir workspaceStorage/<hash>/images，存用户粘贴截图 PNG。
	ImagesDir string
}

// composerWorkspace composer-id → 工作区根目录的精确映射。
//
// 这是最权威的"项目根"信号 —— 直接来自 Cursor 自己的 workspaceStorage：
//
//	macOS   ~/Library/Application Support/Cursor/User/workspaceStorage
//	Windows %APPDATA%\Cursor\User\workspaceStorage
//	Linux   ~/.config/Cursor/User/workspaceStorage
//
// 每个子目录是一个 Cursor 窗口的 workspace，包含：
//   - workspace.json   { "folder": "file:///abs/path" } 工作区根
//   - state.vscdb      ItemTable['composer.composerData']
//                      .selectedComposerIds + .lastFocusedComposerIds → 该 workspace 用过的 composer
//
// 即便 bubble 内全是子目录路径（如 am/server 的 build.gradle），只要 composer 注册在 am
// 工作区下，这里也会准确返回 /Users/gz/projects/am。
type composerWorkspaceCache struct {
	mu       sync.Mutex
	loadedAt time.Time
	mapping  map[string]WorkspaceContext
}

var globalWorkspaceCache = &composerWorkspaceCache{}

// loadComposerWorkspaceMapping 返回最新的 composer-id → 工作区根目录映射。带 TTL 缓存。
func loadComposerWorkspaceMapping() map[string]string {
	ctx := loadComposerWorkspaceContext()
	out := make(map[string]string, len(ctx))
	for sid, w := range ctx {
		out[sid] = w.Folder
	}
	return out
}

// loadComposerWorkspaceContext 返回 composer-id → 工作区上下文（含 images 目录）。
func loadComposerWorkspaceContext() map[string]WorkspaceContext {
	globalWorkspaceCache.mu.Lock()
	defer globalWorkspaceCache.mu.Unlock()
	if time.Since(globalWorkspaceCache.loadedAt) < workspaceMappingTTL && globalWorkspaceCache.mapping != nil {
		return globalWorkspaceCache.mapping
	}
	m := scanComposerWorkspace()
	globalWorkspaceCache.mapping = m
	globalWorkspaceCache.loadedAt = time.Now()
	return m
}

func workspaceStorageRoot() string {
	if p := os.Getenv("AM_CURSOR_WORKSPACE_STORAGE"); p != "" {
		return p
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	switch runtime.GOOS {
	case "darwin":
		return filepath.Join(home, "Library", "Application Support", "Cursor", "User", "workspaceStorage")
	case "windows":
		ad := os.Getenv("APPDATA")
		if ad == "" {
			return ""
		}
		return filepath.Join(ad, "Cursor", "User", "workspaceStorage")
	default:
		return filepath.Join(home, ".config", "Cursor", "User", "workspaceStorage")
	}
}

func scanComposerWorkspace() map[string]WorkspaceContext {
	out := make(map[string]WorkspaceContext)
	root := workspaceStorageRoot()
	if root == "" {
		return out
	}
	entries, err := os.ReadDir(root)
	if err != nil {
		return out
	}
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		dir := filepath.Join(root, e.Name())
		folder := readWorkspaceFolder(filepath.Join(dir, "workspace.json"))
		if folder == "" {
			continue
		}
		dbPath := filepath.Join(dir, "state.vscdb")
		if _, err := os.Stat(dbPath); err != nil {
			continue
		}
		ids := readComposerIDs(dbPath)
		if len(ids) == 0 {
			continue
		}
		ws := WorkspaceContext{Folder: folder}
		imagesDir := filepath.Join(dir, "images")
		if st, err := os.Stat(imagesDir); err == nil && st.IsDir() {
			ws.ImagesDir = imagesDir
		}
		// 后扫描的 workspace 不应覆盖更早的（multiple workspaces selecting同一个 composer 的概率很低，
		// 实际不存在；但为了稳定性，保留先到先得）
		for _, sid := range ids {
			if _, ok := out[sid]; !ok {
				out[sid] = ws
			}
		}
	}
	return out
}

// readWorkspaceFolder 解析 workspace.json，返回工作区根目录绝对路径。
//
// 支持两种结构：
//
//	{ "folder": "file:///abs/path" }                    单根工作区
//	{ "configuration": { "$mid": 1, "external": ... } } *.code-workspace 多根工作区
//
// 多根情况下取 .code-workspace 文件所在目录作为代表（更接近用户认知）。
func readWorkspaceFolder(path string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	var doc struct {
		Folder        string `json:"folder"`
		Configuration struct {
			External string `json:"external"`
			Path     string `json:"path"`
		} `json:"configuration"`
	}
	if err := json.Unmarshal(data, &doc); err != nil {
		return ""
	}
	if doc.Folder != "" {
		return decodeFileURI(doc.Folder)
	}
	if doc.Configuration.External != "" {
		p := decodeFileURI(doc.Configuration.External)
		// .code-workspace 文件所在目录
		return filepath.Dir(p)
	}
	if doc.Configuration.Path != "" {
		return filepath.Dir(doc.Configuration.Path)
	}
	return ""
}

func decodeFileURI(s string) string {
	s = strings.TrimPrefix(s, "file://")
	if decoded, err := url.PathUnescape(s); err == nil {
		return decoded
	}
	return s
}

// readComposerIDs 读 workspace 级 state.vscdb 的 composer.composerData，
// 返回该工作区"选中 + 最近聚焦"的 composer id 列表。
func readComposerIDs(dbPath string) []string {
	db, err := sql.Open("sqlite", dbPath+"?mode=ro")
	if err != nil {
		return nil
	}
	defer func() { _ = db.Close() }()

	var raw string
	row := db.QueryRow("SELECT value FROM ItemTable WHERE key='composer.composerData' LIMIT 1")
	if err := row.Scan(&raw); err != nil {
		return nil
	}
	var doc struct {
		SelectedComposerIds    []string `json:"selectedComposerIds"`
		LastFocusedComposerIds []string `json:"lastFocusedComposerIds"`
	}
	if err := json.Unmarshal([]byte(raw), &doc); err != nil {
		return nil
	}
	total := len(doc.SelectedComposerIds) + len(doc.LastFocusedComposerIds)
	seen := make(map[string]struct{}, total)
	out := make([]string, 0, total)
	for _, src := range [][]string{doc.SelectedComposerIds, doc.LastFocusedComposerIds} {
		for _, id := range src {
			if id == "" {
				continue
			}
			if _, ok := seen[id]; ok {
				continue
			}
			seen[id] = struct{}{}
			out = append(out, id)
		}
	}
	return out
}
