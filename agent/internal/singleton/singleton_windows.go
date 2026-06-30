//go:build windows

// Windows 单实例：会话级命名互斥量 Local\aiwatchd-daemon。
//
// 配合 install.ps1 的"每 10 分钟周期自愈触发器"：周期触发器在 daemon 已在跑时拉起的
// 这次 `aiwatchd start` 会拿不到互斥量、干净退出（exit 0），daemon 死了才真正接管。
// 这样"每 N 分钟拉一次"成为安全自愈而非进程风暴。
//
// 句柄故意不 Close、进程退出由 OS 自动释放——daemon 整个生命周期持有锁。
//
// gz
package singleton

import (
	"fmt"
	"syscall"
	"unsafe"
)

const errAlreadyExists = 183 // ERROR_ALREADY_EXISTS

var mutexHandle uintptr

// Acquire 抢占单实例锁。
//
//	(true, nil)  抢到，应继续常驻
//	(false, nil) 已有实例在跑，本次应退出
//	(true, err)  机制本身失败 → fail-open 放行（宁可多跑也不要没有 daemon）
func Acquire() (bool, error) {
	name, err := syscall.UTF16PtrFromString(`Local\aiwatchd-daemon`)
	if err != nil {
		return true, err
	}
	kernel32 := syscall.NewLazyDLL("kernel32.dll")
	h, _, callErr := kernel32.NewProc("CreateMutexW").Call(0, 0, uintptr(unsafe.Pointer(name)))
	if h == 0 {
		return true, fmt.Errorf("CreateMutexW failed: %v", callErr)
	}
	if errno, ok := callErr.(syscall.Errno); ok && uintptr(errno) == errAlreadyExists {
		// 互斥量已存在：另一实例持有。释放本次句柄并报告 already-running。
		_, _, _ = kernel32.NewProc("CloseHandle").Call(h)
		return false, nil
	}
	mutexHandle = h
	return true, nil
}
