// Cursor bubble → monitor.ContentPart 富化（图片、@文件片段、工具 IO）。
// gz
package cursor

import (
	"os"
	"path/filepath"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/am/aiwatch-agent/internal/monitor"
)

const (
	maxTextBytesPerPart = 512 * 1024
	maxBlobBytesPerPart = 1024 * 1024
	maxBlobsPerMessage  = 8
	maxToolSnippetBytes = 512 * 1024
)

func extractUserParts(b bubbleData, imagesDir string) []monitor.ContentPart {
	var parts []monitor.ContentPart
	order := 0
	add := func(p monitor.ContentPart) {
		p.SortOrder = order
		order++
		parts = append(parts, p)
	}

	if text := strings.TrimSpace(b.Text); text != "" {
		add(monitor.ContentPart{Type: "text", Text: truncateBytes(text, maxTextBytesPerPart)})
	}
	for _, ch := range b.AttachedCodeChunks {
		path := strings.TrimSpace(ch.RelativeWorkspacePath)
		if path == "" && len(ch.Lines) == 0 {
			continue
		}
		body := strings.Join(ch.Lines, "\n")
		start := ch.StartLineNumber
		end := start + len(ch.Lines) - 1
		if start <= 0 {
			start = 1
		}
		if end < start {
			end = start
		}
		add(monitor.ContentPart{
			Type:      "file_snippet",
			Path:      path,
			Text:      truncateBytes(body, maxTextBytesPerPart),
			StartLine: start,
			EndLine:   end,
		})
	}
	blobCount := 0
	for _, img := range collectBubbleImages(b) {
		if blobCount >= maxBlobsPerMessage {
			add(monitor.ContentPart{
				Type:           "image",
				Truncated:      true,
				TruncateReason: "blob_limit",
				Width:          img.Dimension.Width,
				Height:         img.Dimension.Height,
			})
			continue
		}
		p := monitor.ContentPart{
			Type:   "image",
			Mime:   imageMimeFromPath(img.Path),
			Width:  img.Dimension.Width,
			Height: img.Dimension.Height,
		}
		if raw, ok := loadBubbleImage(img, imagesDir); ok {
			if len(raw) > maxBlobBytesPerPart {
				p.Truncated = true
				p.TruncateReason = "blob_too_large"
			} else if b64, err := monitor.GzipBase64(raw); err == nil {
				p.BlobGzipBase64 = b64
				blobCount++
			}
		}
		add(p)
	}
	return parts
}

// collectBubbleImages 合并 images 与 context.selectedImages。
// Cursor 对同一张截图常在两处各写一条且 uuid 不同：images[] 仅 uuid+尺寸，
// selectedImages[] 带绝对 path（文件名形如 <content-uuid>-<suffix>.png）。按内容 uuid 去重并优先保留带 path 的条目。
func collectBubbleImages(b bubbleData) []bubbleImage {
	// selectedImages 通常带可读的绝对路径，排在前面以便合并时优先保留。
	candidates := make([]bubbleImage, 0, len(b.Context.SelectedImages)+len(b.Images))
	candidates = append(candidates, b.Context.SelectedImages...)
	candidates = append(candidates, b.Images...)

	seen := map[string]int{} // identity key -> index in out
	var out []bubbleImage

	for _, img := range candidates {
		keys := imageIdentityKeys(img)
		if len(keys) == 0 {
			continue
		}
		dupIdx := -1
		for _, k := range keys {
			if idx, ok := seen[k]; ok {
				dupIdx = idx
				break
			}
		}
		if dupIdx >= 0 {
			merged := mergeBubbleImage(out[dupIdx], img)
			out[dupIdx] = merged
			for _, k := range imageIdentityKeys(merged) {
				seen[k] = dupIdx
			}
			continue
		}
		idx := len(out)
		out = append(out, img)
		for _, k := range keys {
			seen[k] = idx
		}
	}
	return out
}

// imageIdentityKeys 返回用于去重的键：struct uuid、path、以及 path 文件名中的内容 uuid。
func imageIdentityKeys(img bubbleImage) []string {
	var keys []string
	add := func(prefix, val string) {
		val = strings.TrimSpace(val)
		if val == "" {
			return
		}
		k := prefix + val
		for _, existing := range keys {
			if existing == k {
				return
			}
		}
		keys = append(keys, k)
	}
	add("u:", img.UUID)
	add("p:", img.Path)
	if su := imageStorageUUID(img.Path); su != "" {
		add("u:", su)
	}
	return keys
}

// imageStorageUUID 从 workspaceStorage/.../images/<uuid>-<suffix>.png 提取内容 uuid。
// 文件名前缀固定 36 字符（8-4-4-4-12），不能按第一个 '-' 截断。
func imageStorageUUID(path string) string {
	base := filepath.Base(strings.TrimSpace(path))
	if len(base) < 36 {
		return ""
	}
	candidate := base[:36]
	if isUUID(candidate) {
		return candidate
	}
	return ""
}

func isUUID(s string) bool {
	if len(s) != 36 || strings.Count(s, "-") != 4 {
		return false
	}
	for _, c := range s {
		switch {
		case c >= '0' && c <= '9', c >= 'a' && c <= 'f', c >= 'A' && c <= 'F', c == '-':
		default:
			return false
		}
	}
	return true
}

// mergeBubbleImage 合并重复条目：优先保留带 path、尺寸更完整的一方。
func mergeBubbleImage(a, b bubbleImage) bubbleImage {
	out := a
	if strings.TrimSpace(b.Path) != "" {
		out.Path = b.Path
	}
	if strings.TrimSpace(b.UUID) != "" && strings.TrimSpace(out.UUID) == "" {
		out.UUID = b.UUID
	}
	if b.Dimension.Width > 0 {
		out.Dimension.Width = b.Dimension.Width
	}
	if b.Dimension.Height > 0 {
		out.Dimension.Height = b.Dimension.Height
	}
	return out
}

func loadBubbleImage(img bubbleImage, imagesDir string) ([]byte, bool) {
	if path := strings.TrimSpace(img.Path); path != "" {
		if raw, err := os.ReadFile(path); err == nil && len(raw) > 0 {
			return raw, true
		}
	}
	return loadWorkspaceImage(imagesDir, strings.TrimSpace(img.UUID))
}

func imageMimeFromPath(path string) string {
	switch strings.ToLower(filepath.Ext(path)) {
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".gif":
		return "image/gif"
	case ".webp":
		return "image/webp"
	default:
		return "image/png"
	}
}

func extractToolParts(b bubbleData) []monitor.ContentPart {
	t := b.ToolFormerData
	name := normalizeToolName(t.Name)
	if name == "" {
		return nil
	}
	var parts []monitor.ContentPart
	order := 0
	add := func(p monitor.ContentPart) {
		p.SortOrder = order
		order++
		parts = append(parts, p)
	}
	args := strings.TrimSpace(t.RawArgs)
	if args == "" {
		args = strings.TrimSpace(t.Params)
	}
	if args != "" {
		add(monitor.ContentPart{
			Type:          "tool_call",
			ToolName:      name,
			ArgumentsJSON: truncateBytes(args, maxToolSnippetBytes),
		})
	}
	if res := strings.TrimSpace(t.Result); res != "" {
		add(monitor.ContentPart{
			Type:     "tool_result",
			ToolName: name,
			Text:     truncateBytes(res, maxToolSnippetBytes),
		})
	}
	if note := strings.TrimSpace(b.Text); note != "" {
		add(monitor.ContentPart{Type: "text", Text: truncateBytes(note, maxTextBytesPerPart)})
	}
	return parts
}

// loadWorkspaceImage 读取 workspaceStorage/<hash>/images/<uuid>-*.png（取最大文件）。
func loadWorkspaceImage(imagesDir, uuid string) ([]byte, bool) {
	if imagesDir == "" || uuid == "" {
		return nil, false
	}
	matches, err := filepath.Glob(filepath.Join(imagesDir, uuid+"-*.png"))
	if err != nil || len(matches) == 0 {
		return nil, false
	}
	best := matches[0]
	var bestSize int64
	for _, p := range matches {
		st, err := os.Stat(p)
		if err != nil {
			continue
		}
		if st.Size() > bestSize {
			bestSize = st.Size()
			best = p
		}
	}
	raw, err := os.ReadFile(best)
	if err != nil || len(raw) == 0 {
		return nil, false
	}
	return raw, true
}

func truncateBytes(s string, maxBytes int) string {
	if maxBytes <= 0 || s == "" {
		return s
	}
	b := []byte(s)
	if len(b) <= maxBytes {
		return s
	}
	end := maxBytes
	for end > 0 && !utf8.Valid(b[:end]) {
		end--
	}
	return string(b[:end]) + "…"
}

func appendMessagePartsTo(msgs *[]monitor.Message, extID, role, toolName string, ts time.Time, in, out int, parts []monitor.ContentPart, conversationOrder int) {
	if len(parts) == 0 {
		return
	}
	msg := monitor.Message{
		ExternalMessageID: extID,
		Role:              role,
		ContentParts:      parts,
		ToolName:          toolName,
		Timestamp:         monitor.LocalTime(ts),
		ConversationOrder: conversationOrder,
		InputTokens:       in,
		OutputTokens:      out,
	}
	monitor.FinalizeMessage(&msg)
	*msgs = append(*msgs, msg)
}
