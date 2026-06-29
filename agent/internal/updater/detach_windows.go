//go:build windows

// Windows 分离子进程：CREATE_NEW_PROCESS_GROUP，供分离启动的 update 使用。
//
// gz
package updater

import "syscall"

func sysProcAttrDetached() *syscall.SysProcAttr {
	// CREATE_NO_WINDOW (0x08000000)：分离启动的 `aiwatchd update` 子进程不弹控制台窗口（v1.0.19）。
	return &syscall.SysProcAttr{
		CreationFlags: syscall.CREATE_NEW_PROCESS_GROUP | 0x08000000,
		HideWindow:    true,
	}
}
