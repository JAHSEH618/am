// 工作区模块单元测试。
//
// gz
package cursor

import (
	"database/sql"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	_ "modernc.org/sqlite"
)

// TestScanComposerWorkspace_RealLayout 模拟 Cursor 真实的 workspaceStorage 目录结构，
// 校验我们能从 workspace.json + state.vscdb 恢复 composer→项目根 的映射。
//
// 这是 bug "项目显示成 server 而非 am" 的核心修复路径。
func TestScanComposerWorkspace_RealLayout(t *testing.T) {
	root := t.TempDir()
	t.Setenv("AM_CURSOR_WORKSPACE_STORAGE", root)

	type ws struct {
		hash         string
		folder       string
		selected     []string
		lastFocused  []string
		writeMissing bool // 故意不写 state.vscdb 的目录，应被跳过
	}
	cases := []ws{
		{
			hash:        "amhash",
			folder:      "file:///Users/gz/projects/am",
			selected:    []string{"cd9d41a9-601c-4b69-a727-3f0b5bdf0822"},
			lastFocused: []string{"cd9d41a9-601c-4b69-a727-3f0b5bdf0822"},
		},
		{
			hash:        "akshash",
			folder:      "file:///Users/gz/IdeaProjects/AKS",
			selected:    []string{"62e87c43-049f-4bbf-8cd1-94d7efc42f01"},
			lastFocused: nil,
		},
		{
			hash:         "missing",
			folder:       "file:///Users/gz/missing",
			writeMissing: true,
		},
	}
	for _, c := range cases {
		dir := filepath.Join(root, c.hash)
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
		writeJSON(t, filepath.Join(dir, "workspace.json"),
			map[string]any{"folder": c.folder})
		if c.writeMissing {
			continue
		}
		writeWorkspaceDB(t, filepath.Join(dir, "state.vscdb"), c.selected, c.lastFocused)
	}

	got := scanComposerWorkspace()
	if want := "/Users/gz/projects/am"; got["cd9d41a9-601c-4b69-a727-3f0b5bdf0822"].Folder != want {
		t.Errorf("am mapping wrong: got %q want %q", got["cd9d41a9-601c-4b69-a727-3f0b5bdf0822"].Folder, want)
	}
	if want := "/Users/gz/IdeaProjects/AKS"; got["62e87c43-049f-4bbf-8cd1-94d7efc42f01"].Folder != want {
		t.Errorf("aks mapping wrong: got %q want %q", got["62e87c43-049f-4bbf-8cd1-94d7efc42f01"].Folder, want)
	}
	for k := range got {
		if k == "" {
			t.Errorf("empty composer id slipped into mapping")
		}
	}
}

func TestReadWorkspaceFolder_MultiRoot(t *testing.T) {
	dir := t.TempDir()
	// VS Code/Cursor 多根工作区 .code-workspace 文件
	codeWorkspace := "/Users/gz/myProjects/all.code-workspace"
	writeJSON(t, filepath.Join(dir, "workspace.json"),
		map[string]any{
			"configuration": map[string]any{
				"$mid":     1,
				"external": "file://" + codeWorkspace,
				"path":     codeWorkspace,
				"scheme":   "file",
			},
		})
	got := readWorkspaceFolder(filepath.Join(dir, "workspace.json"))
	if want := "/Users/gz/myProjects"; got != want {
		t.Errorf("multi-root workspace folder = %q, want %q", got, want)
	}
}

func TestDecodeFileURI_PathEscape(t *testing.T) {
	got := decodeFileURI("file:///Users/gz/myProjects/%E4%B8%AD%E6%96%87")
	if want := "/Users/gz/myProjects/中文"; got != want {
		t.Errorf("decodeFileURI escaped path = %q, want %q", got, want)
	}
}

func writeJSON(t *testing.T, path string, v any) {
	t.Helper()
	data, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
}

func writeWorkspaceDB(t *testing.T, path string, selected, lastFocused []string) {
	t.Helper()
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.Exec(`CREATE TABLE ItemTable (key TEXT PRIMARY KEY, value TEXT)`); err != nil {
		t.Fatal(err)
	}
	doc := map[string]any{
		"selectedComposerIds":    selected,
		"lastFocusedComposerIds": lastFocused,
	}
	raw, err := json.Marshal(doc)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO ItemTable(key,value) VALUES('composer.composerData', ?)`, string(raw)); err != nil {
		t.Fatal(err)
	}
}
