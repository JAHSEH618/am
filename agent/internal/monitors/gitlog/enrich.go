// 对本机 commit 执行 git show / diff-tree，采集文件级明细与 unified diff。
//
// gz
package gitlog

import (
	"bytes"
	"compress/gzip"
	"encoding/base64"
	"fmt"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/am/aiwatch-agent/internal/procutil"
)

const maxMessageBodyLen = 8192

var defaultSkipPathSubstrings = []string{
	"/node_modules/",
	"/vendor/",
	"/.git/",
}

// enrichCommit 在 scanRepo 解析出 Commit 后调用，填充 Files / message / parents / detail_status。
func enrichCommit(repoDir string, c *Commit, lim Limits) {
	if c == nil || c.CommitHash == "" {
		return
	}
	if lim.MaxFilesPerCommit <= 0 {
		lim = DefaultLimits()
	}

	c.MessageBody = truncateString(loadMessageBody(repoDir, c.CommitHash), maxMessageBodyLen)
	c.ParentHashes, c.IsMerge = loadParents(repoDir, c.CommitHash)

	statByPath := map[string]PathStat{}
	for _, ps := range c.PathStats {
		statByPath[ps.Path] = ps
	}

	entries := loadNameStatus(repoDir, c.CommitHash)
	if len(entries) == 0 {
		// 无 name-status 时退回 numstat 路径
		for _, ps := range c.PathStats {
			entries = append(entries, nameStatusEntry{
				changeType: "M",
				path:       ps.Path,
			})
		}
	}
	if len(entries) > lim.MaxFilesPerCommit {
		entries = entries[:lim.MaxFilesPerCommit]
		c.DetailStatus = "partial"
		c.DetailSkipReason = "too_many_files"
	}

	var patches map[string]string
	if lim.CollectPatch {
		patches = loadPatchesByPath(repoDir, c.CommitHash, lim.PatchContextLines)
	}

	commitBudget := lim.MaxPatchBytesPerCommit
	commitUsed := 0
	allFull := true
	anyPatch := false

	files := make([]CommitFile, 0, len(entries))
	for i, e := range entries {
		displayPath := e.path
		if displayPath == "" {
			displayPath = e.oldPath
		}
		displayPath = normalizeGitPath(displayPath)

		ps, hasStat := statByPath[displayPath]
		linesAdded, linesDeleted := 0, 0
		if hasStat {
			linesAdded = ps.LinesAdded
			linesDeleted = ps.LinesDeleted
		}

		cf := CommitFile{
			Path:       displayPath,
			OldPath:    normalizeGitPath(e.oldPath),
			ChangeType: e.changeType,
			LinesAdded: linesAdded,
			LinesDeleted: linesDeleted,
			SortOrder:  i,
		}

		if shouldSkipPatchPath(displayPath) {
			cf.PatchTruncated = false
			files = append(files, cf)
			continue
		}

		rawPatch := patches[patchMapKey(e)]
		if rawPatch == "" && e.oldPath != "" {
			rawPatch = patches[normalizeGitPath(e.oldPath)]
		}
		if rawPatch == "" {
			rawPatch = patches[displayPath]
		}
		if strings.Contains(rawPatch, "Binary files") {
			cf.IsBinary = true
			files = append(files, cf)
			continue
		}

		if !lim.CollectPatch || rawPatch == "" {
			if e.changeType != "D" && lim.CollectPatch {
				allFull = false
			}
			files = append(files, cf)
			continue
		}

		patchBytes := []byte(rawPatch)
		truncReason := ""
		if len(patchBytes) > lim.MaxPatchBytesPerFile {
			patchBytes = patchBytes[:lim.MaxPatchBytesPerFile]
			truncReason = "file_too_large"
			cf.PatchTruncated = true
			allFull = false
		}
		if commitBudget >= 0 && commitUsed+len(patchBytes) > commitBudget {
			remain := commitBudget - commitUsed
			if remain <= 0 {
				truncReason = "commit_budget"
				cf.PatchTruncated = true
				allFull = false
				files = append(files, cf)
				continue
			}
			patchBytes = patchBytes[:remain]
			truncReason = "commit_budget"
			cf.PatchTruncated = true
			allFull = false
		}

		gz, err := gzipPatch(patchBytes)
		if err != nil || len(gz) == 0 {
			files = append(files, cf)
			continue
		}
		cf.PatchGzipB64 = base64.StdEncoding.EncodeToString(gz)
		cf.PatchBytes = len(patchBytes)
		cf.HasPatch = true
		anyPatch = true
		commitUsed += len(patchBytes)
		if truncReason != "" {
			cf.TruncateReason = truncReason
		}
		files = append(files, cf)
	}

	c.Files = files
	syncPathStatsFromFiles(c)
	if c.DetailStatus == "" {
		switch {
		case len(files) == 0:
			c.DetailStatus = "none"
		case !lim.CollectPatch:
			c.DetailStatus = "skipped"
			c.DetailSkipReason = "collect_patch_disabled"
		case allFull && anyPatch:
			c.DetailStatus = "full"
		case anyPatch || len(files) > 0:
			c.DetailStatus = "partial"
		default:
			c.DetailStatus = "partial"
		}
	}
}

// syncPathStatsFromFiles 使 path_stats 与 files 路径一致，避免服务端双源不一致。
func syncPathStatsFromFiles(c *Commit) {
	if c == nil || len(c.Files) == 0 {
		return
	}
	stats := make([]PathStat, 0, len(c.Files))
	for _, f := range c.Files {
		if f.Path == "" {
			continue
		}
		stats = append(stats, PathStat{
			Path:         f.Path,
			LinesAdded:   f.LinesAdded,
			LinesDeleted: f.LinesDeleted,
		})
	}
	c.PathStats = stats
}

type nameStatusEntry struct {
	changeType string
	path       string
	oldPath    string
}

func patchMapKey(e nameStatusEntry) string {
	if e.path != "" {
		return normalizeGitPath(e.path)
	}
	return normalizeGitPath(e.oldPath)
}

func loadMessageBody(repoDir, hash string) string {
	out, err := procutil.Hidden(exec.Command("git", "-C", repoDir, "show", "-s", "--format=%B", hash)).Output()
	if err != nil {
		return ""
	}
	return strings.TrimRight(string(out), "\n")
}

func loadParents(repoDir, hash string) ([]string, bool) {
	out, err := procutil.Hidden(exec.Command("git", "-C", repoDir, "rev-list", "--parents", "-n", "1", hash)).Output()
	if err != nil {
		return nil, false
	}
	parts := strings.Fields(strings.TrimSpace(string(out)))
	if len(parts) <= 1 {
		return nil, false
	}
	return parts[1:], len(parts) > 2
}

func loadNameStatus(repoDir, hash string) []nameStatusEntry {
	out, err := procutil.Hidden(exec.Command("git", "-C", repoDir, "-c", "core.quotepath=false",
		"diff-tree", "--no-commit-id", "--name-status", "-r", hash)).Output()
	if err != nil {
		return nil
	}
	var entries []nameStatusEntry
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimRight(line, "\r")
		if line == "" {
			continue
		}
		cols := strings.Split(line, "\t")
		if len(cols) < 2 {
			continue
		}
		ct := cols[0]
		if len(ct) > 1 {
			ct = ct[:1]
		}
		e := nameStatusEntry{changeType: ct}
		switch {
		case ct == "R" || ct == "C":
			if len(cols) >= 3 {
				e.oldPath = cols[1]
				e.path = cols[2]
			}
		default:
			e.path = cols[1]
		}
		entries = append(entries, e)
	}
	return entries
}

func loadPatchesByPath(repoDir, hash string, contextLines int) map[string]string {
	if contextLines <= 0 {
		contextLines = DefaultPatchContextLines
	}
	out, err := procutil.Hidden(exec.Command("git", "-C", repoDir, "-c", "core.quotepath=false",
		"show", "--no-color", fmt.Sprintf("-U%d", contextLines), "--format=", hash)).Output()
	if err != nil {
		return map[string]string{}
	}
	return splitUnifiedDiff(string(out))
}

// splitUnifiedDiff 按 diff --git 切块，键为 b/ 侧路径（normalize 后）。
func splitUnifiedDiff(blob string) map[string]string {
	result := map[string]string{}
	if strings.TrimSpace(blob) == "" {
		return result
	}
	parts := splitDiffParts(blob)
	for _, part := range parts {
		part = strings.TrimLeft(part, "\n")
		if part == "" {
			continue
		}
		path := parseDiffGitPath(part)
		if path == "" {
			continue
		}
		key := normalizeGitPath(path)
		if prev, ok := result[key]; ok {
			result[key] = prev + "\n" + part
		} else {
			result[key] = part
		}
	}
	return result
}

func splitDiffParts(blob string) []string {
	blob = strings.TrimPrefix(blob, "\n")
	if blob == "" {
		return nil
	}
	if !strings.Contains(blob, "diff --git ") {
		return []string{blob}
	}
	chunks := strings.Split(blob, "\ndiff --git ")
	out := make([]string, 0, len(chunks))
	for i, ch := range chunks {
		ch = strings.TrimSpace(ch)
		if ch == "" {
			continue
		}
		if i > 0 {
			ch = "diff --git " + ch
		}
		out = append(out, ch)
	}
	return out
}

func parseDiffGitPath(part string) string {
	first := strings.Index(part, "\n")
	header := part
	if first >= 0 {
		header = part[:first]
	}
	if !strings.HasPrefix(header, "diff --git ") {
		return ""
	}
	rest := strings.TrimPrefix(header, "diff --git ")
	// a/foo b/foo 或 a/foo b/bar
	fields := strings.Fields(rest)
	if len(fields) >= 2 {
		bPath := fields[1]
		return strings.TrimPrefix(bPath, "b/")
	}
	if len(fields) == 1 {
		return strings.TrimPrefix(fields[0], "a/")
	}
	return ""
}

func gzipPatch(raw []byte) ([]byte, error) {
	var buf bytes.Buffer
	w := gzip.NewWriter(&buf)
	if _, err := w.Write(raw); err != nil {
		return nil, err
	}
	if err := w.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func shouldSkipPatchPath(path string) bool {
	p := filepath.ToSlash(path)
	for _, sub := range defaultSkipPathSubstrings {
		if strings.Contains(p, sub) {
			return true
		}
	}
	return false
}

func truncateString(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}
