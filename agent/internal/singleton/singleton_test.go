package singleton

import "testing"

// 同一进程内二次 Acquire 应：首次抢到、二次报告 already-running。
// Windows 命名互斥量与 unix flock（两个独立 OFD）在同进程内均互斥，故两平台行为一致。
func TestAcquireFirstSucceedsSecondReportsRunning(t *testing.T) {
	ok, err := Acquire()
	if err != nil {
		t.Fatalf("first Acquire returned error: %v", err)
	}
	if !ok {
		t.Fatal("first Acquire should succeed (true)")
	}

	ok2, err2 := Acquire()
	if err2 != nil {
		t.Fatalf("second Acquire returned error: %v", err2)
	}
	if ok2 {
		t.Fatal("second Acquire should report already-running (false)")
	}
}
