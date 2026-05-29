//go:build windows

// Windows 分离子进程：CREATE_NEW_PROCESS_GROUP，供分离启动的 update 使用。
//
// gz
package updater

import "syscall"

func sysProcAttrDetached() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{
		CreationFlags: syscall.CREATE_NEW_PROCESS_GROUP,
	}
}
