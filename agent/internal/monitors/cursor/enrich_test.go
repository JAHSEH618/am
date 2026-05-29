// Cursor content part 富化单元测试。
// gz
package cursor

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/am/aiwatch-agent/internal/monitor"
)

func TestExtractUserParts_AttachedChunks(t *testing.T) {
	b := bubbleData{
		Text: "fix npm",
		AttachedCodeChunks: []attachedCodeChunk{{
			RelativeWorkspacePath: "/tmp/proj/foo.go",
			StartLineNumber:       10,
			Lines:                 []string{"line1", "line2"},
		}},
	}
	parts := extractUserParts(b, "")
	if len(parts) < 2 {
		t.Fatalf("parts=%d want >=2", len(parts))
	}
	var snippet *monitor.ContentPart
	for i := range parts {
		if parts[i].Type == "file_snippet" {
			snippet = &parts[i]
			break
		}
	}
	if snippet == nil {
		t.Fatal("missing file_snippet part")
	}
	if snippet.Path != "/tmp/proj/foo.go" {
		t.Errorf("path=%q", snippet.Path)
	}
	if snippet.StartLine != 10 || snippet.EndLine != 11 {
		t.Errorf("lines=%d-%d", snippet.StartLine, snippet.EndLine)
	}
	if !strings.Contains(snippet.Text, "line1") {
		t.Errorf("snippet text=%q", snippet.Text)
	}
}

func TestExtractToolParts_CallAndResult(t *testing.T) {
	b := bubbleData{
		ToolFormerData: toolFormer{
			Name:    "read_file_v2",
			RawArgs: `{"path":"a.go"}`,
			Result:  "package main",
		},
	}
	parts := extractToolParts(b)
	if len(parts) != 2 {
		t.Fatalf("parts=%+v", parts)
	}
	if parts[0].Type != "tool_call" || parts[1].Type != "tool_result" {
		t.Fatalf("types=%s,%s", parts[0].Type, parts[1].Type)
	}
}

func TestExtractUserParts_SelectedImagesPath(t *testing.T) {
	dir := t.TempDir()
	imgPath := filepath.Join(dir, "shot.png")
	if err := os.WriteFile(imgPath, []byte("\x89PNG\r\n\x1a\nfake"), 0o644); err != nil {
		t.Fatal(err)
	}
	b := bubbleData{
		Text: "see screenshot",
		Context: bubbleContext{
			SelectedImages: []bubbleImage{{
				UUID: "1ec457e0-2dc2-431a-aae3-a1d696b4aa89",
				Path: imgPath,
				Dimension: struct {
					Width  int `json:"width"`
					Height int `json:"height"`
				}{Width: 100, Height: 50},
			}},
		},
	}
	parts := extractUserParts(b, "")
	var imgPart *monitor.ContentPart
	for i := range parts {
		if parts[i].Type == "image" {
			imgPart = &parts[i]
			break
		}
	}
	if imgPart == nil {
		t.Fatalf("parts=%+v", parts)
	}
	if imgPart.BlobGzipBase64 == "" {
		t.Fatal("expected blob payload for image from context.selectedImages path")
	}
	if imgPart.Width != 100 || imgPart.Height != 50 {
		t.Fatalf("dims=%d×%d", imgPart.Width, imgPart.Height)
	}
}

func TestCollectBubbleImages_DedupImagesAndSelected(t *testing.T) {
	contentUUID := "a6324e4f-a86c-412e-b7d6-d66348e6bf45"
	dir := t.TempDir()
	imgPath := filepath.Join(dir, contentUUID+"-ddb97159-1e66-49a0-910d-ac43b8697eb7.png")
	b := bubbleData{
		Images: []bubbleImage{{
			UUID: contentUUID,
			Dimension: struct {
				Width  int `json:"width"`
				Height int `json:"height"`
			}{Width: 1024, Height: 638},
		}},
		Context: bubbleContext{
			SelectedImages: []bubbleImage{{
				UUID: "a053c282-0f80-4ec6-bf33-7dd87d8f88ec",
				Path: imgPath,
				Dimension: struct {
					Width  int `json:"width"`
					Height int `json:"height"`
				}{Width: 1024, Height: 638},
			}},
		},
	}
	got := collectBubbleImages(b)
	if len(got) != 1 {
		t.Fatalf("got %d images, want 1: %+v", len(got), got)
	}
	if got[0].Path != imgPath {
		t.Errorf("path=%q want %q", got[0].Path, imgPath)
	}
}

func TestExtractUserParts_DedupCursorImagePair(t *testing.T) {
	contentUUID := "a6324e4f-a86c-412e-b7d6-d66348e6bf45"
	dir := t.TempDir()
	imgPath := filepath.Join(dir, contentUUID+"-suffix.png")
	if err := os.WriteFile(imgPath, []byte("\x89PNG\r\n\x1a\nfake"), 0o644); err != nil {
		t.Fatal(err)
	}
	b := bubbleData{
		Text: "one screenshot",
		Images: []bubbleImage{{
			UUID: contentUUID,
			Dimension: struct {
				Width  int `json:"width"`
				Height int `json:"height"`
			}{Width: 1024, Height: 638},
		}},
		Context: bubbleContext{
			SelectedImages: []bubbleImage{{
				UUID: "a053c282-0f80-4ec6-bf33-7dd87d8f88ec",
				Path: imgPath,
				Dimension: struct {
					Width  int `json:"width"`
					Height int `json:"height"`
				}{Width: 1024, Height: 638},
			}},
		},
	}
	parts := extractUserParts(b, "")
	var imageParts []monitor.ContentPart
	for _, p := range parts {
		if p.Type == "image" {
			imageParts = append(imageParts, p)
		}
	}
	if len(imageParts) != 1 {
		t.Fatalf("image parts=%d want 1, all=%+v", len(imageParts), parts)
	}
	if imageParts[0].BlobGzipBase64 == "" {
		t.Fatal("expected blob from selectedImages path")
	}
}

func TestImageStorageUUID(t *testing.T) {
	path := "/Users/x/Library/.../images/a6324e4f-a86c-412e-b7d6-d66348e6bf45-ddb97159.png"
	if got := imageStorageUUID(path); got != "a6324e4f-a86c-412e-b7d6-d66348e6bf45" {
		t.Fatalf("got %q", got)
	}
}

func TestLoadWorkspaceImage(t *testing.T) {
	dir := t.TempDir()
	uuid := "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
	path := filepath.Join(dir, uuid+"-suffix.png")
	if err := os.WriteFile(path, []byte("\x89PNG\r\n\x1a\nfake"), 0o644); err != nil {
		t.Fatal(err)
	}
	raw, ok := loadWorkspaceImage(dir, uuid)
	if !ok || len(raw) < 8 {
		t.Fatalf("ok=%v len=%d", ok, len(raw))
	}
}
