//go:build darwin || linux

// Unix 分离子进程：`aiwatchd update` 与自动升级子进程使用的 SysProcAttr。
//
// gz
package updater

import "syscall"

func sysProcAttrDetached() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{
		Setsid: true,
	}
}
