package gitlog

import "testing"

func TestSplitUnifiedDiff_twoFiles(t *testing.T) {
	blob := `diff --git a/foo.go b/foo.go
index 111..222 100644
--- a/foo.go
+++ b/foo.go
@@ -1 +1 @@
-old
+new
diff --git a/bar.go b/bar.go
index 333..444 100644
--- a/bar.go
+++ b/bar.go
@@ -1 +1 @@
-x
+y
`
	m := splitUnifiedDiff(blob)
	if len(m) != 2 {
		t.Fatalf("want 2 files got %d", len(m))
	}
	if _, ok := m["foo.go"]; !ok {
		t.Fatalf("missing foo.go keys=%v", m)
	}
	if _, ok := m["bar.go"]; !ok {
		t.Fatalf("missing bar.go")
	}
}
