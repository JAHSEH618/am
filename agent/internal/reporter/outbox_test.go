package reporter

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// newTestOutbox 在临时目录上挂一个 Outbox,绕开 LoadOutbox 的 stateDir 依赖。
func newTestOutbox(t *testing.T, maxFiles, maxDrain int) *Outbox {
	t.Helper()
	dir := t.TempDir()
	o := &Outbox{dir: dir, maxFiles: maxFiles, maxDrainPerCall: maxDrain}
	o.pending.Store(-1)
	return o
}

func countJSON(t *testing.T, dir string) int {
	t.Helper()
	ents, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	n := 0
	for _, e := range ents {
		if strings.HasSuffix(e.Name(), ".json") {
			n++
		}
	}
	return n
}

func TestAppend_EvictsOldestOverCap(t *testing.T) {
	o := newTestOutbox(t, 3, 100)
	for i := 0; i < 6; i++ {
		if err := o.Append([]byte(`{"n":` + string(rune('0'+i)) + `}`)); err != nil {
			t.Fatalf("append %d: %v", i, err)
		}
	}
	if got := countJSON(t, o.dir); got != 3 {
		t.Fatalf("files on disk = %d, want 3 (capped)", got)
	}
	// 留存的应是最新 3 个(文件名时序升序,最旧被删)
	list, err := o.list()
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 3 {
		t.Fatalf("list = %d, want 3", len(list))
	}
}

func TestDrain_PacedToMaxPerCall(t *testing.T) {
	o := newTestOutbox(t, 100, 2)
	for i := 0; i < 5; i++ {
		if err := o.Append([]byte("{}")); err != nil {
			t.Fatal(err)
		}
	}
	var sentTotal int
	send := func(_ context.Context, _ []byte) error { sentTotal++; return nil }

	n, err := o.Drain(context.Background(), send)
	if err != nil || n != 2 {
		t.Fatalf("first drain = (%d,%v), want (2,nil)", n, err)
	}
	if got := o.Pending(); got != 3 {
		t.Fatalf("pending after first drain = %d, want 3", got)
	}
	n2, err := o.Drain(context.Background(), send)
	if err != nil || n2 != 2 {
		t.Fatalf("second drain = (%d,%v), want (2,nil)", n2, err)
	}
	n3, err := o.Drain(context.Background(), send)
	if err != nil || n3 != 1 {
		t.Fatalf("third drain = (%d,%v), want (1,nil)", n3, err)
	}
	if got := countJSON(t, o.dir); got != 0 {
		t.Fatalf("files left = %d, want 0", got)
	}
	if sentTotal != 5 {
		t.Fatalf("sentTotal = %d, want 5", sentTotal)
	}
}

func TestDrain_StopsOnSendError(t *testing.T) {
	o := newTestOutbox(t, 100, 100)
	for i := 0; i < 4; i++ {
		if err := o.Append([]byte("{}")); err != nil {
			t.Fatal(err)
		}
	}
	calls := 0
	send := func(_ context.Context, _ []byte) error {
		calls++
		if calls == 3 {
			return errors.New("boom")
		}
		return nil
	}
	n, err := o.Drain(context.Background(), send)
	if err == nil || n != 2 {
		t.Fatalf("drain = (%d,%v), want (2, error)", n, err)
	}
	// 失败那条及其后的仍在盘上(2 已删 + boom 当条未删 = 剩 2)
	if got := countJSON(t, o.dir); got != 2 {
		t.Fatalf("files left = %d, want 2", got)
	}
	_ = filepath.Separator // keep filepath imported if unused otherwise
}
