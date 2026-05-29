// Package monitor — 结构化消息内容段（全量输入采集 v1）。
// gz
package monitor

import (
	"bytes"
	"compress/gzip"
	"encoding/base64"
	"fmt"
	"strings"
)

// ContentPart 单段内容，与服务端 ContentPartDto 对齐。
type ContentPart struct {
	Type           string `json:"type"`
	Text           string `json:"text,omitempty"`
	Mime           string `json:"mime,omitempty"`
	Path           string `json:"path,omitempty"`
	OldPath        string `json:"old_path,omitempty"`
	Language       string `json:"language,omitempty"`
	StartLine      int    `json:"start_line,omitempty"`
	EndLine        int    `json:"end_line,omitempty"`
	ToolName       string `json:"tool_name,omitempty"`
	ArgumentsJSON  string `json:"arguments_json,omitempty"`
	BlobGzipBase64 string `json:"blob_gzip_base64,omitempty"`
	Width          int    `json:"width,omitempty"`
	Height         int    `json:"height,omitempty"`
	Truncated      bool   `json:"truncated,omitempty"`
	TruncateReason string `json:"truncate_reason,omitempty"`
	SortOrder      int    `json:"sort_order"`
}

// FlattenParts 生成 legacy text 字段（上报兼容旧服务端）。
func FlattenParts(parts []ContentPart) string {
	if len(parts) == 0 {
		return ""
	}
	var sb strings.Builder
	for _, p := range parts {
		switch p.Type {
		case "text", "thinking", "system_context", "tool_result":
			if p.Text != "" {
				if p.Type == "system_context" {
					sb.WriteString("--- system ---\n")
				}
				sb.WriteString(p.Text)
				if !strings.HasSuffix(p.Text, "\n") {
					sb.WriteByte('\n')
				}
			}
		case "file_ref", "file_snippet":
			if p.Path != "" {
				if p.StartLine > 0 && p.EndLine > 0 {
					sb.WriteString(fmt.Sprintf("%s:%d-%d\n", p.Path, p.StartLine, p.EndLine))
				} else {
					sb.WriteString(p.Path)
					sb.WriteByte('\n')
				}
			}
			if p.Text != "" {
				sb.WriteString(p.Text)
				sb.WriteByte('\n')
			}
		case "image":
			if p.Width > 0 && p.Height > 0 {
				sb.WriteString(fmt.Sprintf("[image %dx%d]\n", p.Width, p.Height))
			} else {
				sb.WriteString("[image]\n")
			}
		case "tool_call":
			sb.WriteString("[tool_call ")
			sb.WriteString(p.ToolName)
			sb.WriteString("]\n")
			if p.ArgumentsJSON != "" {
				sb.WriteString(p.ArgumentsJSON)
				sb.WriteByte('\n')
			}
		}
	}
	return strings.TrimSpace(sb.String())
}

// GzipBase64 压缩并 base64 编码（小图内联上报）。
func GzipBase64(raw []byte) (string, error) {
	var buf bytes.Buffer
	zw := gzip.NewWriter(&buf)
	if _, err := zw.Write(raw); err != nil {
		return "", err
	}
	if err := zw.Close(); err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(buf.Bytes()), nil
}
