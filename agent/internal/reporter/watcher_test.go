// 文件级活动监听单测：newestMTime 对"目录下叶子文件 append"的捕获 + scanAll 的基线/推进语义。
// 用 os.Chtimes 写定值 mtime，避免依赖 wall-clock 分辨率导致 flaky。
//
// gz
package reporter

import (
	"context"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

func TestNewestMTimeFileAndMissing(t *testing.T) {
	dir := t.TempDir()
	f := filepath.Join(dir, "a.jsonl")
	if err := os.WriteFile(f, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if got := newestMTime(f); got == 0 {
		t.Error("existing file mtime should be non-zero")
	}
	if got := newestMTime(filepath.Join(dir, "nope")); got != 0 {
		t.Errorf("missing path should be 0, got %d", got)
	}
}

// 模拟 claude 的 projects/<proj>/<sess>.jsonl 结构：对已存在叶子文件 append 只更新叶子 mtime，
// 父目录 mtime 不变——newestMTime 必须递归到叶子才能捕获。
func TestNewestMTimeDirCatchesLeafAppend(t *testing.T) {
	root := t.TempDir()
	proj := filepath.Join(root, "proj")
	if err := os.MkdirAll(proj, 0o755); err != nil {
		t.Fatal(err)
	}
	leaf := filepath.Join(proj, "sess.jsonl")
	if err := os.WriteFile(leaf, []byte("a"), 0o644); err != nil {
		t.Fatal(err)
	}

	old := time.Unix(1_000_000, 0)
	newer := time.Unix(2_000_000, 0)
	for _, p := range []string{root, proj, leaf} {
		if err := os.Chtimes(p, old, old); err != nil {
			t.Fatal(err)
		}
	}
	if got := newestMTime(root); got != old.UnixNano() {
		t.Fatalf("baseline newest = %d, want %d", got, old.UnixNano())
	}
	// 只动叶子（append 语义），父目录保持 old。
	if err := os.Chtimes(leaf, newer, newer); err != nil {
		t.Fatal(err)
	}
	if got := newestMTime(root); got != newer.UnixNano() {
		t.Errorf("after leaf append newest = %d, want %d (recursive scan must reach leaf)", got, newer.UnixNano())
	}
}

func TestWatcherScanAllBaselineThenAdvance(t *testing.T) {
	root := t.TempDir()
	f := filepath.Join(root, "s.jsonl")
	if err := os.WriteFile(f, []byte("a"), 0o644); err != nil {
		t.Fatal(err)
	}
	t0 := time.Unix(1_000_000, 0)
	for _, p := range []string{root, f} {
		if err := os.Chtimes(p, t0, t0); err != nil {
			t.Fatal(err)
		}
	}
	w := &activityWatcher{hints: []string{root}, last: make(map[string]int64), interval: watchPollInterval}

	if w.scanAll() {
		t.Error("first scan should establish baseline, not advance")
	}
	if w.scanAll() {
		t.Error("no change should not advance")
	}
	t1 := time.Unix(2_000_000, 0)
	if err := os.Chtimes(f, t1, t1); err != nil {
		t.Fatal(err)
	}
	if !w.scanAll() {
		t.Error("touch should advance")
	}
	if w.scanAll() {
		t.Error("same mtime after an advance should not re-advance")
	}
}

// 暂停期间不扫描（基线不动）、不触发；恢复后的首轮只重建基线，之后的新变更照常触发。
func TestWatcherPollPausedSkipsScanThenRebaselines(t *testing.T) {
	root := t.TempDir()
	f := filepath.Join(root, "s.jsonl")
	if err := os.WriteFile(f, []byte("a"), 0o644); err != nil {
		t.Fatal(err)
	}
	t0 := time.Unix(1_000_000, 0)
	for _, p := range []string{root, f} {
		if err := os.Chtimes(p, t0, t0); err != nil {
			t.Fatal(err)
		}
	}
	var paused atomic.Bool
	ch := make(chan struct{}, 1)
	w := &activityWatcher{hints: []string{root}, triggerCh: ch, last: make(map[string]int64),
		interval: watchPollInterval, paused: paused.Load}
	w.scanAll() // run() 的首轮基线

	mtime := t0
	for i, st := range []struct {
		paused     bool
		touch      bool
		wantSignal bool
		wantLast   int64 // 本轮后基线；0 = 不检查
	}{
		{paused: false, touch: false, wantSignal: false},
		{paused: true, touch: true, wantSignal: false, wantLast: t0.UnixNano()}, // 暂停：不扫、基线不动
		{paused: true, touch: true, wantSignal: false, wantLast: t0.UnixNano()},
		{paused: false, touch: false, wantSignal: false}, // 恢复首轮：只重建基线
		{paused: false, touch: false, wantSignal: false},
		{paused: false, touch: true, wantSignal: true}, // 恢复后的新变更照常触发
		{paused: false, touch: false, wantSignal: false},
	} {
		paused.Store(st.paused)
		if st.touch {
			mtime = mtime.Add(time.Hour)
			if err := os.Chtimes(f, mtime, mtime); err != nil {
				t.Fatal(err)
			}
		}
		w.poll()
		var got bool
		select {
		case <-ch:
			got = true
		default:
		}
		if got != st.wantSignal {
			t.Fatalf("step %d: signal=%v want %v", i, got, st.wantSignal)
		}
		if st.wantLast != 0 && w.last[root] != st.wantLast {
			t.Fatalf("step %d: baseline=%d want %d (paused poll must not scan)", i, w.last[root], st.wantLast)
		}
	}
}

// run 在 watcher goroutine 里读 paused（reporter 侧并发翻转 atomic）；配合 go test -race 守住无数据竞争。
func TestWatcherRunConcurrentPauseToggle(t *testing.T) {
	root := t.TempDir()
	var paused atomic.Bool
	ch := make(chan struct{}, 1)
	w := &activityWatcher{hints: []string{root}, triggerCh: ch, last: make(map[string]int64),
		interval: time.Millisecond, paused: paused.Load}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		w.run(ctx)
		close(done)
	}()
	for i := 0; i < 50; i++ {
		paused.Store(i%2 == 0)
		time.Sleep(200 * time.Microsecond)
	}
	cancel()
	<-done
}

func TestWatcherSignalNonBlocking(t *testing.T) {
	ch := make(chan struct{}, 1)
	w := &activityWatcher{triggerCh: ch}
	// 连发两次：第一次入缓冲，第二次缓冲已满走 default 丢弃，均不应阻塞。
	w.signal()
	w.signal()
	select {
	case <-ch:
	default:
		t.Error("expected one buffered signal")
	}
	select {
	case <-ch:
		t.Error("expected only one signal buffered (second dropped)")
	default:
	}
}
