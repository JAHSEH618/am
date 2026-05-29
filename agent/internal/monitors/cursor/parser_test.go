// Cursor 解析器单元测试。
//
// gz
package cursor

import (
	"strings"
	"testing"
)

// TestInferWorkspace_DistinctProjects bug1 的核心：两套指向不同项目目录的路径必须解析为不同的项目根，
// 不能因为兜底逻辑而把多个 Cursor 窗口聚到一个项目下。
//
// 不硬编码具体目录字符串（CI / 不同开发机磁盘布局不同）。语义校验：
//   - amResult 必须包含 "/projects/am" 子串（或为空，表示当前机器上没有 .git/marker）
//   - aksResult 必须包含 "AKS" 子串（同上）
//   - 当两者都解析成功时，必须不相等
func TestInferWorkspace_DistinctProjects(t *testing.T) {
	amPaths := []string{
		"/Users/gz/projects/am/.gitignore",
		"/Users/gz/projects/am/README.md",
		"/Users/gz/projects/am/agent/cmd/agent/main.go",
		"/Users/gz/projects/am/server/build.gradle",
		"/Users/gz/.cursor/plans/foo.plan.md", // 用户全局路径，不应胜出
	}
	aksPaths := []string{
		"/Users/gz/IdeaProjects/AKS/README.md",
		"/Users/gz/IdeaProjects/AKS/build.gradle",
	}
	amRoot := inferWorkspace(amPaths)
	aksRoot := inferWorkspace(aksPaths)

	if amRoot != "" && !strings.Contains(amRoot, "/projects/am") {
		t.Errorf("am root looks wrong: %q", amRoot)
	}
	if aksRoot != "" && !strings.Contains(aksRoot, "AKS") {
		t.Errorf("AKS root looks wrong: %q", aksRoot)
	}
	if amRoot != "" && aksRoot != "" && amRoot == aksRoot {
		t.Errorf("two different projects produced identical root %q — bug1 not fixed", amRoot)
	}
	t.Logf("am root=%q, aks root=%q", amRoot, aksRoot)
}

// TestInferWorkspace_RejectsUserHome /Users/gz 这种用户主目录绝对不能当项目根。
func TestInferWorkspace_RejectsUserHome(t *testing.T) {
	if got := inferWorkspace([]string{
		"/Users/gz/.cursor/plans/foo.plan.md",
		"/Users/gz/.cursor/plans/bar.plan.md",
	}); got != "" {
		t.Errorf("must not pick user home, got %q", got)
	}
}

// TestInferWorkspace_Empty 边界。
func TestInferWorkspace_Empty(t *testing.T) {
	if got := inferWorkspace(nil); got != "" {
		t.Errorf("empty input should yield empty root, got %q", got)
	}
}

// TestCollectPathHints_BareAndUri 校验裸 POSIX 路径与 file:// URI 都能命中。
func TestCollectPathHints_BareAndUri(t *testing.T) {
	raw := `{"command":"ls -la /Users/gz/IdeaProjects/AKS","cwd":""}` +
		` and file:///Users/gz/projects/am/README.md`
	var out []string
	collectPathHints(raw, &out)

	hasAks := false
	hasAm := false
	for _, p := range out {
		if strings.Contains(p, "/Users/gz/IdeaProjects/AKS") {
			hasAks = true
		}
		if strings.Contains(p, "/Users/gz/projects/am") {
			hasAm = true
		}
	}
	if !hasAks {
		t.Errorf("expected to capture bare path /Users/gz/IdeaProjects/AKS, got %v", out)
	}
	if !hasAm {
		t.Errorf("expected to capture file:// URI for am project, got %v", out)
	}
}

// TestBuildToolText_RendersArgsAndResult Bug3：tool 内容必须包含命令、参数、结果。
func TestBuildToolText_RendersArgsAndResult(t *testing.T) {
	b := bubbleData{
		ToolFormerData: toolFormer{
			Name:    "run_terminal_command_v2",
			Status:  "completed",
			RawArgs: `{"command":"ls -la /Users/gz/IdeaProjects/AKS"}`,
			Params:  `{"command":"ls -la /Users/gz/IdeaProjects/AKS","cwd":""}`,
			Result:  `{"output":"total 72\\ndrwxr-xr-x  3 gz  staff  96 Apr 24"}`,
		},
	}
	out := buildToolText(b)
	for _, want := range []string{
		"[tool] run_terminal_command_v2",
		"[status] completed",
		"[args]",
		"ls -la /Users/gz/IdeaProjects/AKS",
		"[result]",
		"total 72",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("buildToolText output missing %q\n--- got ---\n%s", want, out)
		}
	}
}

// TestBuildToolText_TruncatesHugeResult 防止 read_file 的整个文件被写库。
func TestBuildToolText_TruncatesHugeResult(t *testing.T) {
	huge := strings.Repeat("A", maxToolTextSnippet*3)
	b := bubbleData{
		ToolFormerData: toolFormer{
			Name:   "read_file_v2",
			Status: "completed",
			Result: huge,
		},
	}
	out := buildToolText(b)
	if len(out) > maxToolMessageSize+50 {
		t.Errorf("expected truncation, got len=%d", len(out))
	}
	if !strings.Contains(out, "…") {
		t.Errorf("expected ellipsis marker in truncated tool text, got: %s", out[:200])
	}
}
