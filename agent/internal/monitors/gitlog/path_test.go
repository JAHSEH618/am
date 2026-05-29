package gitlog

import "testing"

func TestNormalizeGitPath_quotepathOctal(t *testing.T) {
	raw := `"docs/design/\345\221\230\345\267\245AI\345\262\227\344\275\215\350\222\270\351\246\217-\347\224\262\347\261\273\350\257\206\345\210\253\344\270\216\350\203\275\345\212\233\347\224\273\345\203\217-v1.0.md"`
	got := normalizeGitPath(raw)
	want := "docs/design/员工AI岗位蒸馏-甲类识别与能力画像-v1.0.md"
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
}

func TestNormalizeGitPath_plain(t *testing.T) {
	if got := normalizeGitPath("src/main.go"); got != "src/main.go" {
		t.Fatalf("got %q", got)
	}
}
