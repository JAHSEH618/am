//go:build darwin || linux

// 非 Windows 单实例：state 目录下 aiwatchd.lock 的 flock(LOCK_EX|LOCK_NB)。
//
// 两个独立 open 得到两个 open file description，flock 即便在同进程内也互斥（man flock），
// 因此 Acquire 二次调用会如实报告 already-running。锁随进程退出由内核自动释放，故意不 Close。
//
// gz
package singleton

import (
	"os"
	"path/filepath"
	"syscall"

	"github.com/am/aiwatch-agent/internal/config"
)

var lockFile *os.File

// Acquire 抢占单实例锁。语义同 Windows 版：
//
//	(true, nil)  抢到，应继续常驻
//	(false, nil) 已有实例在跑，本次应退出
//	(true, err)  机制本身失败 → fail-open 放行
func Acquire() (bool, error) {
	dir, err := config.StateDir() // 内部已 MkdirAll
	if err != nil {
		return true, err
	}
	path := filepath.Join(dir, "aiwatchd.lock")
	f, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0o644)
	if err != nil {
		return true, err
	}
	if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		_ = f.Close()
		if err == syscall.EWOULDBLOCK {
			return false, nil
		}
		return true, err
	}
	lockFile = f
	return true, nil
}
